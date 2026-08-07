package com.rigour.tenant.iam.infrastructure.feishu;

import com.rigour.tenant.iam.application.port.out.FeishuIdentityProvider;
import com.rigour.tenant.iam.application.port.out.FeishuIdentityProviderException;
import com.rigour.tenant.iam.infrastructure.config.FeishuAuthenticationProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 飞书一次性授权码交换适配器；所有飞书令牌只存在当前进程内存。 */
public final class FeishuIdentityProviderClient implements FeishuIdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(FeishuIdentityProviderClient.class);
    private static final URI APP_TOKEN_URI = URI.create(
            "https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal");
    private static final URI USER_TOKEN_URI = URI.create(
            "https://open.feishu.cn/open-apis/authen/v1/access_token");
    private static final long DEFAULT_APP_TOKEN_TTL_SECONDS = Duration.ofHours(2).toSeconds();

    private final RestClient restClient;
    private final FeishuAuthenticationProperties properties;
    private final Object cacheLock = new Object();
    private volatile CachedToken appToken;

    public FeishuIdentityProviderClient(
            RestClient.Builder builder, FeishuAuthenticationProperties properties) {
        this(createRestClient(builder, properties), properties);
    }

    FeishuIdentityProviderClient(RestClient restClient, FeishuAuthenticationProperties properties) {
        this.restClient = Objects.requireNonNull(restClient, "restClient cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public VerifiedIdentity exchange(String code) {
        validateConfiguration();
        String token = appAccessToken();
        Map<String, String> request = new LinkedHashMap<>();
        request.put("grant_type", "authorization_code");
        request.put("code", code);
        Map<?, ?> response = post(USER_TOKEN_URI, request, token,
                FeishuIdentityProviderException.Reason.INVALID_CODE);
        long providerCode = code(response);
        if (providerCode == Long.MIN_VALUE) {
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 200);
        }
        if (providerCode != 0L) {
            log.warn("飞书授权码交换被拒绝 endpoint={} providerCode={}",
                    USER_TOKEN_URI.getPath(), providerCode);
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.INVALID_CODE, providerCode, 200);
        }
        Map<?, ?> data = map(response.get("data"));
        String accessToken = text(data.get("access_token"));
        String tenantKey = text(data.get("tenant_key"));
        String openId = text(data.get("open_id"));
        if (accessToken == null || tenantKey == null || openId == null) {
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 200);
        }
        log.info("飞书授权码交换成功 endpoint={} hasDisplayName={} hasAvatar={}",
                USER_TOKEN_URI.getPath(), text(data.get("name")) != null,
                text(data.get("avatar_url")) != null);
        return new VerifiedIdentity(tenantKey, openId, text(data.get("name")), text(data.get("avatar_url")));
    }

    private String appAccessToken() {
        Instant now = Instant.now();
        CachedToken cached = appToken;
        if (cached != null && cached.validAt(now, properties.getTokenSafetyWindow())) {
            return cached.value();
        }
        synchronized (cacheLock) {
            now = Instant.now();
            cached = appToken;
            if (cached != null && cached.validAt(now, properties.getTokenSafetyWindow())) {
                return cached.value();
            }
            Map<String, String> request = new LinkedHashMap<>();
            request.put("app_id", properties.getAppId());
            request.put("app_secret", properties.getAppSecret());
            Map<?, ?> response = post(APP_TOKEN_URI, request, null,
                    FeishuIdentityProviderException.Reason.CONFIG_INVALID);
            long providerCode = code(response);
            if (providerCode == Long.MIN_VALUE) {
                throw new FeishuIdentityProviderException(
                        FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 200);
            }
            if (providerCode != 0L) {
                log.warn("飞书应用凭证被拒绝 endpoint={} providerCode={}",
                        APP_TOKEN_URI.getPath(), providerCode);
                throw new FeishuIdentityProviderException(
                        FeishuIdentityProviderException.Reason.CONFIG_INVALID, providerCode, 200);
            }
            String value = text(response.get("app_access_token"));
            long expiresIn = positiveLong(response.get("expire"), DEFAULT_APP_TOKEN_TTL_SECONDS);
            if (value == null) {
                throw new FeishuIdentityProviderException(
                        FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 200);
            }
            CachedToken fresh = new CachedToken(value, Instant.now().plusSeconds(expiresIn));
            appToken = fresh;
            return fresh.value();
        }
    }

    private void validateConfiguration() {
        try {
            properties.validate();
        } catch (IllegalStateException exception) {
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.CONFIG_INVALID, 0, 0);
        }
    }

    private Map<?, ?> post(
            URI uri, Object body, String bearerToken,
            FeishuIdentityProviderException.Reason responseFailureReason) {
        long startedAt = System.nanoTime();
        log.info("飞书身份上游请求开始 endpoint={}", uri.getPath());
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (bearerToken != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            Map<?, ?> response = request.body(body).retrieve().body(Map.class);
            if (response == null) {
                throw new FeishuIdentityProviderException(
                        FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 200);
            }
            log.info("飞书身份上游请求完成 endpoint={} httpStatus=200 elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            return response;
        } catch (FeishuIdentityProviderException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("飞书身份上游HTTP失败 endpoint={} httpStatus={} elapsedMs={}",
                    uri.getPath(), exception.getStatusCode().value(), elapsedMillis(startedAt));
            FeishuIdentityProviderException.Reason reason = exception.getStatusCode().is5xxServerError()
                    ? FeishuIdentityProviderException.Reason.UPSTREAM_FAILED
                    : responseFailureReason;
            throw new FeishuIdentityProviderException(
                    reason, 0, exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            log.warn("飞书身份上游网络失败 endpoint={} elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 0);
        } catch (RestClientException exception) {
            log.warn("飞书身份上游客户端失败 endpoint={} elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            throw new FeishuIdentityProviderException(
                    FeishuIdentityProviderException.Reason.UPSTREAM_FAILED, 0, 0);
        }
    }

    private static RestClient createRestClient(
            RestClient.Builder builder, FeishuAuthenticationProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().requestFactory(requestFactory).build();
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static long code(Map<?, ?> response) {
        Object value = response.get("code");
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException ignored) { return Long.MIN_VALUE; }
    }

    private static long positiveLong(Object value, long fallback) {
        if (value instanceof Number number && number.longValue() > 0) return number.longValue();
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean validAt(Instant now, Duration safetyWindow) {
            return expiresAt.isAfter(now.plus(safetyWindow));
        }
    }
}
