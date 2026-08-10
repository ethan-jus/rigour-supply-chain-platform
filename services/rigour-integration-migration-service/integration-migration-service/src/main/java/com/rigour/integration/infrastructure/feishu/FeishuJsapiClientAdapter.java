package com.rigour.integration.infrastructure.feishu;

import com.rigour.integration.application.port.out.FeishuJsapiClient;
import com.rigour.integration.application.port.out.FeishuJsapiClientException;
import com.rigour.integration.infrastructure.config.FeishuClientProperties;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书 tenant_access_token 与 jsapi_ticket 适配器。
 *
 * <p>tenant_access_token 在进程内缓存并在过期前安全窗口内刷新；jsapi_ticket 每次签名实时获取，
 * 因为飞书会在有效期到达前轮换 ticket，旧值会被校验服务拒绝（表现为“签名已过期”）。
 * 票据不落库、不写日志，也不把飞书上游原始响应透传给浏览器。</p>
 */
public final class FeishuJsapiClientAdapter implements FeishuJsapiClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuJsapiClientAdapter.class);

    private static final URI TENANT_TOKEN_URI = URI.create(
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal");
    private static final URI JSAPI_TICKET_URI = URI.create(
            "https://open.feishu.cn/open-apis/jssdk/ticket/get");
    private final RestClient restClient;
    private final FeishuClientProperties properties;
    private final Object cacheLock = new Object();
    private volatile CachedToken tenantToken;

    public FeishuJsapiClientAdapter(RestClient.Builder builder, FeishuClientProperties properties) {
        this(createRestClient(builder, properties), properties);
    }

    FeishuJsapiClientAdapter(RestClient restClient, FeishuClientProperties properties) {
        this.restClient = Objects.requireNonNull(restClient, "restClient cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public String getJsapiTicket() {
        try {
            properties.validateForSigning();
        } catch (IllegalStateException exception) {
            throw new FeishuJsapiClientException("FEISHU_CONFIG_INVALID", 0);
        }

        Instant now = Instant.now();
        String accessToken = tenantAccessToken(now);
        Map<?, ?> response = post(JSAPI_TICKET_URI, Map.of(), accessToken);
        assertSuccess(response, "FEISHU_JSAPI_TICKET_FAILED");
        Map<?, ?> data = mapValue(response.get("data"));
        String ticket = text(data.get("ticket"));
        if (ticket == null) {
            throw new FeishuJsapiClientException("FEISHU_JSAPI_TICKET_INVALID", 200);
        }
        log.info("飞书 JSSDK ticket 每次签名实时获取，避免轮换后旧 ticket 失效");
        return ticket;
    }

    private String tenantAccessToken(Instant now) {
        CachedToken cached = tenantToken;
        if (cached != null && cached.validAt(now, properties.getTokenSafetyWindow())) {
            log.debug("飞书 tenant_access_token 缓存命中 cache=tenant-access-token");
            return cached.value();
        }
        log.info("飞书 tenant_access_token 缓存未命中，开始刷新 cache=tenant-access-token");
        Map<String, String> request = new LinkedHashMap<>();
        request.put("app_id", properties.getAppId());
        request.put("app_secret", properties.getAppSecret());
        Map<?, ?> response = post(TENANT_TOKEN_URI, request, null);
        assertSuccess(response, "FEISHU_TENANT_TOKEN_FAILED");
        String token = text(response.get("tenant_access_token"));
        long expiresIn = positiveLong(response.get("expire"), 0L);
        if (token == null || expiresIn <= 0) {
            throw new FeishuJsapiClientException("FEISHU_TENANT_TOKEN_INVALID", 200);
        }
        CachedToken fresh = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
        tenantToken = fresh;
        return fresh.value();
    }

    private Map<?, ?> post(URI uri, Object body, String bearerToken) {
        String endpoint = uri.getPath();
        long startedAt = System.nanoTime();
        log.info("飞书上游请求开始 endpoint={}", endpoint);
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON);
            if (bearerToken != null) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            }
            Map<?, ?> response = request.body(body).retrieve().body(Map.class);
            if (response == null) {
                throw new FeishuJsapiClientException("FEISHU_EMPTY_RESPONSE", 200);
            }
            log.info("飞书上游请求完成 endpoint={} httpStatus=200 elapsedMs={}",
                    endpoint, elapsedMillis(startedAt));
            return response;
        } catch (RestClientResponseException exception) {
            log.warn("飞书上游请求失败 endpoint={} httpStatus={} elapsedMs={}",
                    endpoint, exception.getStatusCode().value(), elapsedMillis(startedAt));
            throw new FeishuJsapiClientException("FEISHU_HTTP_" + exception.getStatusCode().value(),
                    exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            log.warn("飞书上游网络请求失败 endpoint={} elapsedMs={}", endpoint, elapsedMillis(startedAt));
            throw new FeishuJsapiClientException("FEISHU_NETWORK_ERROR", 0);
        } catch (RestClientException exception) {
            log.warn("飞书上游客户端请求失败 endpoint={} elapsedMs={}", endpoint, elapsedMillis(startedAt));
            throw new FeishuJsapiClientException("FEISHU_CLIENT_ERROR", 0);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static void assertSuccess(Map<?, ?> response, String failureCode) {
        long code = response.containsKey("code")
                ? longValue(response.get("code"), Long.MIN_VALUE)
                : Long.MIN_VALUE;
        if (code != 0L) {
            throw new FeishuJsapiClientException(failureCode + "_" + code, 200);
        }
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static long positiveLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue() > 0 ? number.longValue() : fallback;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static RestClient createRestClient(RestClient.Builder builder, FeishuClientProperties properties) {
        Objects.requireNonNull(builder, "builder cannot be null");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration connectTimeout = properties.getConnectTimeout();
        Duration readTimeout = properties.getReadTimeout();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return builder.clone().requestFactory(requestFactory).build();
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean validAt(Instant now, Duration safetyWindow) {
            return expiresAt.isAfter(now.plus(safetyWindow));
        }
    }

}
