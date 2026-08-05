package com.rigour.integration.infrastructure.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.application.port.out.DhbClient.Customer;
import com.rigour.integration.application.port.out.DhbClient.CustomerQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderDetail;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.Product;
import com.rigour.integration.application.port.out.DhbClient.ProductQuery;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.infrastructure.config.DhbClientProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 订货宝 ERP API 的 HTTP 适配器。
 *
 * <p>订货宝文档不是 REST/OpenAPI，而是固定 URL 上的 {@code f/v} JSON 信封。本类集中
 * 处理令牌缓存、Secret 引用、超时、仅对传输临时错误重试、进程内限流、偏移分页和字段
 * 映射。业务服务不得复制这些规则，也不得直接访问订货宝。</p>
 */
public final class DhbClientAdapter implements DhbClient {

    private static final Logger log = LoggerFactory.getLogger(DhbClientAdapter.class);
    private static final DateTimeFormatter DHB_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneOffset DHB_ZONE = ZoneOffset.ofHours(8);
    private static final String TOKEN_FUNCTION = "getTokenValue";

    private final RestClient restClient;
    private final DhbSecretResolver secretResolver;
    private final DhbClientProperties properties;
    private final ConcurrentMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> tokenLocks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PermitBucket> rateLimiters = new ConcurrentHashMap<>();

    public DhbClientAdapter(RestClient.Builder builder,
                                   DhbSecretResolver secretResolver,
                                   DhbClientProperties properties) {
        this(createRestClient(builder, properties), secretResolver, properties);
    }

    /** 测试/嵌入式调用使用已构造的 RestClient，以便注入可重复的 HTTP 契约服务器。 */
    DhbClientAdapter(RestClient restClient,
                            DhbSecretResolver secretResolver,
                            DhbClientProperties properties) {
        this.secretResolver = Objects.requireNonNull(secretResolver, "secretResolver cannot be null");
        this.properties = Objects.requireNonNull(properties, "properties cannot be null");
        properties.validate();
        this.restClient = Objects.requireNonNull(restClient, "restClient cannot be null");
    }

