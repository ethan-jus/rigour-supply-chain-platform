package com.rigour.order.infrastructure.integration;

import com.rigour.integration.api.v1.DhbOrderApi;
import com.rigour.integration.api.v1.model.DhbApiModels;
import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** 通过当前 Integration V1 订单契约查询订货宝，并转换为订单中心本地导入批次。 */
public final class HttpDhbOrderSyncClient implements DhbOrderSyncClient {
    private static final int PAGE_SIZE = 100;
    private static final ZoneId DHB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final ObjectMapper objectMapper;
    private final URI integrationBaseUri;

    public HttpDhbOrderSyncClient(RestClient.Builder builder, TrustedContextSigner signer,
                                  ObjectMapper objectMapper, String integrationBaseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper不能为空");
        this.integrationBaseUri = baseUri(integrationBaseUrl);
    }

    @Override
    public Collected collect(CallerIdentity caller, UUID connectorId, DhbOrderSyncCommand command) {
        Objects.requireNonNull(caller, "caller不能为空");
        Objects.requireNonNull(connectorId, "connectorId不能为空");
        DhbOrderSyncCommand effective = command == null ? new DhbOrderSyncCommand(null, null) : command;
        List<DhbOrderImportBatch.OrderItem> orders = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        long total = 0;

        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            total = page.total();
            for (DhbApiModels.OrderView summary : page.items()) {
                DhbApiModels.OrderContentView detail = effective.includeDetails()
                        ? content(caller, connectorId, summary.orderNumber()) : null;
                orders.add(order(summary, detail));
            }
            completed.add("ORDER");
            if (effective.includeDetails()) completed.add("ORDER_DETAIL");
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
        }

