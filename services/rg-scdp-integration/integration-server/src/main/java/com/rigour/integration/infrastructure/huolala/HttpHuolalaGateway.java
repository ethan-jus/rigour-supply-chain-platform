package com.rigour.integration.infrastructure.huolala;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;
import com.rigour.integration.application.port.out.HuolalaGateway;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 仅访问官方固定域名和只读方法。令牌以AES-GCM写入部署环境指定的私有持久化目录。 */
@Component
public final class HttpHuolalaGateway implements HuolalaGateway {
    private static final Set<String> READ_METHODS =
            Set.of(
                    "e-order-list",
                    "e-order-detail",
                    "e-staff-construct",
                    "e-staff-detail",
                    "e-depart-detail");
    private final ObjectMapper json;
    private final Function<String, String> env;
    private final HttpClient http;
    private long nextReadNanos;

    @org.springframework.beans.factory.annotation.Autowired
    public HttpHuolalaGateway(ObjectMapper json) {
        this(
                json,
                System::getenv,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    HttpHuolalaGateway(ObjectMapper json, Function<String, String> env, HttpClient http) {
        this.json = json;
        this.env = env;
        this.http = http;
    }

    private String prefix(Connection c) {
        if (c.secretRef() == null || !c.secretRef().matches("env://[A-Za-z_][A-Za-z0-9_]*"))
            throw failure("应用凭据引用须为env://环境变量前缀");
        return c.secretRef().substring(6);
    }

    private String required(String name) {
        String value = env.apply(name);
        if (value == null || value.isBlank()) throw failure("货拉拉服务端凭据未配置完整");
        return value;
    }

    @Override
    public boolean ready(Connection c) {
        try {
            required(prefix(c) + "_APP_KEY");
            required(prefix(c) + "_APP_SECRET");
            byte[] key = key();
            Arrays.fill(key, (byte) 0);
            root();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public String authorizeUrl(Connection c) {
        return "https://open.huolala.cn/#/oauth/authorize?response_type=code&client_id="
                + encode(required(prefix(c) + "_APP_KEY"))
                + "&redirect_uri="
                + encode(c.redirectUri())
                + "&isSandbox="
                + c.sandbox();
    }

    @Override
    public Token exchange(Connection c, String code) {
        return token(c, "authorization_code", "code", code);
    }

    @Override
    public Token refresh(Connection c, Token previous) {
        return token(c, "refresh_token", "refresh_token", previous.refreshToken());
    }

    private Token token(Connection c, String grant, String name, String value) {
        String query =
                "grant_type="
                        + grant
                        + "&client_id="
                        + encode(required(prefix(c) + "_APP_KEY"))
                        + "&"
                        + name
                        + "="
                        + encode(value)
                        + "&isSandbox="
                        + c.sandbox();
        JsonNode result =
                send(
                        HttpRequest.newBuilder(
                                        URI.create("https://open.huolala.cn/oauth/token?" + query))
                                .timeout(Duration.ofSeconds(20))
                                .GET()
                                .build());
        JsonNode data = result.has("data") ? result.path("data") : result;
        String access = data.path("access_token").asText(""),
                refresh = data.path("refresh_token").asText("");
        if (access.isBlank() || refresh.isBlank()) throw failure("货拉拉未返回完整授权令牌，请重新授权");
        long seconds = data.path("expires_in").asLong(data.path("expire_in").asLong(0));
        Instant expiry;
        if (seconds > 0) expiry = Instant.now().plusSeconds(seconds);
        else {
            long epoch = data.path("expire_time").asLong(0);
            if (epoch <= 0) throw failure("货拉拉未返回可识别的令牌有效期");
            expiry =
                    epoch > 10_000_000_000L
                            ? Instant.ofEpochMilli(epoch)
                            : Instant.ofEpochSecond(epoch);
        }
        if (!expiry.isAfter(Instant.now().plusSeconds(30))) throw failure("货拉拉授权令牌已过期");
        return new Token(access, refresh, expiry);
    }

    @Override
    public JsonNode read(Connection c, Token token, String method, Map<String, Object> parameters) {
        if (!READ_METHODS.contains(method)) throw failure("当前接入仅允许订单及员工资料查询");
        throttle();
        Map<String, String> envelope = new TreeMap<>();
        envelope.put("api_method", method);
        envelope.put("api_version", "1.0");
        envelope.put("app_key", required(prefix(c) + "_APP_KEY"));
        envelope.put("access_token", token.accessToken());
        envelope.put("timestamp", Long.toString(Instant.now().getEpochSecond()));
        envelope.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        envelope.put("api_data", json.writeValueAsString(parameters));
        envelope.put("signature", signature(envelope, required(prefix(c) + "_APP_SECRET")));
        URI uri =
                URI.create(
                        c.sandbox()
                                ? "https://openapi-pre.huolala.cn/v1"
                                : "https://openapi.huolala.cn/v1");
        JsonNode result =
                send(
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(20))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                json.writeValueAsString(envelope),
                                                StandardCharsets.UTF_8))
                                .build());
        if (!result.has("ret") || !"0".equals(result.path("ret").asText()))
            throw failure("货拉拉查询未成功（响应码" + safeCode(result.path("ret").asText("UNKNOWN")) + "）");
        return result.path("data");
    }

    private synchronized void throttle() {
        String configured = env.apply("RIGOUR_HUOLALA_REQUEST_INTERVAL_MS");
        long interval = configured == null ? 500 : Long.parseLong(configured);
        if (interval < 100 || interval > 10000) throw failure("货拉拉请求间隔配置须在100～10000毫秒之间");
        long remaining = nextReadNanos - System.nanoTime();
        if (remaining > 0)
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure("货拉拉请求已中断");
            }
        nextReadNanos = System.nanoTime() + interval * 1_000_000L;
    }

