package com.rigour.integration.infrastructure.thirdparty.huolala.http;

import com.rigour.integration.api.v1.model.HuolalaModels.Connection;
import com.rigour.integration.application.port.out.thirdparty.huolala.HuolalaGateway;
import com.rigour.integration.domain.thirdparty.oauth.*;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 仅访问官方固定域名和只读方法。凭据由货拉拉专属服务配置提供，令牌持久化由公共OAuth仓储负责。 */
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
    // 官方 auth_end_time 为中国业务时间，转为 UTC Instant 后持久化，不使用服务器默认时区。
    private static final DateTimeFormatter AUTH_END_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);

    @org.springframework.beans.factory.annotation.Autowired
    public HttpHuolalaGateway(ObjectMapper json, Environment environment) {
        this(
                json,
                name -> configuredCredential(environment, name),
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
    public OAuthToken exchange(Connection c, String code) {
        return token(c, "authorization_code", "code", code);
    }

    @Override
    public OAuthToken refresh(Connection c, OAuthToken previous) {
        return token(c, "refresh_token", "refresh_token", previous.refreshToken());
    }

    private OAuthToken token(Connection c, String grant, String name, String value) {
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
                                .build(),
                        false);
        if (result.has("ret") && !"0".equals(result.path("ret").asText()))
            throw new OAuthFailure(
                    OAuthFailure.Kind.REAUTHORIZE,
                    "TOKEN_REJECTED_" + safeCode(result.path("ret").asText("UNKNOWN")),
                    "货拉拉未接受本次令牌请求，请核对授权后重试");
        JsonNode data = result.has("data") ? result.path("data") : result;
        String access = data.path("access_token").asText(""),
                refresh = data.path("refresh_token").asText("");
        if (access.isBlank() || refresh.isBlank())
            throw new OAuthFailure(
                    OAuthFailure.Kind.REAUTHORIZE, "INVALID_TOKEN_RESPONSE", "货拉拉未返回完整授权令牌，请重新授权");
        Instant receivedAt = Instant.now();
        Instant expiry = tokenExpiry(data, receivedAt);
        if (!expiry.isAfter(receivedAt.plusSeconds(30)))
            throw new OAuthFailure(
                    OAuthFailure.Kind.REAUTHORIZE, "TOKEN_EXPIRED", "货拉拉返回的授权令牌已过期，请重新授权");
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String field :
                List.of(
                        "expires_in",
                        "expire_in",
                        "expire_time",
                        "auth_end_time",
                        "scope",
                        "refresh_expires_in",
                        "refresh_token_expires_in"))
            if (data.has(field))
                metadata.put(field, json.convertValue(data.path(field), Object.class));
        long refreshSeconds =
                data.path("refresh_expires_in")
                        .asLong(data.path("refresh_token_expires_in").asLong(0));
        return new OAuthToken(
                access,
                refresh,
                expiry,
                data.path("token_type").asText(null),
                refreshSeconds > 0 ? receivedAt.plusSeconds(refreshSeconds) : null,
                metadata,
                receivedAt);
    }

    private static Instant tokenExpiry(JsonNode data, Instant receivedAt) {
        String authEndTime = data.path("auth_end_time").asText(null);
        if (authEndTime != null && !authEndTime.isBlank()) {
            try {
                return LocalDateTime.parse(authEndTime, AUTH_END_TIME)
                        .atZone(ZoneId.of("Asia/Shanghai"))
                        .toInstant();
            } catch (DateTimeParseException e) {
                throw new OAuthFailure(
                        OAuthFailure.Kind.REAUTHORIZE,
                        "TOKEN_EXPIRY_INVALID",
                        "货拉拉返回的授权到期时间格式无法识别，请联系管理员核对");
            }
        }
        long seconds = data.path("expires_in").asLong(data.path("expire_in").asLong(0));
        if (seconds > 0) return receivedAt.plusSeconds(seconds);
        long epoch = data.path("expire_time").asLong(0);
        if (epoch > 0)
            return epoch > 10_000_000_000L
                    ? Instant.ofEpochMilli(epoch)
                    : Instant.ofEpochSecond(epoch);
        throw new OAuthFailure(
                OAuthFailure.Kind.REAUTHORIZE, "TOKEN_EXPIRY_MISSING", "货拉拉未返回授权到期时间，请联系管理员核对");
    }

    @Override
    public JsonNode read(
            Connection c, OAuthToken token, String method, Map<String, Object> parameters) {
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
        return send(request, true);
    }

    private JsonNode send(HttpRequest request, boolean retryable) {
        int attempts = retryable ? 3 : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var stream = response.body()) {
                    int status = response.statusCode();
                    if ((status == 429 || status >= 500) && attempt < attempts - 1) {
                        Thread.sleep(500L * (attempt + 1));
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        if (!retryable)
                            throw new OAuthFailure(
                                    status == 429
                                            ? OAuthFailure.Kind.TEMPORARY
                                            : OAuthFailure.Kind.UNCERTAIN,
                                    "TOKEN_HTTP_" + status,
                                    "货拉拉授权请求未完成，请核对授权状态");
                        throw failure("货拉拉请求失败（HTTP " + status + "），请稍后重试或检查授权");
                    }
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
                if (!retryable)
                    throw new OAuthFailure(
                            OAuthFailure.Kind.UNCERTAIN, "TOKEN_NETWORK", "货拉拉令牌请求结果不明，请重新授权");
                if (attempt == attempts - 1) throw failure("货拉拉网络连接失败，请稍后重试");
            }
        }
        throw failure("货拉拉请求失败");
    }

    @Override
    public String appKey(Connection c) {
        return required(prefix(c) + "_APP_KEY");
    }

    private static String configuredCredential(Environment environment, String name) {
        String suffix =
                name.endsWith("_APP_KEY")
                        ? "APP_KEY"
                        : name.endsWith("_APP_SECRET")
                                ? "APP_SECRET"
                                : name.equals("RIGOUR_HUOLALA_REQUEST_INTERVAL_MS")
                                        ? "REQUEST_INTERVAL_MS"
                                        : null;
        String key =
                suffix == null
                        ? null
                        : switch (suffix) {
                            case "APP_KEY" -> "app-key";
                            case "APP_SECRET" -> "app-secret";
                            case "REQUEST_INTERVAL_MS" -> "request-interval-ms";
                            default -> null;
                        };
        String value =
                key == null ? null : environment.getProperty("rigour.thirdparty.huolala." + key);
        return value == null || value.isBlank() ? environment.getProperty(name) : value;
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
