package com.rigour.integration.infrastructure.persistence.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** 两条已产生真实历史的 V34 保留原文件，只解析与本库历史一致的一条。 其余迁移仍交给 Flyway 完整校验，V35 前向补齐另一分支变更。 */
public final class IntegrationV34MigrationResources implements ResourceProvider {
    private static final String ALTERNATE = "V34__retire_feishu_import_configuration.sql";
    private static final String CANONICAL = "V34__dhb_carrier_backfill_progress.sql";
    private static final Map<String, Fingerprint> ORIGINALS =
            Map.of(
                    ALTERNATE,
                            new Fingerprint(
                                    "retire feishu import configuration",
                                    1051428510,
                                    "3ee4304b8107c96014f6aaff5ebfd98192a537ba845b4899a5ce0bda34f8a79c"),
                    CANONICAL,
                            new Fingerprint(
                                    "dhb carrier backfill progress",
                                    864899347,
                                    "727f5429796b2457fb1b129391631977635c40c74070349411743b0a750e2f61"));
    private final List<LoadableResource> resources;

    private record Fingerprint(String description, int checksum, String sha256) {}

    private IntegrationV34MigrationResources(List<LoadableResource> resources) {
        this.resources = List.copyOf(resources);
    }

    public static void configure(FluentConfiguration configuration) {
        if (configuration.getResourceProvider() instanceof IntegrationV34MigrationResources) return;
        if (configuration.getResourceProvider() != null)
            throw new FlywayException("Integration 历史兼容不能覆盖其他迁移资源提供器");
        var resolver = new PathMatchingResourcePatternResolver(configuration.getClassLoader());
        Map<String, LoadableResource> all = new LinkedHashMap<>();
        Map<String, Integer> originals = new HashMap<>();
        try {
            for (var location : configuration.getLocations()) {
                String pattern;
                if (location.isClassPath()) pattern = "classpath*:" + location.getPath();
                else if (location.isFileSystem()) pattern = "file:" + location.getPath();
                else throw new FlywayException("Integration 历史兼容仅支持 classpath / filesystem 迁移位置");
                for (var resource :
                        resolver.getResources(pattern.replaceAll("/+$", "") + "/**/*")) {
                    if (!resource.isReadable() || resource.getFilename() == null) continue;
                    String path = resource.getURL().toExternalForm();
                    if (all.containsKey(path)) continue;
                    var expected = ORIGINALS.get(resource.getFilename());
                    if (expected != null) {
                        String digest =
                                HexFormat.of()
                                        .formatHex(
                                                MessageDigest.getInstance("SHA-256")
                                                        .digest(resource.getContentAsByteArray()));
                        if (!expected.sha256().equals(digest))
                            throw new FlywayException(
                                    "已冻结的 Integration V34 文件发生变化：" + resource.getFilename());
                        originals.merge(resource.getFilename(), 1, Integer::sum);
                    }
                    all.put(path, new SqlResource(resource, configuration.getEncoding()));
                }
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new FlywayException("无法读取 Integration 历史迁移资源", e);
        }
        if (!originals.equals(Map.of(ALTERNATE, 1, CANONICAL, 1)))
            throw new FlywayException("Integration 历史兼容要求两份原版 V34 文件各一份，不能缺失或重复");
        String selected = selectedHistory(configuration);
        configuration.ignoreMigrationPatterns(new String[0]);
        configuration.resourceProvider(
                new IntegrationV34MigrationResources(
                        all.values().stream()
                                .filter(
                                        r ->
                                                !ORIGINALS.containsKey(r.getFilename())
                                                        || selected.equals(r.getFilename()))
                                .toList()));
    }

    private static String selectedHistory(FluentConfiguration configuration) {
        try (var connection = configuration.getDataSource().getConnection()) {
            connection.setReadOnly(true);
            String quote = connection.getMetaData().getIdentifierQuoteString().trim();
            String table = identifier(configuration.getTable(), quote);
            if (configuration.getDefaultSchema() != null)
                table = identifier(configuration.getDefaultSchema(), quote) + "." + table;
            String columns =
                    String.join(
                            ",",
                            List.of("description", "type", "script", "checksum", "success").stream()
                                    .map(c -> identifier(c, quote))
                                    .toList());
            try (var statement =
                            connection.prepareStatement(
                                    "SELECT "
                                            + columns
                                            + " FROM "
                                            + table
                                            + " WHERE "
                                            + identifier("version", quote)
                                            + "='34'");
                    var rows = statement.executeQuery()) {
                if (!rows.next()) return CANONICAL;
                String script = rows.getString("script");
                var known = ORIGINALS.get(script);
                if (known == null
                        || !known.description().equals(rows.getString("description"))
                        || !"SQL".equals(rows.getString("type"))
                        || !rows.getBoolean("success")
                        || rows.getObject("checksum") == null
                        || rows.getInt("checksum") != known.checksum()
                        || rows.next()) throw new FlywayException("Integration V34 历史不是已核实的成功原版，禁止自动迁移");
                return script;
            } catch (SQLException e) {
                if (Set.of("42S02", "42P01", "42S04")
                        .contains(Objects.toString(e.getSQLState(), ""))) return CANONICAL;
                throw e;
            }
        } catch (SQLException e) {
            throw new FlywayException("无法核验 Integration V34 历史，禁止自动迁移", e);
        }
    }

    private static String identifier(String value, String quote) {
        if (value == null || !value.matches("[A-Za-z0-9_]+"))
            throw new FlywayException("Integration 迁移历史表或 schema 名称无效");
        return quote + value + quote;
    }

    @Override
    public LoadableResource getResource(String name) {
        return resources.stream()
                .filter(
                        r ->
                                r.getRelativePath().equals(name)
                                        || r.getAbsolutePath().equals(name)
                                        || r.getFilename().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        return resources.stream()
                .filter(
                        r ->
                                r.getFilename().startsWith(prefix)
                                        && Arrays.stream(suffixes)
                                                .anyMatch(r.getFilename()::endsWith))
                .toList();
    }

    private static final class SqlResource extends LoadableResource {
        private final Resource resource;
        private final Charset encoding;

        SqlResource(Resource resource, Charset encoding) {
            this.resource = resource;
            this.encoding = encoding;
        }

        @Override
        public Reader read() {
            try {
                return new InputStreamReader(resource.getInputStream(), encoding);
            } catch (IOException e) {
                throw new FlywayException("无法读取迁移：" + getFilename(), e);
            }
        }

        @Override
        public String getAbsolutePath() {
            try {
                return resource.getURL().toExternalForm();
            } catch (IOException e) {
                throw new FlywayException("无法定位迁移：" + getFilename(), e);
            }
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return getAbsolutePath();
        }

        @Override
        public String getFilename() {
            return resource.getFilename();
        }

        @Override
        public String getRelativePath() {
            return getFilename();
        }
    }
}