    private static RestClient createRestClient(RestClient.Builder builder,
                                               DhbClientProperties properties) {
        Objects.requireNonNull(builder, "builder cannot be null");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeout());
        requestFactory.setReadTimeout(properties.getReadTimeout());
        return builder.clone().requestFactory(requestFactory).build();
    }

    @Override
    public ConnectionTestResult testConnection(Connector connector) {
        try {
            CachedToken token = tokenFor(connector);
            return ConnectionTestResult.success(token.expiresAt());
        } catch (DhbClientException exception) {
            log.warn("订货宝连接测试失败 tenantId={} connectorId={} code={}",
                    connector.tenantId(), connector.connectorId(), exception.code());
            return ConnectionTestResult.failure(exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("订货宝连接测试失败 tenantId={} connectorId={} code=DHB_CLIENT_CONFIG_INVALID",
                    connector.tenantId(), connector.connectorId());
            return ConnectionTestResult.failure(
                    "DHB_CLIENT_CONFIG_INVALID", "订货宝连接配置无效，请检查基础 URL 和 Secret 引用");
        }
    }

    @Override
    public Page<Product> getProducts(Connector connector, ProductQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", query.page().begin());
        values.put("step", query.page().step());
        putIfPresent(values, "status", query.status());
        putIfPresent(values, "putaway", query.putaway());
        putIfPresent(values, "goodsCode", query.goodsCode());
        putWindow(values, query.updatedWindow(), "updateGe", "updateLe");
        putIfPresent(values, "barcode", query.barcode());
        ApiEnvelope response = callBusiness(connector, "getGoodsList", values);
        List<Map<String, Object>> rows = rows(response, "getGoodsList");
        List<Product> items = rows.stream().map(DhbClientAdapter::product).toList();
        logPage(connector, "getGoodsList", query.page(), response, items.size());
        return new Page<>(query.page(), response.total(), items);
    }

    @Override
    public Page<Customer> getCustomers(Connector connector, CustomerQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", query.page().begin());
        values.put("step", query.page().step());
        putIfPresent(values, "status", query.status());
        putIfPresent(values, "data_type", query.dataType());
        putIfPresent(values, "time_type", query.timeType());
        putWindow(values, query.window(), "start_time", "end_time");
        putIfPresent(values, "client_no", query.clientNo());
        putIfPresent(values, "client_area", query.clientArea());
        putIfPresent(values, "type_id", query.typeId());
        ApiEnvelope response = callBusiness(connector, "getDealersList", values);
        List<Map<String, Object>> rows = rows(response, "getDealersList");
        List<Customer> items = rows.stream().map(DhbClientAdapter::customer).toList();
        logPage(connector, "getDealersList", query.page(), response, items.size());
        return new Page<>(query.page(), response.total(), items);
    }

    @Override
    public Page<OrderSummary> getOrders(Connector connector, OrderQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", query.page().begin());
        values.put("step", query.page().step());
        putIfPresent(values, "order_status_val", query.orderStatus());
        putWindow(values, query.createdWindow(), "starttime", "endtime");
        putWindow(values, query.updatedWindow(), "updateGe", "updateLe");
        putIfPresent(values, "exceptionStatus", query.exceptionStatus());
        putIfPresent(values, "apiStatus", query.apiStatus());
        putIfPresent(values, "payStatus", query.payStatus());
        putIfPresent(values, "splitType", query.splitType());
        ApiEnvelope response = callBusiness(connector, "getOrderList", values);
        List<Map<String, Object>> rows = rows(response, "getOrderList");
        List<OrderSummary> items = rows.stream().map(DhbClientAdapter::order).toList();
        logPage(connector, "getOrderList", query.page(), response, items.size());
        return new Page<>(query.page(), response.total(), items);
    }

    @Override
    public OrderDetail getOrderContent(Connector connector, String orderNumber,
                                       boolean autoMarkDownloaded, boolean autoAudit) {
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("orderSn", orderNumber.strip());
        values.put("isAutoSign", autoMarkDownloaded ? 1 : 2);
        values.put("isAutoAudit", autoAudit ? 1 : 2);
        ApiEnvelope response = callBusiness(connector, "getOrderContent", values);
        Map<String, Object> data = object(response.data(), "getOrderContent");
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function=getOrderContent orderNumber={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), safeBusinessKey(orderNumber), response.elapsedMs());
        return new OrderDetail(first(data, "OrderSN", orderNumber),
                text(data, "OrderStatus"), decimal(data, "OrderTotal"), data);
    }

    private CachedToken tokenFor(Connector connector) {
        String key = connectorKey(connector);
        CachedToken cached = tokenCache.get(key);
        if (cached != null && cached.validAt(Instant.now(), properties.getTokenSafetyWindow())) {
            return cached;
        }
        synchronized (tokenLocks.computeIfAbsent(key, ignored -> new Object())) {
            cached = tokenCache.get(key);
            if (cached != null && cached.validAt(Instant.now(), properties.getTokenSafetyWindow())) {
                return cached;
            }
            if (connector.secretRef() == null || connector.secretRef().isBlank()) {
                throw new DhbClientException(
                        "DHB_SECRET_NOT_CONFIGURED", "订货宝 Secret 尚未配置引用", false, null, null);
            }
            DhbSecretResolver.Credentials credentials = secretResolver.resolve(connector.secretRef());
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("SerialNumber", credentials.serialNumber());
            values.put("Password", credentials.password());
            ApiEnvelope response = postEnvelope(connector, TOKEN_FUNCTION, values);
            Map<String, Object> data = object(response.data(), TOKEN_FUNCTION);
            String token = text(data, "token");
            long expiresIn = number(data, "expires_in", 0L);
            if (token == null || token.isBlank() || expiresIn <= 0) {
                throw protocolError(TOKEN_FUNCTION, "订货宝认证回执缺少 token 或 expires_in");
            }
            CachedToken fresh = new CachedToken(token, Instant.now().plusSeconds(expiresIn));
            tokenCache.put(key, fresh);
            log.info("订货宝认证成功 tenantId={} connectorId={} expiresInSeconds={}",
                    connector.tenantId(), connector.connectorId(), expiresIn);
            return fresh;
        }
    }

    private ApiEnvelope callBusiness(Connector connector, String function, Map<String, Object> values) {
        String key = connectorKey(connector);
        for (int authAttempt = 0; authAttempt < 2; authAttempt++) {
            CachedToken token = tokenFor(connector);
            Map<String, Object> authenticated = new LinkedHashMap<>();
            authenticated.put("sKey", token.value());
            authenticated.putAll(values);
            try {
                return postEnvelope(connector, function, authenticated);
            } catch (DhbClientException exception) {
                if (!"DHB_AUTH_FAILED".equals(exception.code()) || authAttempt == 1) {
                    throw exception;
                }
                tokenCache.remove(key, token);
                log.info("订货宝 Token 失效，准备重新获取 tenantId={} connectorId={} function={}",
                        connector.tenantId(), connector.connectorId(), function);
            }
        }
        throw new DhbClientException("DHB_AUTH_FAILED", "订货宝认证失败", false, null, null);
    }

    @SuppressWarnings("unchecked")
    private ApiEnvelope postEnvelope(Connector connector, String function,
                                      Map<String, Object> values) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("f", function);
        request.put("v", values);
        return executeWithRetry(connector, function, () -> {
            URI uri = endpoint(connector.baseUrl());
            long started = System.nanoTime();
            Map<String, Object> body = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                throw protocolError(function, "订货宝回执为空");
            }
            return parseEnvelope(body, function, elapsedMillis(started));
        });
    }

    private ApiEnvelope executeWithRetry(Connector connector, String function,
                                          RetryCall<ApiEnvelope> call) {
        int maxAttempts = properties.getMaxAttempts();
        DhbClientException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            acquirePermit(connector);
            try {
                return call.execute();
            } catch (DhbClientException exception) {
                last = exception;
                if (!exception.retryable() || attempt == maxAttempts) {
                    log.warn("订货宝接口调用失败 tenantId={} connectorId={} function={} attempt={} code={}",
                            connector.tenantId(), connector.connectorId(), function, attempt, exception.code());
                    throw exception;
                }
                log.warn("订货宝接口准备重试 tenantId={} connectorId={} function={} attempt={} code={}",
                        connector.tenantId(), connector.connectorId(), function, attempt, exception.code());
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                boolean retryable = status == 429 || status >= 500;
                last = httpError(status, retryable);
                if (!retryable || attempt == maxAttempts) {
                    log.warn("订货宝 HTTP 调用失败 tenantId={} connectorId={} function={} attempt={} httpStatus={}",
                            connector.tenantId(), connector.connectorId(), function, attempt, status);
                    throw last;
                }
                log.warn("订货宝 HTTP 调用准备重试 tenantId={} connectorId={} function={} attempt={} httpStatus={}",
                        connector.tenantId(), connector.connectorId(), function, attempt, status);
            } catch (ResourceAccessException exception) {
                last = new DhbClientException(
                        "DHB_NETWORK_TIMEOUT", "订货宝网络请求超时或不可达", true, null, null);
                if (attempt == maxAttempts) {
                    log.warn("订货宝网络调用失败 tenantId={} connectorId={} function={} attempt={} code={}",
                            connector.tenantId(), connector.connectorId(), function, attempt, last.code());
                    throw last;
                }
                log.warn("订货宝网络调用准备重试 tenantId={} connectorId={} function={} attempt={} code={}",
                        connector.tenantId(), connector.connectorId(), function, attempt, last.code());
            }
            sleepBeforeRetry(attempt);
        }
        throw last == null
                ? new DhbClientException("DHB_CALL_FAILED", "订货宝调用失败", false, null, null)
                : last;
    }

    private void acquirePermit(Connector connector) {
        rateLimiters.computeIfAbsent(connectorKey(connector), ignored -> new PermitBucket(
                properties.getRequestsPerSecond(), properties.getRateLimitBurst())).acquire();
    }

    private void sleepBeforeRetry(int attempt) {
        long baseMillis = properties.getInitialBackoff().toMillis();
        long capped = Math.min(properties.getMaxBackoff().toMillis(), baseMillis * (1L << Math.min(attempt - 1, 10)));
        long jitter = capped <= 0 ? 0 : ThreadLocalRandom.current().nextLong(Math.max(1, capped / 4 + 1));
        try {
            Thread.sleep(Math.min(properties.getMaxBackoff().toMillis(), capped + jitter));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DhbClientException(
                    "DHB_RETRY_INTERRUPTED", "订货宝重试被中断", false, null, null);
        }
    }

    private static ApiEnvelope parseEnvelope(Map<String, Object> body, String function, long elapsedMs) {
        int status = (int) number(body, "rStatus", Integer.MIN_VALUE);
        if (status == Integer.MIN_VALUE) {
            throw protocolError(function, "订货宝回执缺少 rStatus");
        }
        String message = redact(text(body, "message"));
        if (status != 100) {
            String code = isAuthFailure(status, message)
                    ? "DHB_AUTH_FAILED" : "DHB_PROVIDER_ERROR";
            throw new DhbClientException(code,
                    message == null || message.isBlank() ? "订货宝返回业务失败" : message,
                    false, null, status);
        }
        return new ApiEnvelope(body.get("rData"), number(body, "rTotal", -1L), message, elapsedMs);
    }

    private static boolean isAuthFailure(int status, String message) {
        String value = message == null ? "" : message.toLowerCase();
        return status == 203 || status == 401 || status == 403 || value.contains("token")
                || value.contains("令牌") || value.contains("密码") || value.contains("账号");
    }

    private static List<Map<String, Object>> rows(ApiEnvelope response, String function) {
        if (response.data() == null) {
            return List.of();
        }
        if (!(response.data() instanceof Iterable<?> iterable)) {
            throw protocolError(function, "订货宝回执的 rData 不是数组");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : iterable) {
            rows.add(object(item, function));
        }
        return rows;
    }

    private static Product product(Map<String, Object> row) {
        return new Product(first(row, "guid", "coding"), text(row, "coding"), text(row, "name"),
                text(row, "putaway"), row);
    }

    private static Customer customer(Map<String, Object> row) {
        return new Customer(first(row, "clientGUID", "clientNO"), text(row, "clientAccount"),
                text(row, "clientNO"), text(row, "clientCompanyName"), text(row, "clientStatus"),
                instant(row, "createDate"), instant(row, "updateDate"), row);
    }

    private static OrderSummary order(Map<String, Object> row) {
        return new OrderSummary(first(row, "OrderSN"), text(row, "OrderSN"), text(row, "OrderStatus"),
                decimal(row, "OrderTotal"), instant(row, "OrderDate"), instant(row, "OrderUpdateDate"),
                first(row, "ClientNO"), text(row, "PayStatus"), row);
    }

    private static void putWindow(Map<String, Object> values, TimeWindow window,
                                  String fromKey, String toKey) {
        if (window == null) {
            return;
        }
        values.put(fromKey, DHB_TIME.withZone(DHB_ZONE).format(window.from()));
        values.put(toKey, DHB_TIME.withZone(DHB_ZONE).format(window.to()));
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value);
        }
    }

    private static Map<String, Object> object(Object value, String function) {
        if (!(value instanceof Map<?, ?> source)) {
            throw protocolError(function, "订货宝回执对象格式无效");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return immutable(result);
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String first(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = text(values, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        String value = text(values, key);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static long number(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static Instant instant(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        String text = String.valueOf(value).strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text, DHB_TIME).toInstant(DHB_ZONE);
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return Instant.ofEpochSecond(Long.parseLong(text));
                } catch (NumberFormatException ignoredFinally) {
                    return null;
                }
            }
        }
    }

    private static URI endpoint(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.strip());
            if ((!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new DhbClientException(
                    "DHB_BASE_URL_INVALID", "订货宝基础 URL 无效", false, null, null);
        }
    }

    private static DhbClientException protocolError(String function, String message) {
        return new DhbClientException(
                "DHB_RESPONSE_INVALID", "订货宝接口 " + function + " 回执格式无效：" + redact(message),
                false, null, null);
    }

    private static DhbClientException httpError(int status, boolean retryable) {
        return new DhbClientException(
                status == 429 ? "DHB_RATE_LIMITED"
                        : (status == 401 || status == 403
                        ? "DHB_AUTH_FAILED" : "DHB_HTTP_ERROR"),
                status == 429 ? "订货宝请求被限流"
                        : (status == 401 || status == 403
                        ? "订货宝认证失败" : "订货宝 HTTP 请求失败"),
                retryable, status, null);
    }

    private static String redact(String value) {
        if (value == null) {
            return null;
        }
        String redacted = value.replaceAll(
                "(?i)(password|token|skey|serialnumber|api[-_]?key)\\s*[:=]\\s*[^,;\\s}]+",
                "$1=[REDACTED]");
        return redacted.length() > 256 ? redacted.substring(0, 256) : redacted;
    }

    private static String safeBusinessKey(String value) {
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private void logPage(Connector connector, String function, PageRequest request,
                         ApiEnvelope response, int itemCount) {
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function={} begin={} step={} returned={} total={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), function, request.begin(), request.step(),
                itemCount, response.total(), response.elapsedMs());
    }

    private static String connectorKey(Connector connector) {
        // 连接器更新后，baseUrl 或 Secret 引用可能改变；不能让旧 Token 跨配置复用。
        return connector.tenantId() + ":" + connector.connectorId() + ":"
                + String.valueOf(connector.baseUrl()) + ":" + String.valueOf(connector.secretRef());
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private record ApiEnvelope(Object data, long total, String message, long elapsedMs) { }

    private record CachedToken(String value, Instant expiresAt) {
        boolean validAt(Instant now, Duration safetyWindow) {
            return value != null && !value.isBlank() && expiresAt.isAfter(now.plus(safetyWindow));
        }

        @Override
        public String toString() {
            return "CachedToken[value=[REDACTED], expiresAt=" + expiresAt + "]";
        }
    }

    @FunctionalInterface
    private interface RetryCall<T> {
        T execute();
    }

    private static final class PermitBucket {
        private final long intervalNanos;
        private final int burst;
        private final AtomicLong nextPermitNanos;

        private PermitBucket(int requestsPerSecond, int burst) {
            this.intervalNanos = Math.max(1L, 1_000_000_000L / requestsPerSecond);
            this.burst = burst;
            this.nextPermitNanos = new AtomicLong(
                    System.nanoTime() - intervalNanos * Math.max(0, burst - 1));
        }

        private void acquire() {
            for (;;) {
                long now = System.nanoTime();
                long previous = nextPermitNanos.get();
                long earliestPermit = now - intervalNanos * Math.max(0, burst - 1);
                long permitAt = Math.max(earliestPermit, previous);
                if (nextPermitNanos.compareAndSet(previous, permitAt + intervalNanos)) {
                    long waitNanos = permitAt - now;
                    if (waitNanos > 0) {
                        try {
                            long millis = waitNanos / 1_000_000L;
                            int nanos = (int) (waitNanos % 1_000_000L);
                            Thread.sleep(millis, nanos);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new DhbClientException(
                                    "DHB_RATE_LIMIT_INTERRUPTED", "订货宝限流等待被中断", false, null, null);
                        }
                    }
                    return;
                }
            }
        }
    }
}
