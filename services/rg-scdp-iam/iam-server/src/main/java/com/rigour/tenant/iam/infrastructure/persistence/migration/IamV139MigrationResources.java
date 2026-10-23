package com.rigour.tenant.iam.infrastructure.persistence.migration;

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

/** 两条已产生真实历史的 V139 保留原文件，只解析与本库历史一致的一条。 其余迁移仍交给 Flyway 完整校验，V141 前向补齐另一项权限变更。 */
public final class IamV139MigrationResources implements ResourceProvider {
    private static final String COST = "V139__cost_dashboard_permissions.sql";
    private static final String RISK = "V139__iam_payment_risk_page_read_permission.sql";
    private static final Map<String, Fingerprint> ORIGINALS =
            Map.of(
                    COST,
                            new Fingerprint(
                                    "cost dashboard permissions",
                                    -812060856,
                                    "724b8ff438141ac9502014483c71c7a2f98ba9ad7a15d407874bbfeb33a22210"),
                    RISK,
                            new Fingerprint(
                                    "iam payment risk page read permission",
                                    466362980,
                                    "a6f4b9c0b4c1b3bdc05eaede425b1de0e1060c57819b61fb19f897a6181570b9"));
    private final List<LoadableResource> resources;

    private record Fingerprint(String description, int checksum, String sha256) {}

    private IamV139MigrationResources(List<LoadableResource> resources) {
        this.resources = List.copyOf(resources);
    }

    public static void configure(FluentConfiguration configuration) {
        if (configuration.getResourceProvider() instanceof IamV139MigrationResources) return;
        if (configuration.getResourceProvider() != null)
            throw new FlywayException("IAM 历史兼容不能覆盖其他迁移资源提供器");
        var resolver = new PathMatchingResourcePatternResolver(configuration.getClassLoader());
        Map<String, LoadableResource> all = new LinkedHashMap<>();
        Map<String, Integer> originals = new HashMap<>();
        try {
            for (var location : configuration.getLocations()) {
                String pattern;
                if (location.isClassPath()) pattern = "classpath*:" + location.getPath();
                else if (location.isFileSystem()) pattern = "file:" + location.getPath();
                else throw new FlywayException("IAM 历史兼容仅支持 classpath / filesystem 迁移位置");
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
                                    "已冻结的 IAM V139 文件发生变化：" + resource.getFilename());
                        originals.merge(resource.getFilename(), 1, Integer::sum);
                    }
                    all.put(path, new SqlResource(resource, configuration.getEncoding()));
                }
            }
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new FlywayException("无法读取 IAM 历史迁移资源", e);
        }
        if (!originals.equals(Map.of(COST, 1, RISK, 1)))
            throw new FlywayException("IAM 历史兼容要求两份原版 V139 文件各一份，不能缺失或重复");
        String selected = selectedHistory(configuration);
        configuration.ignoreMigrationPatterns(new String[0]);
        configuration.resourceProvider(
                new IamV139MigrationResources(
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
                                            + "='139'");
                    var rows = statement.executeQuery()) {
                if (!rows.next()) return RISK;
                String script = rows.getString("script");
                var known = ORIGINALS.get(script);
                if (known == null
                        || !known.description().equals(rows.getString("description"))
                        || !"SQL".equals(rows.getString("type"))
                        || !rows.getBoolean("success")
                        || rows.getObject("checksum") == null
                        || rows.getInt("checksum") != known.checksum()
                        || rows.next()) throw new FlywayException("IAM V139 历史不是已核实的成功原版，禁止自动迁移");
                return script;
            } catch (SQLException e) {
                if (Set.of("42S02", "42P01", "42S04")
                        .contains(Objects.toString(e.getSQLState(), ""))) return RISK;
                throw e;
            }
        } catch (SQLException e) {
            throw new FlywayException("无法核验 IAM V139 历史，禁止自动迁移", e);
        }
    }

    private static String identifier(String value, String quote) {
        if (value == null || !value.matches("[A-Za-z0-9_]+"))
            throw new FlywayException("IAM 迁移历史表或 schema 名称无效");
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