    public static String signature(Map<String, String> parameters, String secret) {
        String base =
                new TreeMap<>(parameters)
                                .entrySet().stream()
                                        .filter(
                                                e ->
                                                        !"signature".equals(e.getKey())
                                                                && e.getValue() != null
                                                                && !e.getValue().isEmpty())
                                        .map(e -> e.getKey() + "=" + e.getValue())
                                        .collect(Collectors.joining("&"))
                        + secret;
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("MD5")
                                    .digest(base.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("签名算法不可用");
        }
    }

    private JsonNode send(HttpRequest request) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var stream = response.body()) {
                    int status = response.statusCode();
                    if ((status == 429 || status >= 500) && attempt < 2) {
                        Thread.sleep(500L * (attempt + 1));
                        continue;
                    }
                    if (status < 200 || status >= 300)
                        throw failure("货拉拉请求失败（HTTP " + status + "），请稍后重试或检查授权");
                    byte[] bytes = stream.readNBytes(2_000_001);
                    if (bytes.length > 2_000_000) throw failure("货拉拉响应超过处理上限");
                    return json.readTree(bytes);
                }
            } catch (tools.jackson.core.JacksonException e) {
                throw failure("货拉拉响应格式异常，请重新查询核对");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure("货拉拉同步已中断");
            } catch (java.io.IOException e) {
                if (attempt == 2) throw failure("货拉拉网络连接失败，请稍后重试");
            }
        }
        throw failure("货拉拉请求失败");
    }

    @Override
    public Token load(String tenant, String connector) {
        Path path = path(tenant, connector);
        try {
            Path root = root();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)
                            .equals(PosixFilePermissions.fromString("rwx------")))
                throw failure("令牌目录必须为私有真实目录（700）");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                            .equals(PosixFilePermissions.fromString("rw-------")))
                throw failure("授权令牌未配置或文件权限不正确");
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(65537);
            }
            if (bytes.length < 30 || bytes.length > 65536 || bytes[0] != 1)
                throw failure("授权令牌文件格式无效");
            Cipher cipher =
                    cipher(
                            Cipher.DECRYPT_MODE,
                            Arrays.copyOfRange(bytes, 1, 13),
                            tenant,
                            connector);
            byte[] plain = cipher.doFinal(bytes, 13, bytes.length - 13);
            try {
                return json.readValue(plain, Token.class);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (tools.jackson.core.JacksonException e) {
            throw failure("授权令牌格式无效，请重新授权");
        } catch (java.io.IOException | GeneralSecurityException e) {
            throw failure("授权令牌无法读取，请检查持久化目录或重新授权");
        }
    }

    @Override
    public void save(String tenant, String connector, Token token) {
        Path root = root(), temporary = null;
        byte[] plain = json.writeValueAsBytes(token);
        try {
            Files.createDirectories(
                    root,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS)
                            .equals(PosixFilePermissions.fromString("rwx------")))
                throw failure("令牌目录必须为私有真实目录（700）");
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, iv, tenant, connector).doFinal(plain);
            byte[] envelope =
                    ByteBuffer.allocate(13 + encrypted.length)
                            .put((byte) 1)
                            .put(iv)
                            .put(encrypted)
                            .array();
            temporary =
                    Files.createTempFile(
                            root,
                            ".hll-",
                            ".enc",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            Files.write(temporary, envelope);
            Files.move(
                    temporary,
                    path(tenant, connector),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException | GeneralSecurityException e) {
            throw failure("授权令牌保存失败，未完成授权");
        } finally {
            Arrays.fill(plain, (byte) 0);
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                }
        }
    }

    private Path root() {
        Path value = Path.of(required("RIGOUR_HUOLALA_TOKEN_DIR"));
        if (!value.isAbsolute()) throw failure("令牌目录须使用部署环境的绝对路径");
        return value.normalize();
    }

    private Path path(String tenant, String connector) {
        return root().resolve(UUID.fromString(tenant) + "-" + UUID.fromString(connector) + ".enc");
    }

    private byte[] key() {
        try {
            byte[] key = Base64.getDecoder().decode(required("RIGOUR_HUOLALA_TOKEN_KEY"));
            if (key.length != 32) throw failure("令牌加密密钥须为32字节Base64值");
            return key;
        } catch (IllegalArgumentException e) {
            throw failure("令牌加密密钥格式无效");
        }
    }

    private Cipher cipher(int mode, byte[] iv, String tenant, String connector)
            throws GeneralSecurityException {
        byte[] key = key();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD((tenant + ":" + connector).getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String safeCode(String value) {
        return value.matches("[A-Za-z0-9_-]{1,30}") ? value : "UNKNOWN";
    }

    private static IllegalStateException failure(String message) {
        return new IllegalStateException(message);
    }
}