        DhbOrderImportBatch batch = new DhbOrderImportBatch(orders, List.of(), List.of(), List.of());
        return new Collected(UUID.randomUUID(), "ORDER", orders.size(), completed, batch);
    }

    private DhbApiModels.OrderPageView query(CallerIdentity caller, UUID connectorId,
                                             DhbOrderSyncCommand command, int begin) {
        DhbApiModels.OrderQueryCommand request = new DhbApiModels.OrderQueryCommand(
                begin, PAGE_SIZE, null, null, null, command.updatedFrom(), command.updatedTo(),
                null, null, null, null);
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.QUERY_PATH)
                .buildAndExpand(connectorId)
                .encode()
                .toUri();
        return post(caller, uri, request, DhbApiModels.OrderPageView.class);
    }

    private DhbApiModels.OrderContentView content(CallerIdentity caller, UUID connectorId,
                                                   String orderNumber) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.CONTENT_PATH)
                .buildAndExpand(connectorId, orderNumber)
                .encode()
                .toUri();
        return post(caller, uri, new DhbApiModels.OrderContentCommand(false, false),
                DhbApiModels.OrderContentView.class);
    }

    private <T> T post(CallerIdentity caller, URI uri, Object body, Class<T> responseType) {
        Map<String, String> context = contextHeaders(caller);
        TrustedContextSigner.SignedContext signed = signer.sign(
                "POST", uri.getRawPath(), uri.getRawQuery(), context);
        context.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        context.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        context.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        return restClient.post()
                .uri(uri)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> context.forEach(headers::set))
                .header(RequestHeaders.REQUEST_ID, requestId())
                .body(body)
                .retrieve()
                .body(responseType);
    }

    private DhbOrderImportBatch.OrderItem order(DhbApiModels.OrderView summary,
                                                 DhbApiModels.OrderContentView detail) {
        Map<String, Object> list = map(summary.sourceFields());
        Map<String, Object> content = detail == null ? Map.of() : map(detail.sourceFields());
        String rawList = json(list);
        String rawDetail = detail == null ? null : json(content);
        String effectiveRaw = rawDetail == null ? rawList : rawDetail;
        return new DhbOrderImportBatch.OrderItem(
                required(defaultText(first(content, list, "OrderSN", "orders_num", "orderNumber", "sourceId"),
                        summary.orderNumber()), "orderNumber"),
                defaultText(first(content, list, "OrderStatus", "order_status", "status"), summary.status()),
                defaultText(first(content, list, "PayStatus", "pay_status", "paymentStatus"), summary.paymentStatus()),
                first(content, list, "OrderType", "order_type", "TypeName"),
                firstDecimal(content, list, summary.amount(), "OrderTotal", "order_total", "Total", "amount"),
                firstInstant(content, list, summary.createdAt(), "OrderDate", "order_date", "createdAt"),
                firstInstant(content, list, summary.updatedAt(), "OrderUpdateDate", "update_date", "updatedAt"),
                first(content, list, "OrderUpdateDate", "update_date", "updatedAt"),
                first(content, list, "DeliveryDate", "SendDate", "delivery_date"),
                first(content, list, "OrderRemark", "Remark", "remark"),
                defaultText(first(content, list, "ClientNO", "ClientNum", "client_num", "customerNumber"),
                        summary.customerNumber()),
                first(content, list, "ClientGUID", "ClientGuid", "client_guid"),
                first(content, list, "ClientName", "ClientCompanyName", "client_name"),
                first(content, list, "OrderReceiveName", "Consignee", "ReceiverName", "LinkMan"),
                first(content, list, "OrderReceiveCompany", "CompanyName", "ReceiverCompany"),
                first(content, list, "OrderReceivePhone", "Mobile", "Phone", "ReceiverPhone"),
                first(content, list, "OrderReceiveAdd", "OrderReceiveAddTwo", "Address", "ReceiverAddress"),
                first(content, list, "Province", "province"),
                first(content, list, "City", "city"),
                first(content, list, "District", "Area", "district"),
                first(content, list, "OrderApi", "ApiStatus", "apiStatus", "api_status"),
                first(content, list, "ExceptionStatus", "exceptionStatus", "exception_status"),
                first(content, list, "SendType", "send_type"),
                first(content, list, "LastOrderDate", "last_order_at"),
                first(content, list, "SourceDevice", "Device", "device"),
                first(content, list, "IsAdminOrder", "AdminOrder", "admin_order"),
                first(content, list, "SplitType", "splitType", "split_type"),
                first(content, list, "SplitTypeName", "split_type_name"),
                detail == null ? List.of() : orderLines(content),
                detail == null ? List.of() : orderShipments(content),
                rawList, rawDetail, sha256(effectiveRaw), detail != null);
    }

    private static List<DhbOrderImportBatch.OrderLineItem> orderLines(Map<String, Object> content) {
        List<Map<String, Object>> rows = rows(content,
                "OrderProduct", "OrderProducts", "OrderGoods", "Goods", "Products", "list", "body");
        List<DhbOrderImportBatch.OrderLineItem> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            result.add(new DhbOrderImportBatch.OrderLineItem(
                    stableLineId(index, row, "orders_list_id", "OrderListId", "id"),
                    first(row, "Guid", "TrueGuid", "GoodsGuid", "goods_guid"),
                    first(row, "options_goods_num", "OptionsGoodsNum", "skuNo", "SkuNo", "sku_no"),
                    first(row, "OptionsGoodsNo", "options_goods_no"),
                    first(row, "options_barcode", "Barcode", "BarCode", "barcode"),
                    first(row, "Name", "GoodsName", "ProductName"),
                    first(row, "Coding", "GoodsCoding", "ProductCode"),
                    first(row, "multiFirst", "Spec1", "SpecificationFirst"),
                    first(row, "multiSecond", "Spec2", "SpecificationSecond"),
                    first(row, "multiName", "SpecName", "SpecificationName"),
                    decimal(firstObject(row, "ContentPrice", "order_units_price", "Price", "OrderPrice", "UnitPrice")),
                    decimal(firstObject(row, "ContentNumber", "order_units_number", "Number", "OrderNumber", "Quantity")),
                    decimal(firstObject(row, "ActualAmount", "Total", "OrderTotal", "Amount")),
                    first(row, "order_units_name", "base_units_name", "Units", "UnitsName", "Unit", "unit_name"),
                    first(row, "remark", "Remark")));
        }
        return List.copyOf(result);
    }

    private static List<DhbOrderImportBatch.OrderShipmentItem> orderShipments(Map<String, Object> content) {
        return rows(content, "Ships", "ships").stream()
                .map(row -> new DhbOrderImportBatch.OrderShipmentItem(
                        first(row, "ships_num", "ShipsNum"), first(row, "status", "Status"),
                        first(row, "ships_date", "ShipsDate"), first(row, "stock_up_time", "StockUpTime")))
                .toList();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝订单原始数据序列化失败", exception);
        }
    }

    private static Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : new LinkedHashMap<>(value);
    }

    private static List<Map<String, Object>> rows(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            Object value = firstObject(root, key);
            if (value instanceof Iterable<?> iterable) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : iterable) {
                    if (item instanceof Map<?, ?> map) {
                        result.add(stringMap(map));
                    }
                }
                return List.copyOf(result);
            }
        }
        return List.of();
    }

    private static String stableLineId(int index, Map<String, Object> row, String... keys) {
        String explicit = first(row, keys);
        if (explicit != null) return explicit;
        String business = first(row, "Guid", "TrueGuid", "OptionsGoodsNum", "Coding", "Name");
        return (business == null ? "LINE" : business) + "#" + index;
    }

    private static String first(Map<String, Object> preferred, Map<String, Object> fallback, String... keys) {
        for (String key : keys) {
            String value = text(firstObject(preferred, key));
            if (value != null) return value;
            value = text(firstObject(fallback, key));
            if (value != null) return value;
        }
        return null;
    }

    private static String first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = text(firstObject(row, key));
            if (value != null) return value;
        }
        return null;
    }

    private static Object firstObject(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object direct = row.get(key);
            if (direct != null) return direct;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
            }
        }
        return null;
    }

    private static BigDecimal firstDecimal(Map<String, Object> preferred, Map<String, Object> fallback,
                                           BigDecimal typed, String... keys) {
        BigDecimal value = decimal(firstObject(preferred, keys));
        if (value != null) return value;
        value = decimal(firstObject(fallback, keys));
        return value == null ? typed : value;
    }

    private static Instant firstInstant(Map<String, Object> preferred, Map<String, Object> fallback,
                                        Instant typed, String... keys) {
        Instant value = instant(firstObject(preferred, keys));
        if (value != null) return value;
        value = instant(firstObject(fallback, keys));
        return value == null ? typed : value;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).strip();
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private static String defaultText(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static BigDecimal decimal(Object value) {
        String valueText = text(value);
        if (valueText == null) return null;
        try {
            return new BigDecimal(valueText.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            long raw = number.longValue();
            return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        }
        String valueText = text(value);
        if (valueText == null || "0000-00-00 00:00:00".equals(valueText)) return null;
        try {
            long raw = Long.parseLong(valueText);
            return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(valueText);
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(valueText.replace('T', ' '), DATE_TIME)
                            .atZone(DHB_ZONE).toInstant();
                } catch (DateTimeParseException ignoredFinally) {
                    return null;
                }
            }
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException("订货宝回执缺少" + field);
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static Map<String, String> contextHeaders(CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.USER_ID, caller.userId());
        put(headers, RequestHeaders.PLATFORM_USER_ID, caller.platformUserId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        return headers;
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Integration地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Integration地址必须使用http或https");
        }
        return uri;
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
