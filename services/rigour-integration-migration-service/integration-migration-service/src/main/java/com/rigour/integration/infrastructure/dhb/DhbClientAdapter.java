package com.rigour.integration.infrastructure.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.Connector;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.application.port.out.DhbClient.Customer;
import com.rigour.integration.application.port.out.DhbClient.CustomerQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderDetail;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbClient.Payment;
import com.rigour.integration.application.port.out.DhbClient.PaymentQuery;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.Product;
import com.rigour.integration.application.port.out.DhbClient.ProductBrand;
import com.rigour.integration.application.port.out.DhbClient.ProductCategory;
import com.rigour.integration.application.port.out.DhbClient.ProductImage;
import com.rigour.integration.application.port.out.DhbClient.ProductQuery;
import com.rigour.integration.application.port.out.DhbClient.ProductSku;
import com.rigour.integration.application.port.out.DhbClient.ProductSpecification;
import com.rigour.integration.application.port.out.DhbClient.ProductSpecificationValue;
import com.rigour.integration.application.port.out.DhbClient.ProductTag;
import com.rigour.integration.application.port.out.DhbClient.Supplier;
import com.rigour.integration.application.port.out.DhbClient.Warehouse;
import com.rigour.integration.application.port.out.DhbClient.PurchaseOrder;
import com.rigour.integration.application.port.out.DhbClient.PurchaseOrderLine;
import com.rigour.integration.application.port.out.DhbClient.PurchaseReturn;
import com.rigour.integration.application.port.out.DhbClient.PurchaseReturnLine;
import com.rigour.integration.application.port.out.DhbClient.WarehousingReceipt;
import com.rigour.integration.application.port.out.DhbClient.WarehousingLine;
import com.rigour.integration.application.port.out.DhbClient.PurchaseLink;
import com.rigour.integration.application.port.out.DhbClient.InventoryBalance;
import com.rigour.integration.application.port.out.DhbClient.Receipt;
import com.rigour.integration.application.port.out.DhbClient.ReceiptQuery;
import com.rigour.integration.application.port.out.DhbClient.ReturnDetail;
import com.rigour.integration.application.port.out.DhbClient.ReturnLine;
import com.rigour.integration.application.port.out.DhbClient.ReturnQuery;
import com.rigour.integration.application.port.out.DhbClient.ReturnSummary;
import com.rigour.integration.application.port.out.DhbClient.Shipment;
import com.rigour.integration.application.port.out.DhbClient.ShipmentDetail;
import com.rigour.integration.application.port.out.DhbClient.ShipmentQuery;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.application.port.out.DhbClient.WaitShipment;
import com.rigour.integration.application.port.out.DhbClient.WaitShipmentLine;
import com.rigour.integration.application.port.out.DhbClient.WaitStock;
import com.rigour.integration.application.port.out.DhbClient.WaitShips;
import com.rigour.integration.infrastructure.config.DhbClientProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    private static final int PURCHASE_RETURN_MAX_PAGE_SIZE = 99;

    private static final Logger log = LoggerFactory.getLogger(DhbClientAdapter.class);
    private static final DateTimeFormatter DHB_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DHB_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneOffset DHB_ZONE = ZoneOffset.ofHours(8);
    private static final String TOKEN_FUNCTION = "getTokenValue";

    private final RestClient restClient;
    private final DhbSecretResolver secretResolver;
    private final DhbClientProperties properties;
    private final URI imageBaseUri;
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
        this.imageBaseUri = endpoint(properties.getImageBaseUrl());
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
        putIfPresent(values, "barcode", query.barcode());
        putInstant(values, "updateGe", query.updatedFrom());
        putInstant(values, "updateLe", query.updatedTo());
        ApiEnvelope response = callBusiness(connector, "getGoodsList", values);
        List<Map<String, Object>> rows = rows(response, "getGoodsList");
        List<Product> items = rows.stream().map(DhbClientAdapter::product).toList();
        logPage(connector, "getGoodsList", query.page(), response, items.size());
        log.info("订货宝拉取数据量:{}", items.size());
        return new Page<>(query.page(), response.total(), items);
    }

    @Override
    public DownloadedImage downloadProductImage(Connector connector, String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            DhbClientException exception = new DhbClientException(
                    "DHB_IMAGE_URL_MISSING", "订货宝商品图片地址为空", false, null, null);
            logImageDownloadFailure(connector, null, exception);
            throw exception;
        }
        URI uri;
        try {
            uri = resolveImageUri(imageBaseUri, sourceUrl);
        } catch (DhbClientException exception) {
            logImageDownloadFailure(connector, null, exception);
            throw exception;
        }
        try {
            var response = restClient.get().uri(uri)
                    .header(HttpHeaders.ACCEPT, MediaType.ALL_VALUE)
                    .retrieve().toEntity(byte[].class);
            byte[] content = response.getBody();
            if (content == null || content.length == 0) {
                throw new DhbClientException("DHB_IMAGE_EMPTY", "订货宝商品图片内容为空",
                        false, response.getStatusCode().value(), null);
            }
            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            return new DownloadedImage(content, contentType);
        } catch (DhbClientException exception) {
            logImageDownloadFailure(connector, uri, exception);
            throw exception;
        } catch (RestClientResponseException exception) {
            DhbClientException imageException = new DhbClientException(
                    "DHB_IMAGE_HTTP_ERROR", "订货宝商品图片下载失败",
                    exception.getStatusCode().is5xxServerError(), exception.getStatusCode().value(), null);
            logImageDownloadFailure(connector, uri, imageException);
            throw imageException;
        } catch (ResourceAccessException exception) {
            DhbClientException imageException = new DhbClientException(
                    "DHB_IMAGE_NETWORK_ERROR", "订货宝商品图片网络请求失败", true, null, null);
            logImageDownloadFailure(connector, uri, imageException);
            throw imageException;
        }
    }

    /** 只记录图片源站域名，不记录完整 URL、路径、查询参数或可能包含签名的片段。 */
    private static void logImageDownloadFailure(Connector connector, URI uri,
                                                 DhbClientException exception) {
        log.warn("订货宝商品图片下载失败 tenantId={} connectorId={} imageHost={} httpStatus={} errorType={}",
                connector.tenantId(), connector.connectorId(), redactedImageHost(uri),
                exception.httpStatus(), exception.code());
    }

    private static String redactedImageHost(URI uri) {
        if (uri == null || uri.getHost() == null || uri.getHost().isBlank()) {
            return "unknown";
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<ProductCategory> getProductCategories(Connector connector) {
        ApiEnvelope response = callBusiness(connector, "getSite", new LinkedHashMap<>());
        List<ProductCategory> items = rows(response, "getSite").stream()
                .map(DhbClientAdapter::productCategory).toList();
        logPage(connector, "getSite", PageRequest.first(Math.max(1, Math.min(1000, items.size()))),
                response, items.size());
        return items;
    }

    @Override
    public List<ProductBrand> getProductBrands(Connector connector) {
        ApiEnvelope response = callBusiness(connector, "getBrands", new LinkedHashMap<>());
        List<ProductBrand> items = rows(response, "getBrands").stream()
                .map(DhbClientAdapter::productBrand).toList();
        logPage(connector, "getBrands", PageRequest.first(Math.max(1, Math.min(1000, items.size()))),
                response, items.size());
        return items;
    }

    @Override
    public Page<ProductSpecification> getProductSpecifications(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", page.begin());
        values.put("step", page.step());
        ApiEnvelope response = callBusiness(connector, "getMultiOptionsList", values);
        List<ProductSpecification> items = rows(response, "getMultiOptionsList").stream()
                .map(DhbClientAdapter::productSpecification).toList();
        logPage(connector, "getMultiOptionsList", page, response, items.size());
        return new Page<>(page, response.total(), items);
    }

    @Override
    public Page<ProductTag> getProductTags(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("page", page.begin() / page.step() + 1);
        values.put("page_size", page.step());
        ApiEnvelope response = callBusiness(connector, "getGoodsTag", values);
        List<Map<String, Object>> rawItems = pageRows(response, "getGoodsTag");
        List<ProductTag> items = rawItems.stream()
                .map(DhbClientAdapter::productTag).toList();
        logPage(connector, "getGoodsTag", page, response, items.size(), pageTotal(response, "getGoodsTag"));
        return new Page<>(page, pageTotal(response, "getGoodsTag"), items);
    }

    @Override
    public Page<Supplier> getSuppliers(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", page.begin());
        values.put("step", page.step());
        values.put("data_type", 3);
        ApiEnvelope response = callBusiness(connector, "getSupplierList", values);
        List<Supplier> items = businessRows(response, "getSupplierList").stream()
                .map(DhbClientAdapter::supplier).toList();
        long total = businessTotal(response, "getSupplierList");
        logPage(connector, "getSupplierList", page, response, items.size(), total);
        return new Page<>(page, total, items);
    }

    @Override
    public Page<PurchaseOrder> getPurchaseOrders(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        Map<String, Object> values = pageValues(page);
        values.put("is_erp_api", 2);
        ApiEnvelope response = callBusiness(connector, "getPurchaseList", values);
        List<PurchaseOrder> items = new ArrayList<>();
        for (Map<String, Object> summary : businessRows(response, "getPurchaseList")) {
            String number = first(summary, "purchase_num");
            if (number == null) continue;
            ApiEnvelope detailResponse = callBusiness(connector, "getPurchaseContent",
                    Map.of("purchase_num", number));
            Map<String, Object> detail = businessObject(detailResponse, "getPurchaseContent");
            items.add(purchaseOrder(summary, detail));
        }
        long total = businessTotal(response, "getPurchaseList");
        logPage(connector, "getPurchaseList", page, response, items.size(), total);
        return new Page<>(page, total, items);
    }

    @Override
    public Page<PurchaseReturn> getPurchaseReturns(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        PageRequest effectivePage = page.step() > PURCHASE_RETURN_MAX_PAGE_SIZE
                ? new PageRequest(page.begin(), PURCHASE_RETURN_MAX_PAGE_SIZE) : page;
        ApiEnvelope response = callBusiness(connector, "getPurchaseReturnList", pageValues(effectivePage));
        List<PurchaseReturn> items = new ArrayList<>();
        for (Map<String, Object> summary : businessRows(response, "getPurchaseReturnList")) {
            String number = first(summary, "returns_num");
            if (number == null) continue;
            ApiEnvelope detailResponse = callBusiness(connector, "getPurchaseReturnContent",
                    Map.of("purchase_num", number));
            Map<String, Object> detail = businessObject(detailResponse, "getPurchaseReturnContent");
            items.add(purchaseReturn(summary, detail));
        }
        long total = businessTotal(response, "getPurchaseReturnList");
        logPage(connector, "getPurchaseReturnList", effectivePage, response, items.size(), total);
        return new Page<>(effectivePage, total, items);
    }

    @Override
    public Page<WarehousingReceipt> getWarehousingReceipts(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("page", page.begin() / page.step() + 1);
        values.put("pageSize", page.step());
        values.put("is_erp_api", 2);
        ApiEnvelope response = callBusiness(connector, "getWarehousingList", values);
        List<WarehousingReceipt> items = new ArrayList<>();
        for (Map<String, Object> summary : businessRows(response, "getWarehousingList")) {
            String number = first(summary, "warehousing_num");
            if (number == null) continue;
            ApiEnvelope detailResponse = callBusiness(connector, "getWarehousingContent",
                    Map.of("warehousing_num", number));
            Map<String, Object> detail = businessObject(detailResponse, "getWarehousingContent");
            items.add(warehousingReceipt(summary, detail));
        }
        long total = businessTotal(response, "getWarehousingList");
        logPage(connector, "getWarehousingList", page, response, items.size(), total);
        return new Page<>(page, total, items);
    }

    @Override
    public Page<Warehouse> getWarehouses(Connector connector, PageRequest page) {
        Objects.requireNonNull(page, "page cannot be null");
        ApiEnvelope response = callBusiness(connector, "getStockInfo", pageValues(page));
        List<Warehouse> items = businessRows(response, "getStockInfo").stream()
                .map(DhbClientAdapter::warehouse).toList();
        long total = businessTotal(response, "getStockInfo");
        logPage(connector, "getStockInfo", page, response, items.size(), total);
        return new Page<>(page, total, items);
    }

    @Override
    public List<InventoryBalance> getInventory(Connector connector, List<String> goodsCodes) {
        if (goodsCodes == null || goodsCodes.isEmpty()) return List.of();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("goods_num", goodsCodes.stream().filter(Objects::nonNull)
                .map(String::strip).filter(value -> !value.isEmpty()).distinct().toList());
        ApiEnvelope response = callBusiness(connector, "batchGetStock", values);
        List<InventoryBalance> items = businessRows(response, "batchGetStock").stream()
                .map(DhbClientAdapter::inventoryBalance).toList();
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function=batchGetStock requestedGoods={} returned={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), goodsCodes.size(), items.size(), response.elapsedMs());
        return items;
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

    @Override
    public Page<Shipment> getShipments(Connector connector, ShipmentQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        // 订货宝 getShipsList 使用 1 基页码，本平台出站端口保持 begin/step 偏移语义。
        values.put("page", query.page().begin() / query.page().step() + 1);
        values.put("page_size", query.page().step());
        putIfPresent(values, "status", query.status());
        putIfPresent(values, "is_api", query.isApi());
        putIfPresent(values, "type_id", query.typeId());
        putWindow(values, query.createdWindow(), "create_date_egt", "create_date_elt");
        putWindow(values, query.updatedWindow(), "update_date_egt", "update_date_elt");
        putIfPresent(values, "client_num", query.clientNumber());
        putIfPresent(values, "stock_id", query.stockId());
        putIfPresent(values, "stock_num", query.stockNumber());
        ApiEnvelope response = callBusiness(connector, "getShipsList", values);
        List<Map<String, Object>> rows = pageRows(response, "getShipsList");
        List<Shipment> items = rows.stream().map(DhbClientAdapter::shipment).toList();
        long total = pageTotal(response, "getShipsList");
        logPage(connector, "getShipsList", query.page(), response, items.size(), total);
        return new Page<>(query.page(), total, items);
    }

    @Override
    public ShipmentDetail getShipmentContent(Connector connector, String shipmentNumber) {
        if (shipmentNumber == null || shipmentNumber.isBlank()) {
            throw new IllegalArgumentException("shipmentNumber is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ships_num", shipmentNumber.strip());
        ApiEnvelope response = callBusiness(connector, "getShipsContent", values);
        Map<String, Object> data = object(response.data(), "getShipsContent");
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function=getShipsContent shipmentNumber={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), safeBusinessKey(shipmentNumber),
                response.elapsedMs());
        return new ShipmentDetail(first(data, "ships_num", "ShipsNum", "shipmentNumber"), data);
    }

    @Override
    public WaitShips getWaitShips(Connector connector, String orderNumber) {
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("orders_num", orderNumber.strip());
        ApiEnvelope response = callBusiness(connector, "getWaitShips", values);
        Map<String, Object> data = object(response.data(), "getWaitShips");
        List<WaitShipment> shipped = childRows(data, "shipped", "getWaitShips").stream()
                .map(DhbClientAdapter::waitShipment).toList();
        List<WaitStock> waitStock = childRows(data, "wait_stock", "getWaitShips").stream()
                .map(DhbClientAdapter::waitStock).toList();
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function=getWaitShips orderNumber={} shipped={} waitStock={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), safeBusinessKey(orderNumber),
                shipped.size(), waitStock.size(), response.elapsedMs());
        return new WaitShips(orderNumber.strip(), shipped, waitStock, data);
    }

    @Override
    public Page<ReturnSummary> getReturns(Connector connector, ReturnQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", query.page().begin());
        values.put("step", query.page().step());
        putIfPresent(values, "status", query.status());
        putIfPresent(values, "isApi", query.isApi());
        putWindow(values, query.createdWindow(), "starttime", "endtime");
        putWindow(values, query.updatedWindow(), "updateGe", "updateLe");
        putIfPresent(values, "stock_id", query.stockId());
        putIfPresent(values, "stock_num", query.stockNumber());
        ApiEnvelope response = callBusiness(connector, "getReturnsList", values);
        List<Map<String, Object>> rows = rows(response, "getReturnsList");
        List<ReturnSummary> items = rows.stream().map(DhbClientAdapter::returnSummary).toList();
        logPage(connector, "getReturnsList", query.page(), response, items.size());
        return new Page<>(query.page(), response.total(), items);
    }

    @Override
    public ReturnDetail getReturnContent(Connector connector, String returnNumber) {
        if (returnNumber == null || returnNumber.isBlank()) {
            throw new IllegalArgumentException("returnNumber is required");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("returnsSn", returnNumber.strip());
        ApiEnvelope response = callBusiness(connector, "getReturnsContent", values);
        Map<String, Object> data = object(response.data(), "getReturnsContent");
        ReturnSummary summary = returnSummary(data);
        List<ReturnLine> lines = childRows(data, "body", "getReturnsContent").stream()
                .map(DhbClientAdapter::returnLine).toList();
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function=getReturnsContent returnNumber={} lines={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), safeBusinessKey(returnNumber),
                lines.size(), response.elapsedMs());
        return new ReturnDetail(first(data, "ReturnsSN", returnNumber), summary, lines, data);
    }

    @Override
    public Page<Receipt> getReceipts(Connector connector, ReceiptQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", query.page().begin());
        values.put("step", query.page().step());
        putIfPresent(values, "orderSn", query.orderNumber());
        putWindow(values, query.createdWindow(), "starttime", "endtime");
        putInstant(values, "updateDateGe", query.updatedFrom());
        putIfPresent(values, "status", query.status());
        ApiEnvelope response = callBusiness(connector, "getReceiptsList", values);
        List<Map<String, Object>> rows = rows(response, "getReceiptsList");
        List<Receipt> items = rows.stream().map(DhbClientAdapter::receipt).toList();
        logPage(connector, "getReceiptsList", query.page(), response, items.size());
        return new Page<>(query.page(), response.total(), items);
    }

    @Override
    public Page<Payment> getPayments(Connector connector, PaymentQuery query) {
        Objects.requireNonNull(query, "query cannot be null");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("begin", query.page().begin());
        values.put("step", query.page().step());
        putIfPresent(values, "orderSn", query.orderNumber());
        putWindow(values, query.createdWindow(), "starttime", "endtime");
        putIfPresent(values, "status", query.status());
        ApiEnvelope response = callBusiness(connector, "getPaymentList", values);
        List<Map<String, Object>> rows = rows(response, "getPaymentList");
        List<Payment> items = rows.stream().map(DhbClientAdapter::payment).toList();
        logPage(connector, "getPaymentList", query.page(), response, items.size());
        return new Page<>(query.page(), response.total(), items);
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

    /** 解析订货宝分页对象：{page_size,page,total_page,total,data:[...]}。 */
    private static List<Map<String, Object>> pageRows(ApiEnvelope response, String function) {
        Map<String, Object> data = object(response.data(), function);
        Object value = data.get("data");
        if (value == null) return List.of();
        if (!(value instanceof Iterable<?> iterable)) {
            throw protocolError(function, "订货宝分页回执字段 data 不是数组");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : iterable) rows.add(object(item, function));
        return rows;
    }

    private static long pageTotal(ApiEnvelope response, String function) {
        Map<String, Object> data = object(response.data(), function);
        return number(data, "total", response.total());
    }

    private static Map<String, Object> pageValues(PageRequest page) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("page", page.begin() / page.step() + 1);
        values.put("page_size", page.step());
        return values;
    }

    /** 兼容供应链接口中数组、data.list、data.listsData 三种分页信封。 */
    private static List<Map<String, Object>> businessRows(ApiEnvelope response, String function) {
        Object value = response.data();
        for (int depth = 0; depth < 4; depth++) {
            if (value == null) return List.of();
            if (value instanceof Iterable<?> iterable) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : iterable) result.add(object(item, function));
                return result;
            }
            Map<String, Object> map = object(value, function);
            Object list = firstObject(map, "list", "listsData", "items");
            if (list != null) {
                value = list;
                continue;
            }
            Object data = map.get("data");
            if (data == null) return List.of();
            value = data;
        }
        throw protocolError(function, "订货宝分页回执嵌套层级超限");
    }

    private static long businessTotal(ApiEnvelope response, String function) {
        long fallback = response.total();
        Object value = response.data();
        for (int depth = 0; depth < 4 && value instanceof Map<?, ?>; depth++) {
            Map<String, Object> map = object(value, function);
            long total = number(map, "count", number(map, "total", Long.MIN_VALUE));
            if (total != Long.MIN_VALUE) return total;
            value = map.get("data");
        }
        return fallback >= 0 ? fallback : businessRows(response, function).size();
    }

    /** 获取详情接口的业务对象，自动剥离 code/message/data 外层。 */
    private static Map<String, Object> businessObject(ApiEnvelope response, String function) {
        Object value = response.data();
        for (int depth = 0; depth < 4; depth++) {
            Map<String, Object> map = object(value, function);
            Object data = map.get("data");
            if (data instanceof Map<?, ?> && onlyEnvelopeFields(map)) {
                value = data;
                continue;
            }
            return map;
        }
        throw protocolError(function, "订货宝详情回执嵌套层级超限");
    }

    private static boolean onlyEnvelopeFields(Map<String, Object> map) {
        return map.keySet().stream().allMatch(key -> List.of("code", "message", "data").contains(key));
    }

    private static Object firstObject(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static List<Map<String, Object>> childRows(Map<String, Object> parent, String key, String function) {
        Object value = parent.get(key);
        if (value == null) return List.of();
        if (value instanceof Map<?, ?>) return List.of(object(value, function));
        if (!(value instanceof Iterable<?> iterable)) {
            throw protocolError(function, "订货宝回执字段 " + key + " 不是数组或对象");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : iterable) result.add(object(item, function));
        return result;
    }

    private static WaitShipment waitShipment(Map<String, Object> row) {
        List<WaitShipmentLine> lines = childRows(row, "list", "getWaitShips").stream()
                .map(DhbClientAdapter::waitShipmentLine).toList();
        return new WaitShipment(first(row, "ships_id"), first(row, "ships_num"), first(row, "status"),
                first(row, "logistics_name"), first(row, "logistics_code"), valueText(row, "express_num"),
                instant(row, "ships_date"), instant(row, "ships_time"), first(row, "stock_num"),
                first(row, "stock_name"), lines, row);
    }

    private static WaitShipmentLine waitShipmentLine(Map<String, Object> row) {
        return new WaitShipmentLine(first(row, "ships_list_id"), first(row, "orders_list_id"),
                first(row, "goods_id"), first(row, "options_goods_num"), first(row, "list_type"),
                first(row, "goods_num"), first(row, "goods_name"), first(row, "goods_options"),
                first(row, "base_units"), first(row, "container_units"), decimal(row, "conversion_number"),
                decimal(row, "ships_number"), first(row, "remark"), row);
    }

    private static WaitStock waitStock(Map<String, Object> row) {
        return new WaitStock(first(row, "orders_list_id"), first(row, "goods_id"),
                first(row, "options_goods_num"), first(row, "list_type"), first(row, "goods_num"),
                first(row, "goods_name"), first(row, "goods_options"), first(row, "base_units"),
                first(row, "container_units"), decimal(row, "conversion_number"), first(row, "stock_num"),
                first(row, "stock_name"), decimal(row, "orders_number"), decimal(row, "stock_number"),
                decimal(row, "real_number"), decimal(row, "wait_stock_number"), first(row, "remark"), row);
    }

    private static String valueText(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            for (String candidate : List.of("express_num", "code", "number", "value")) {
                Object nested = map.get(candidate);
                if (nested != null) return String.valueOf(nested);
            }
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static Supplier supplier(Map<String, Object> row) {
        return new Supplier(first(row, "guid", "client_id"), first(row, "guid"),
                first(row, "client_num"), first(row, "client_name"), first(row, "area_name"),
                first(row, "address"), first(row, "contact"), first(row, "mobile"),
                first(row, "phone"), first(row, "email"), first(row, "account_name"),
                first(row, "bank"), first(row, "bank_account"), first(row, "invoice_title"),
                first(row, "taxpayer_number"), first(row, "remark"), instant(row, "update_date"), row);
    }

    private static Warehouse warehouse(Map<String, Object> row) {
        return new Warehouse(first(row, "stock_id", "stock_guid", "stock_num"),
                first(row, "stock_guid"), first(row, "stock_num"), first(row, "stock_name"),
                first(row, "status"), booleanFlag(row, "is_default"), decimal(row, "acreages"),
                first(row, "phone"), first(row, "address"), first(row, "collaborator_id"),
                first(row, "remark"), row);
    }

    private static PurchaseOrder purchaseOrder(Map<String, Object> summary,
                                                Map<String, Object> detail) {
        Map<String, Object> main = nestedObjectOrSelf(detail, "main");
        List<PurchaseOrderLine> lines = childRows(detail, "list", "getPurchaseContent").stream()
                .map(DhbClientAdapter::purchaseOrderLine).toList();
        return new PurchaseOrder(first(main, "purchase_id", "purchase_num"),
                first(main, "purchase_num"), first(main, "supplier_id", "supplier_guid"),
                first(main, "supplier_num"), first(main, "supplier_name"),
                first(main, "stock_id", "stock_guid"), first(main, "stock_num"),
                first(main, "stock_name"), first(main, "staff_id"), first(main, "staff_name"),
                first(main, "status"), first(main, "status_name"), first(main, "pay_status"),
                first(main, "pay_status_name"), instant(main, "delivery_date"),
                instant(main, "create_date"), instant(main, "update_date"),
                decimal(main, "total"), decimal(main, "account_paid"), decimal(main, "goods_count"),
                booleanFlag(main, "is_erp_api"), first(main, "remark"),
                first(main, "internal_comtion"), lines, merged(summary, detail));
    }

    private static PurchaseOrderLine purchaseOrderLine(Map<String, Object> row) {
        Map<String, Object> options = nestedObjectOrSelf(row, "options_info");
        return new PurchaseOrderLine(first(row, "purchase_list_id"), first(row, "goods_id"),
                first(row, "goods_guid"), first(row, "goods_num"), first(row, "goods_name"),
                first(row, "options_id"), first(row, "options_goods_num"),
                first(options, "options_name", "name"), decimal(row, "number"),
                decimal(row, "price"), first(row, "purchase_units"),
                first(row, "purchase_units_name"), decimal(row, "purchase_units_number"),
                decimal(row, "wh_number"), decimal(row, "returns_number"),
                first(row, "remark"), row);
    }

    private static PurchaseReturn purchaseReturn(Map<String, Object> summary,
                                                  Map<String, Object> detail) {
        Map<String, Object> main = nestedObjectOrSelf(detail, "main");
        List<PurchaseReturnLine> lines = childRows(detail, "list", "getPurchaseReturnContent")
                .stream().map(DhbClientAdapter::purchaseReturnLine).toList();
        return new PurchaseReturn(first(main, "returns_id", "returns_num"),
                first(main, "returns_num"), first(main, "supplier_id"),
                first(main, "supplier_num"), first(main, "supplier_name"),
                first(main, "stock_id"), first(main, "stock_num"), first(main, "stock_name"),
                first(main, "staff_id"), first(main, "staff_name"), first(main, "status"),
                first(main, "status_name"), decimal(main, "returns_total"),
                decimal(main, "discount_total"), first(main, "returns_reason"),
                instant(main, "create_date"), instant(main, "returns_send_date"),
                first(main, "internal_comtion"), first(main, "remark"),
                integer(main, "returns_details_count"), first(main, "contact_name", "returns_contact"),
                first(main, "returns_phone"), first(main, "returns_address"),
                stringList(main.get("returns_cityid")), stringList(main.get("returns_city_name")),
                first(main, "source_device"), first(main, "parent_returnsid"),
                first(main, "parent_companyid"), booleanFlag(main, "is_erp_api"),
                lines, merged(summary, detail));
    }

    private static PurchaseReturnLine purchaseReturnLine(Map<String, Object> row) {
        return new PurchaseReturnLine(first(row, "returns_list_id"), first(row, "goods_id"),
                first(row, "goods_num"), first(row, "goods_name"), first(row, "options_id"),
                first(row, "options_goods_num"), first(row, "options_name"),
                decimal(row, "returns_number"), decimal(row, "confirm_number"),
                decimal(row, "returns_price"), decimal(row, "confirm_price"),
                first(row, "returns_units"), first(row, "returns_units_name"),
                decimal(row, "returns_units_number"), decimal(row, "returns_confirm_units_number"),
                decimal(row, "conversion_number"), decimal(row, "amount"), decimal(row, "cost_price"),
                first(row, "purchase_num"), first(row, "category_name"), first(row, "brand_name"),
                first(row, "remark"), row);
    }

    private static WarehousingReceipt warehousingReceipt(Map<String, Object> summary,
                                                          Map<String, Object> detail) {
        Map<String, Object> info = nestedObjectOrSelf(detail, "info");
        List<WarehousingLine> lines = childRows(detail, "list_detail", "getWarehousingContent")
                .stream().map(DhbClientAdapter::warehousingLine).toList();
        List<PurchaseLink> links = childRows(detail, "order_num", "getWarehousingContent")
                .stream().map(row -> new PurchaseLink(first(row, "orderid"), first(row, "ordernum")))
                .filter(link -> link.purchaseOrderNo() != null).toList();
        return new WarehousingReceipt(first(info, "warehousing_id", "warehousing_num"),
                first(info, "warehousing_num"), first(info, "stock_id"), first(info, "stock_name"),
                first(info, "client_id"), first(info, "supplier_name"), first(info, "type_id"),
                first(info, "type_name"), first(info, "status"), first(info, "status_name"),
                first(info, "staff_name"), first(info, "client_id"), first(info, "accounts_id"),
                first(info, "collaborator_id"), first(info, "collaborator_name"),
                first(info, "logistics_id"), valueText(info, "express_num"),
                instant(info, "storage_date"), instant(info, "create_date"), instant(info, "update_date"),
                decimal(info, "freight"), firstDecimal(info, "total_amounts", "total"),
                decimal(info, "cost_total"), booleanFlag(info, "is_api"), first(info, "split_type"),
                first(info, "remark"), lines, links, merged(summary, detail));
    }

    private static WarehousingLine warehousingLine(Map<String, Object> row) {
        return new WarehousingLine(first(row, "warehousing_list_id"), first(row, "goods_id"),
                first(row, "goods_num"), first(row, "goods_name"), first(row, "options_id"),
                first(row, "options_goods_num"), first(row, "options_name"),
                decimal(row, "warehousing_number"), decimal(row, "warehousing_list_units_number"),
                first(row, "warehousing_list_units"), first(row, "warehousing_list_units_name"),
                decimal(row, "warehousing_list_conversion_number"), decimal(row, "cost_price"),
                decimal(row, "warehousing_list_units_cost_price"), decimal(row, "purchase_price"),
                decimal(row, "whole_price"), first(row, "goods_allocation"), first(row, "base_barcode"),
                first(row, "goods_model"), decimal(row, "real_number"), decimal(row, "available_number"),
                first(row, "collaborator_id"), first(row, "collaborator_name"), first(row, "remark"), row);
    }

    private static InventoryBalance inventoryBalance(Map<String, Object> row) {
        return new InventoryBalance(first(row, "goods_guid"), first(row, "goods_num"),
                first(row, "goods_name"), first(row, "stock_guid"), first(row, "stock_num"),
                first(row, "stock_name"), first(row, "multi_first"), first(row, "multi_first_num"),
                first(row, "multi_first_name"), first(row, "multi_second"),
                first(row, "multi_second_num"), first(row, "multi_second_name"),
                decimal(row, "available_number"), decimal(row, "real_number"), row);
    }

    private static Map<String, Object> nestedObjectOrSelf(Map<String, Object> values, String key) {
        Object nested = values.get(key);
        return nested instanceof Map<?, ?> ? object(nested, key) : values;
    }

    private static Map<String, Object> merged(Map<String, Object> first,
                                              Map<String, Object> second) {
        Map<String, Object> result = new LinkedHashMap<>(first);
        result.putAll(second);
        return immutable(result);
    }

    private static List<String> stringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof Iterable<?> iterable) {
            List<String> result = new ArrayList<>();
            for (Object item : iterable) if (item != null) result.add(String.valueOf(item));
            return List.copyOf(result);
        }
        return List.of(String.valueOf(value));
    }

    private static BigDecimal firstDecimal(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            BigDecimal value = decimal(values, key);
            if (value != null) return value;
        }
        return null;
    }

    private static Product product(Map<String, Object> row) {
        String sourceId = first(row, "guid", "coding");
        List<ProductSku> skus = childRows(row, "multi", "getGoodsList").stream()
                .map(DhbClientAdapter::productSku).toList();
        List<ProductImage> images = new ArrayList<>(childRows(row, "goods_imgs", "getGoodsList").stream()
                .map(DhbClientAdapter::productImage).toList());
        String mainImageSource = text(row, "goods_picture");
        boolean mainImageIncluded = images.stream()
                .anyMatch(image -> Objects.equals(mainImageSource, image.sourceUrl()));
        if (mainImageSource != null && !mainImageIncluded) {
            images.addFirst(new ProductImage(sourceId + "/main", sourceId, null, null, 0,
                    mainImageSource));
        }
        return new Product(sourceId, text(row, "coding"), text(row, "name"),
                text(row, "putaway"), text(row, "barcode"), text(row, "units"),
                first(row, "siteID", "SiteID"), first(row, "brandID", "BrandID"),
                text(row, "model"), text(row, "subtitle"), text(row, "keywords"),
                text(row, "goods_allocation"), mainImageSource, text(row, "multi_id"),
                decimal(row, "price1"), decimal(row, "price2"), decimal(row, "price3"),
                decimal(row, "price4"), text(row, "middle_units"), text(row, "bigunits"),
                text(row, "middle_barcode"), text(row, "conversion_barcode"),
                text(row, "big_barcode"), decimal(row, "base2middle_unit_rate"),
                decimal(row, "conversionnumber"), decimal(row, "package"),
                text(row, "minorder"), decimal(row, "librarydown"), decimal(row, "libraryup"),
                decimal(row, "librarysafe"), decimal(row, "middle_unit_whole_price"),
                decimal(row, "big_unit_whole_price"), images, customFields(row), skus, row);
    }

    private static ProductSku productSku(Map<String, Object> row) {
        return new ProductSku(first(row, "options_id", "options_goods_num", "barcode"),
                first(row, "options_goods_num"), first(row, "barcode"),
                first(row, "multiFirst"), first(row, "multiSecond"), first(row, "multiName"),
                text(row, "options_id"), decimal(row, "whole"), decimal(row, "selling"),
                decimal(row, "purchase"), decimal(row, "middle_unit_whole_price"),
                decimal(row, "big_unit_whole_price"), text(row, "options_middle_barcode"),
                text(row, "options_big_barcode"), row);
    }

    private static ProductCategory productCategory(Map<String, Object> row) {
        return new ProductCategory(first(row, "SiteID", "siteID"), first(row, "ERPID", "erpID"),
                first(row, "SiteName", "siteName"), first(row, "SiteNum", "siteNum"),
                first(row, "ParentId", "parentId"), booleanFlag(row, "IsDefault", "isDefault"), row);
    }

    private static ProductBrand productBrand(Map<String, Object> row) {
        return new ProductBrand(first(row, "brandID", "BrandID"), first(row, "erpID", "ERPID"),
                first(row, "brandName", "BrandName"), first(row, "brandNum", "BrandNum"),
                integer(row, "orderNum"), first(row, "brandAbout", "BrandAbout"), row);
    }

    private static ProductSpecification productSpecification(Map<String, Object> row) {
        String sourceId = first(row, "multiID", "MultiID");
        List<ProductSpecificationValue> values = childRows(row, "children", "getMultiOptionsList")
                .stream().map(DhbClientAdapter::productSpecificationValue).toList();
        return new ProductSpecification(sourceId, first(row, "multiNum", "MultiNum"),
                first(row, "name", "Name"), first(row, "parentMultiID", "ParentMultiID"), values, row);
    }

    private static ProductSpecificationValue productSpecificationValue(Map<String, Object> row) {
        return new ProductSpecificationValue(first(row, "multiID", "MultiID"),
                first(row, "multiNum", "MultiNum"), first(row, "name", "Name"),
                first(row, "parentMultiID", "ParentMultiID"), row);
    }

    private static ProductTag productTag(Map<String, Object> row) {
        String sourceId = first(row, "tag_id", "tagID", "tagId", "TagID", "commendID", "commendId",
                "id", "code", "tagCode");
        String code = first(row, "tag_code", "tagCode", "code", "commendCode", "commendID", "tagID", "id");
        String name = first(row, "tag_name", "tagName", "name", "TagName", "commendName", "title");
        return new ProductTag(sourceId == null ? (code == null ? name : code) : sourceId,
                code, name, integer(row, "sort"), integer(row, "relation_count"),
                instant(row, "create_date"), instant(row, "update_date"),
                first(row, "group_id", "groupID"), first(row, "group_name", "groupName"), row);
    }

    private static ProductImage productImage(Map<String, Object> row) {
        String sourceUrl = first(row, "url", "image_url", "file_url", "file_name", "fileName");
        return new ProductImage(first(row, "resource_id", "resourceId"),
                first(row, "goods_id", "goodsId"), first(row, "old_name", "oldName"),
                first(row, "file_name", "fileName"), integer(row, "order_num", "orderNum"),
                sourceUrl);
    }

    private static Map<String, String> customFields(Map<String, Object> row) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index <= 6; index++) {
            String key = "field_" + index;
            String value = text(row, key);
            if (value != null) values.put(key, value);
        }
        return Collections.unmodifiableMap(values);
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

    private static Shipment shipment(Map<String, Object> row) {
        return new Shipment(first(row, "ships_id"), first(row, "ships_num"), first(row, "orders_num"),
                first(row, "status"), first(row, "status_name"), first(row, "type_id"),
                first(row, "type_name"), first(row, "client_num"), first(row, "client_name"),
                first(row, "client_guid"), first(row, "stock_num"), first(row, "stock_name"),
                first(row, "stock_guid"), instant(row, "ships_date"), first(row, "logistics_name"),
                valueText(row, "express_num"), first(row, "remark"), instant(row, "create_date"),
                instant(row, "update_date"), row);
    }

    private static ReturnSummary returnSummary(Map<String, Object> row) {
        return new ReturnSummary(
                first(row, "ReturnsSN"),
                first(row, "ReturnsSN"),
                first(row, "OrdersNum", "OrderSN", "order_sn"),
                first(row, "ReturnsStatus"),
                first(row, "StaffName"),
                decimal(row, "ReturnsTotal"),
                decimal(row, "ReturnsDiscountTotal"),
                instant(row, "ReturnsDate"),
                instant(row, "ReturnsUpdateDate"),
                first(row, "ReturnsReason"),
                first(row, "ClientNum", "client_num"),
                first(row, "ClientGUID", "ClientGuid"),
                first(row, "ReturnsConsignee"),
                first(row, "ReturnsPhone"),
                first(row, "ReturnsAddress"),
                first(row, "ReturnsSendCompany"),
                first(row, "ReturnsSendNo"),
                first(row, "ReturnsType"),
                first(row, "ReturnsSendMode"),
                row);
    }

    private static ReturnLine returnLine(Map<String, Object> row) {
        Map<String, Object> stock = nestedObject(row, "Stock", "getReturnsContent");
        return new ReturnLine(
                first(row, "Guid", "TrueGuid", "Coding", "OptionsGoodsNum"),
                first(row, "TrueGuid", "Guid"),
                first(row, "OptionsGoodsNum"),
                first(row, "Coding"),
                first(row, "Name"),
                decimal(row, "ReturnsNumber"),
                decimal(row, "ReturnsConfirmNumber"),
                decimal(row, "ReturnsPrice"),
                decimal(row, "ReturnsConfirmPrice"),
                first(row, "ReturnsUnitsName", "ReturnsUnits"),
                decimal(row, "ReturnsUnitsNumber"),
                decimal(row, "ReturnsConfirmUnitsNumber"),
                decimal(row, "ConversionNumber"),
                first(row, "ReturnsRemark"),
                first(stock, "StockId"),
                first(stock, "StockName"),
                first(stock, "StockGuid"),
                row);
    }

    private static Receipt receipt(Map<String, Object> row) {
        return new Receipt(
                first(row, "ReceiptsNum"),
                first(row, "ReceiptsNum"),
                first(row, "OrdersNum"),
                first(row, "ClientNum", "client_num"),
                first(row, "ClientGuid", "ClientGUID"),
                first(row, "IncexpId"),
                first(row, "TypeId"),
                decimal(row, "Amount"),
                first(row, "Status"),
                instant(row, "ReceiptsDate"),
                instant(row, "CreateDate"),
                instant(row, "UpdateDate"),
                first(row, "SerialNumber"),
                first(row, "AccountName"),
                first(row, "BankName"),
                first(row, "AccountNumber"),
                first(row, "Remark"),
                row);
    }

    private static Payment payment(Map<String, Object> row) {
        return new Payment(
                first(row, "PaymentNum"),
                first(row, "PaymentNum"),
                first(row, "ReceiptsNum"),
                first(row, "OrdersNum"),
                first(row, "ClientNum", "client_num"),
                first(row, "ClientGuid", "ClientGUID"),
                first(row, "IncexpId"),
                first(row, "TypeId"),
                decimal(row, "Amount"),
                first(row, "Status"),
                instant(row, "ReceiptsDate"),
                instant(row, "CreateDate"),
                first(row, "SerialNumber"),
                first(row, "AccountName"),
                first(row, "BankName"),
                first(row, "AccountNumber"),
                first(row, "Remark"),
                row);
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

    private static void putInstant(Map<String, Object> values, String key, Instant value) {
        if (value != null) {
            values.put(key, DHB_TIME.withZone(DHB_ZONE).format(value));
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

    private static Map<String, Object> nestedObject(Map<String, Object> values, String key,
                                                    String function) {
        Object value = values.get(key);
        return value == null ? Map.of() : object(value, function);
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

    private static Integer integer(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // 兼容订货宝偶尔返回空字符串或非数字排序值。
            }
        }
        return null;
    }

    private static Boolean booleanFlag(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String value = text(values, key);
            if (value == null) {
                continue;
            }
            if ("1".equals(value) || "true".equalsIgnoreCase(value)
                    || "yes".equalsIgnoreCase(value) || "y".equalsIgnoreCase(value)) {
                return Boolean.TRUE;
            }
            if ("0".equals(value) || "false".equalsIgnoreCase(value)
                    || "no".equalsIgnoreCase(value) || "n".equalsIgnoreCase(value)) {
                return Boolean.FALSE;
            }
        }
        return null;
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
                    return LocalDate.parse(text, DHB_DATE).atStartOfDay(DHB_ZONE).toInstant();
                } catch (DateTimeParseException ignoredDate) {
                    try {
                        return Instant.ofEpochSecond(Long.parseLong(text));
                    } catch (NumberFormatException ignoredFinally) {
                        return null;
                    }
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

    private static URI resolveImageUri(URI imageBaseUri, String sourceUrl) {
        try {
            URI source = URI.create(sourceUrl.strip());
            URI resolved = source.isAbsolute() ? source : imageBaseUri.resolve(source);
            if (!"http".equalsIgnoreCase(resolved.getScheme())
                    && !"https".equalsIgnoreCase(resolved.getScheme())) {
                throw new IllegalArgumentException("商品图片地址协议不受支持");
            }
            return resolved;
        } catch (IllegalArgumentException exception) {
            throw new DhbClientException("DHB_IMAGE_URL_INVALID", "订货宝商品图片地址无效",
                    false, null, null);
        }
    }

    private void logPage(Connector connector, String function, PageRequest request,
                         ApiEnvelope response, int itemCount) {
        logPage(connector, function, request, response, itemCount, response.total());
    }

    private void logPage(Connector connector, String function, PageRequest request,
                         ApiEnvelope response, int itemCount, long total) {
        log.info("订货宝接口调用成功 tenantId={} connectorId={} function={} begin={} step={} returned={} total={} elapsedMs={}",
                connector.tenantId(), connector.connectorId(), function, request.begin(), request.step(),
                itemCount, total, response.elapsedMs());
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
