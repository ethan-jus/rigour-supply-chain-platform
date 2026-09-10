package com.rigour.integration.infrastructure.feishu;

import com.rigour.integration.application.port.out.FeishuBitableClient;
import com.rigour.integration.application.port.out.FeishuBitableClientException;
import com.rigour.integration.infrastructure.config.FeishuClientProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** 飞书 Base/Drive 服务端 API 适配器；只返回业务需要的记录字段和附件字节。 */
public final class FeishuBitableClientAdapter implements FeishuBitableClient {
    private static final Logger log = LoggerFactory.getLogger(FeishuBitableClientAdapter.class);
    private static final String OPEN_API_BASE = "https://open.feishu.cn";
    private static final URI TENANT_TOKEN_URI = URI.create(
            OPEN_API_BASE + "/open-apis/auth/v3/tenant_access_token/internal");
    private static final int TABLE_PAGE_SIZE = 100;
    private static final int FIELD_PAGE_SIZE = 100;
    private static final int RECORD_PAGE_SIZE = 500;
    private static final Pattern JSON_CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern JSON_MSG_PATTERN = Pattern.compile("\"msg\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private final RestClient restClient;
    private final FeishuClientProperties properties;
    private final Object cacheLock = new Object();
    private volatile CachedToken tenantToken;

    public FeishuBitableClientAdapter(RestClient.Builder builder, FeishuClientProperties properties) {
        this(createRestClient(builder, properties), properties);
    }

    FeishuBitableClientAdapter(RestClient restClient, FeishuClientProperties properties) {
        this.restClient = Objects.requireNonNull(restClient, "restClient cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
    }

    @Override
    public List<BitableTable> tables(String appToken) {
        properties.validateForServerApi();
        String token = tenantAccessToken(Instant.now());
        List<BitableTable> result = new ArrayList<>();
        String pageToken = null;
        do {
            Map<?, ?> response = get(tableUri(appToken, pageToken), token);
            assertSuccess(response, "FEISHU_BITABLE_TABLES_FAILED");
            Map<?, ?> data = mapValue(response.get("data"));
            for (Object item : listValue(data.get("items"))) {
                Map<?, ?> table = mapValue(item);
                String tableId = text(table.get("table_id"));
                String name = text(table.get("name"));
                if (StringUtils.hasText(tableId) && StringUtils.hasText(name)) {
                    result.add(new BitableTable(tableId, name));
                }
            }
            pageToken = Boolean.TRUE.equals(booleanValue(data.get("has_more")))
                    ? text(data.get("page_token"))
                    : null;
        } while (StringUtils.hasText(pageToken));
        return List.copyOf(result);
    }

    @Override
    public List<BitableField> fields(String appToken, String tableId) {
        properties.validateForServerApi();
        String token = tenantAccessToken(Instant.now());
        List<BitableField> result = new ArrayList<>();
        String pageToken = null;
        do {
            Map<?, ?> response = get(fieldUri(appToken, tableId, pageToken), token);
            assertSuccess(response, "FEISHU_BITABLE_FIELDS_FAILED");
            Map<?, ?> data = mapValue(response.get("data"));
            for (Object item : listValue(data.get("items"))) {
                Map<?, ?> field = mapValue(item);
                String fieldId = text(field.get("field_id"));
                String name = firstNonBlank(text(field.get("field_name")), text(field.get("name")));
                if (StringUtils.hasText(fieldId) && StringUtils.hasText(name)) {
                    result.add(new BitableField(fieldId, name));
                }
            }
            pageToken = Boolean.TRUE.equals(booleanValue(data.get("has_more")))
                    ? text(data.get("page_token"))
                    : null;
        } while (StringUtils.hasText(pageToken));
        return List.copyOf(result);
    }

    @Override
    public List<BitableRecord> records(String appToken, String tableId, String viewId) {
        properties.validateForServerApi();
        String token = tenantAccessToken(Instant.now());
        List<BitableRecord> result = new ArrayList<>();
        String pageToken = null;
        do {
            Map<String, Object> body = new LinkedHashMap<>();
            if (StringUtils.hasText(viewId)) body.put("view_id", viewId);
            body.put("automatic_fields", true);
            Map<?, ?> response = post(recordSearchUri(appToken, tableId, pageToken), body, token);
            assertSuccess(response, "FEISHU_BITABLE_RECORDS_FAILED");
            Map<?, ?> data = mapValue(response.get("data"));
            for (Object item : listValue(data.get("items"))) {
                Map<?, ?> record = mapValue(item);
                String recordId = text(record.get("record_id"));
                Map<String, Object> fields = objectMap(record.get("fields"));
                if (StringUtils.hasText(recordId)) {
                    result.add(new BitableRecord(recordId, fields));
                }
            }
            pageToken = Boolean.TRUE.equals(booleanValue(data.get("has_more")))
                    ? text(data.get("page_token"))
                    : null;
        } while (StringUtils.hasText(pageToken));
        return List.copyOf(result);
    }

    @Override
    public DownloadedAttachment downloadAttachment(String fileToken, String fallbackFileName,
                                                   String tableId, String recordId, String fieldId) {
        properties.validateForServerApi();
        if (!StringUtils.hasText(fileToken)) {
            throw new FeishuBitableClientException("FEISHU_ATTACHMENT_TOKEN_MISSING",
                    "飞书附件 file_token 为空", false);
        }
        String token = tenantAccessToken(Instant.now());
        URI uri = downloadUri(fileToken, tableId, recordId, fieldId);
        long startedAt = System.nanoTime();
        log.debug("飞书附件下载开始 endpoint={} fileTokenHash={}", uri.getPath(), tokenHash(fileToken));
        try {
            var response = restClient.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.ACCEPT, MediaType.ALL_VALUE)
                    .retrieve()
                    .toEntity(byte[].class);
            byte[] content = response.getBody();
            if (content == null || content.length == 0) {
                throw new FeishuBitableClientException("FEISHU_ATTACHMENT_EMPTY",
                        "飞书附件内容为空", false);
            }
            String fileName = firstNonBlank(response.getHeaders().getContentDisposition().getFilename(),
                    fallbackFileName, fileToken);
            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            log.debug("飞书附件下载完成 endpoint={} fileTokenHash={} bytes={} elapsedMs={}",
                    uri.getPath(), tokenHash(fileToken), content.length, elapsedMillis(startedAt));
            return new DownloadedAttachment(fileToken, fileName, contentType, content);
        } catch (FeishuBitableClientException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("飞书附件下载失败 endpoint={} httpStatus={} fileTokenHash={} elapsedMs={}",
                    uri.getPath(), exception.getStatusCode().value(), tokenHash(fileToken),
                    elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_ATTACHMENT_HTTP_" + exception.getStatusCode().value(),
                    feishuHttpFailureMessage(exception, "飞书附件下载失败"),
                    exception.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException exception) {
            log.warn("飞书附件下载网络异常 endpoint={} fileTokenHash={} elapsedMs={}",
                    uri.getPath(), tokenHash(fileToken), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_ATTACHMENT_NETWORK_ERROR",
                    "飞书附件下载网络异常", true);
        } catch (RestClientException exception) {
            log.warn("飞书附件下载客户端异常 endpoint={} fileTokenHash={} elapsedMs={}",
                    uri.getPath(), tokenHash(fileToken), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_ATTACHMENT_CLIENT_ERROR",
                    "飞书附件下载客户端异常", false);
        }
    }

    private String tenantAccessToken(Instant now) {
        CachedToken cached = tenantToken;
        if (cached != null && cached.validAt(now, properties.getTokenSafetyWindow())) return cached.value();
        synchronized (cacheLock) {
            cached = tenantToken;
            if (cached != null && cached.validAt(now, properties.getTokenSafetyWindow())) return cached.value();
            Map<String, String> request = new LinkedHashMap<>();
            request.put("app_id", properties.getAppId());
            request.put("app_secret", properties.getAppSecret());
            Map<?, ?> response = post(TENANT_TOKEN_URI, request, null);
            assertSuccess(response, "FEISHU_TENANT_TOKEN_FAILED");
            String token = text(response.get("tenant_access_token"));
            long expiresIn = positiveLong(response.get("expire"), 0L);
            if (!StringUtils.hasText(token) || expiresIn <= 0) {
                throw new FeishuBitableClientException("FEISHU_TENANT_TOKEN_INVALID",
                        "飞书 tenant_access_token 无效", false);
            }
            tenantToken = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
            return token;
        }
    }

    private Map<?, ?> get(URI uri, String bearerToken) {
        long startedAt = System.nanoTime();
        log.info("飞书 Base 请求开始 endpoint={}", uri.getPath());
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(uri)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            if (bearerToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            Map<?, ?> response = request.retrieve().body(Map.class);
            if (response == null) {
                throw new FeishuBitableClientException("FEISHU_EMPTY_RESPONSE",
                        "飞书接口返回为空", true);
            }
            log.info("飞书 Base 请求完成 endpoint={} httpStatus=200 elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            return response;
        } catch (FeishuBitableClientException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("飞书 Base 请求失败 endpoint={} httpStatus={} elapsedMs={}",
                    uri.getPath(), exception.getStatusCode().value(), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_HTTP_" + exception.getStatusCode().value(),
                    feishuHttpFailureMessage(exception, "飞书 Base 请求失败"),
                    exception.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException exception) {
            log.warn("飞书 Base 网络请求失败 endpoint={} elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_NETWORK_ERROR",
                    "飞书 Base 网络请求失败", true);
        } catch (RestClientException exception) {
            log.warn("飞书 Base 客户端请求失败 endpoint={} elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_CLIENT_ERROR",
                    "飞书 Base 客户端请求失败", false);
        }
    }

    private Map<?, ?> post(URI uri, Object body, String bearerToken) {
        long startedAt = System.nanoTime();
        log.info("飞书 Base 请求开始 endpoint={}", uri.getPath());
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(uri)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON);
            if (bearerToken != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
            Map<?, ?> response = request.body(body).retrieve().body(Map.class);
            if (response == null) {
                throw new FeishuBitableClientException("FEISHU_EMPTY_RESPONSE",
                        "飞书接口返回为空", true);
            }
            log.info("飞书 Base 请求完成 endpoint={} httpStatus=200 elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            return response;
        } catch (FeishuBitableClientException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            log.warn("飞书 Base 请求失败 endpoint={} httpStatus={} elapsedMs={}",
                    uri.getPath(), exception.getStatusCode().value(), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_HTTP_" + exception.getStatusCode().value(),
                    feishuHttpFailureMessage(exception, "飞书 Base 请求失败"),
                    exception.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException exception) {
            log.warn("飞书 Base 网络请求失败 endpoint={} elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_NETWORK_ERROR",
                    "飞书 Base 网络请求失败", true);
        } catch (RestClientException exception) {
            log.warn("飞书 Base 客户端请求失败 endpoint={} elapsedMs={}",
                    uri.getPath(), elapsedMillis(startedAt));
            throw new FeishuBitableClientException("FEISHU_CLIENT_ERROR",
                    "飞书 Base 客户端请求失败", false);
        }
    }

    private static URI tableUri(String appToken, String pageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(OPEN_API_BASE)
                .pathSegment("open-apis", "bitable", "v1", "apps", appToken, "tables")
                .queryParam("page_size", TABLE_PAGE_SIZE);
        if (StringUtils.hasText(pageToken)) builder.queryParam("page_token", pageToken);
        return builder.build().toUri();
    }

    private static URI fieldUri(String appToken, String tableId, String pageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(OPEN_API_BASE)
                .pathSegment("open-apis", "bitable", "v1", "apps", appToken,
                        "tables", tableId, "fields")
                .queryParam("page_size", FIELD_PAGE_SIZE);
        if (StringUtils.hasText(pageToken)) builder.queryParam("page_token", pageToken);
        return builder.build().toUri();
    }

    private static URI recordSearchUri(String appToken, String tableId, String pageToken) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(OPEN_API_BASE)
                .pathSegment("open-apis", "bitable", "v1", "apps", appToken,
                        "tables", tableId, "records", "search")
                .queryParam("page_size", RECORD_PAGE_SIZE)
                .queryParam("user_id_type", "open_id");
        if (StringUtils.hasText(pageToken)) builder.queryParam("page_token", pageToken);
        return builder.build().toUri();
    }

    private static URI downloadUri(String fileToken, String tableId, String recordId, String fieldId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(OPEN_API_BASE)
                .pathSegment("open-apis", "drive", "v1", "medias", fileToken, "download");
        String extra = bitableExtra(fileToken, tableId, recordId, fieldId);
        if (StringUtils.hasText(extra)) {
            builder.queryParam("extra", UriUtils.encodeQueryParam(extra, StandardCharsets.UTF_8));
            return builder.build(true).toUri();
        }
        return builder.build().toUri();
    }

    private static String bitableExtra(String fileToken, String tableId, String recordId, String fieldId) {
        if (!StringUtils.hasText(tableId) || !StringUtils.hasText(recordId)
                || !StringUtils.hasText(fieldId) || !StringUtils.hasText(fileToken)) {
            return null;
        }
        return "{\"bitablePerm\":{\"tableId\":\"" + jsonEscape(tableId)
                + "\",\"attachments\":{\"" + jsonEscape(fieldId) + "\":{\""
                + jsonEscape(recordId) + "\":[\"" + jsonEscape(fileToken) + "\"]}}}}";
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
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

    private static void assertSuccess(Map<?, ?> response, String failureCode) {
        long code = response.containsKey("code")
                ? longValue(response.get("code"), Long.MIN_VALUE)
                : Long.MIN_VALUE;
        if (code != 0L) {
            throw new FeishuBitableClientException(failureCode + "_" + code,
                    feishuFailureMessage(response, "飞书接口返回失败"), false);
        }
    }

    private static String feishuFailureMessage(Map<?, ?> response, String fallback) {
        String message = firstNonBlank(text(response.get("msg")), text(response.get("message")),
                text(response.get("error")), fallback);
        long code = response.containsKey("code")
                ? longValue(response.get("code"), Long.MIN_VALUE)
                : Long.MIN_VALUE;
        if (code == Long.MIN_VALUE || code == 0L) return text(message, 500);
        return text(message + "（飞书code=" + code + "）", 500);
    }

    private static String feishuHttpFailureMessage(RestClientResponseException exception, String fallback) {
        String body = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
        String message = extractJsonMessage(body);
        String code = extractJsonCode(body);
        String detail = firstNonBlank(message, bodyPreview(body));
        if (!StringUtils.hasText(detail)) return fallback;
        String result = fallback + "：" + detail;
        if (StringUtils.hasText(code)) result += "（飞书code=" + code + "）";
        return text(result, 500);
    }

    private static String extractJsonMessage(String body) {
        if (!StringUtils.hasText(body)) return null;
        Matcher matcher = JSON_MSG_PATTERN.matcher(body);
        return matcher.find() ? unescapeJsonString(matcher.group(1)) : null;
    }

    private static String extractJsonCode(String body) {
        if (!StringUtils.hasText(body)) return null;
        Matcher matcher = JSON_CODE_PATTERN.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String bodyPreview(String body) {
        String value = text(body);
        if (value == null) return null;
        value = value.replace('\n', ' ').replace('\r', ' ').strip();
        return text(value, 300);
    }

    private static String unescapeJsonString(String value) {
        if (value == null || value.indexOf('\\') < 0) return value;
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\' || index == value.length() - 1) {
                builder.append(character);
                continue;
            }
            char next = value.charAt(++index);
            switch (next) {
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                case 'b' -> builder.append('\b');
                case 'f' -> builder.append('\f');
                case 'n' -> builder.append('\n');
                case 'r' -> builder.append('\r');
                case 't' -> builder.append('\t');
                default -> builder.append(next);
            }
        }
        return builder.toString();
    }

    private static Map<?, ?> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(result);
    }

    private static Boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return Boolean.FALSE;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String text(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.strip();
        }
        return null;
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
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String tokenHash(String value) {
        return Integer.toHexString(Objects.hashCode(value));
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean validAt(Instant now, Duration safetyWindow) {
            return expiresAt.isAfter(now.plus(safetyWindow));
        }
    }
}
