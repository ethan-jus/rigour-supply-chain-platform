package com.rigour.platform.secrets;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 本机凭据存储。仅使用 JDK，可同时打包成离线管理工具；不输出凭据。 */
public final class LocalSecretStore {
    private static final byte[] MAGIC = {'R', 'G', 'S', 1};
    private static final int MAX_BYTES = 65_536;
    private static final Set<String> SERVICES = Set.of(
            "gateway", "iam", "integration", "crm", "erp", "order",
            "sales", "ai", "bi", "hr", "foundation");
    private static final Set<String> COMMON = Set.of(
            "spring.cloud.nacos.username", "spring.cloud.nacos.password",
            "rigour.context.trust.keys-base64.v1");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path root;

    public LocalSecretStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public void initialize() throws IOException {
        directory(root);
        directory(root.resolve("keys"));
        directory(root.resolve("secrets"));
        // 绝不能通过重新 init 丢掉能解密现有凭据的唯一主密钥。
        try (var files = Files.list(root.resolve("secrets"))) {
            if (files.findAny().isPresent()) {
                throw new IOException("secrets 目录非空，拒绝初始化主密钥");
            }
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            Files.createFile(keyPath(), PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            Files.write(keyPath(), key, StandardOpenOption.WRITE);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    public void set(String scope, String property, String value) throws IOException, GeneralSecurityException {
        validateScope(scope);
        validateEntry(scope, property, value);
        checkLayout();
        Path destination = scopePath(scope);
        Map<String, String> values = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                ? read(scope) : new LinkedHashMap<>();
        values.put(property, value);
        Properties properties = new Properties();
        properties.putAll(values);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, null);
        byte[] plain = output.toByteArray();
        if (plain.length > MAX_BYTES - 32) {
            throw new IOException("凭据文件过大");
        }
        byte[] key = key();
        Path temporary = null;
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, iv, scope);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] envelope = ByteBuffer.allocate(MAGIC.length + iv.length + encrypted.length)
                    .put(MAGIC).put(iv).put(encrypted).array();
            temporary = Files.createTempFile(root.resolve("secrets"), ".new-", ".enc",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, envelope);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plain, (byte) 0);
            if (temporary != null) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public Map<String, String> load(String service) throws IOException, GeneralSecurityException {
        if (!SERVICES.contains(service)) {
            throw new IllegalArgumentException("未知服务名称");
        }
        Map<String, String> result = read("common");
        for (String field : COMMON) {
            require(result, field);
        }
        byte[] trust;
        try {
            trust = Base64.getDecoder().decode(result.get("rigour.context.trust.keys-base64.v1"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("现有 HMAC v1 必须是合法 Base64");
        }
        int trustLength = trust.length;
        Arrays.fill(trust, (byte) 0);
        if (trustLength < 32) {
            throw new IllegalArgumentException("现有 HMAC v1 解码后必须至少 32 字节");
        }
        boolean databaseService = !Set.of("gateway", "ai").contains(service);
        if (databaseService || Files.exists(scopePath(service), LinkOption.NOFOLLOW_LINKS)) {
            result.putAll(read(service));
        }
        if (databaseService) {
            require(result, "spring.datasource.username");
            require(result, "spring.datasource.password");
        }
        return result;
    }

    private Map<String, String> read(String scope) throws IOException, GeneralSecurityException {
        checkLayout();
        byte[] envelope = readPrivate(scopePath(scope));
        if (envelope.length < MAGIC.length + 12 + 16
                || !Arrays.equals(MAGIC, Arrays.copyOf(envelope, MAGIC.length))) {
            throw new IOException("不支持或不完整的密文格式");
        }
        byte[] key = key();
        byte[] plain = null;
        try {
            byte[] iv = Arrays.copyOfRange(envelope, MAGIC.length, MAGIC.length + 12);
            plain = cipher(Cipher.DECRYPT_MODE, key, iv, scope)
                    .doFinal(envelope, MAGIC.length + 12, envelope.length - MAGIC.length - 12);
            Properties properties = new Properties() {
                @Override
                public synchronized Object put(Object name, Object value) {
                    if (containsKey(name)) {
                        throw new IllegalArgumentException("凭据含重复属性");
                    }
                    return super.put(name, value);
                }
            };
            properties.load(new ByteArrayInputStream(plain));
            Map<String, String> result = new LinkedHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                String value = properties.getProperty(name);
                validateEntry(scope, name, value);
                result.put(name, value);
            }
            return result;
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] iv, String scope) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("rigour-local-secrets-v1:" + scope).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private byte[] key() throws IOException {
        byte[] key = readPrivate(keyPath());
        if (key.length != 32) {
            Arrays.fill(key, (byte) 0);
            throw new IOException("主密钥格式不正确，禁止重新 init 覆盖");
        }
        return key;
    }

    private Path keyPath() {
        return root.resolve("keys/master.key");
    }

    private Path scopePath(String scope) {
        validateScope(scope);
        return root.resolve("secrets").resolve(scope + ".enc");
    }

    private void checkLayout() throws IOException {
        checkPrivate(root, true);
        checkPrivate(root.resolve("keys"), true);
        checkPrivate(root.resolve("secrets"), true);
    }

    private static void directory(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        }
        checkPrivate(path, true);
    }

    private static void checkPrivate(Path path, boolean directory) throws IOException {
        boolean valid = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        String expected = directory ? "rwx------" : "rw-------";
        if (!valid || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                .equals(PosixFilePermissions.fromString(expected))) {
            throw new IOException("凭据路径必须为真实目录/普通文件；目录权限 700，文件权限 600");
        }
    }

    private static byte[] readPrivate(Path path) throws IOException {
        checkPrivate(path, false);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("凭据文件过大");
            }
            return bytes;
        }
    }

    private static void validateScope(String scope) {
        if (!"common".equals(scope) && !SERVICES.contains(scope)) {
            throw new IllegalArgumentException("未知凭据分组");
        }
    }

    private static void validateEntry(String scope, String property, String value) {
        if (!property.matches("[a-z][a-z0-9.-]*")
                || ("common".equals(scope) != COMMON.contains(property))) {
            throw new IllegalArgumentException("属性名称或所属分组不正确");
        }
        // Spring 会展开 ${...}，禁止把另一个引用误当成真实密钥保存。
        if (value == null || value.isBlank() || value.contains("${") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("请输入真实非空值；本工具不接受含 ${ 的凭据");
        }
    }

    private static void require(Map<String, String> values, String property) {
        if (!values.containsKey(property)) {
            throw new IllegalArgumentException("缺少属性：" + property);
        }
    }
}
