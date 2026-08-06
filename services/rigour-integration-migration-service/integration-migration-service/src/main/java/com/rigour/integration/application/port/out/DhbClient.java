package com.rigour.integration.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 订货宝出站端口。
 *
 * <p>该端口只描述 Integration 需要的供应商能力，供应商的 HTTP、认证、重试、限流和
 * 字段兼容逻辑必须留在 infrastructure。所有请求都带租户和连接器标识，避免把一个
 * 供应商连接误用到另一个租户。</p>
 */
public interface DhbClient {

    ConnectionTestResult testConnection(Connector connector);

    Page<Product> getProducts(Connector connector, ProductQuery query);

    Page<Customer> getCustomers(Connector connector, CustomerQuery query);

    Page<OrderSummary> getOrders(Connector connector, OrderQuery query);

    /** 查询订货宝 getShipsList 出库/发货单列表；分页使用 begin/step 偏移语义。 */
    Page<Shipment> getShipments(Connector connector, ShipmentQuery query);

    /** 查询订货宝 getShipsContent 出库/发货单详情；业务键为 ships_num。 */
    ShipmentDetail getShipmentContent(Connector connector, String shipmentNumber);

    OrderDetail getOrderContent(Connector connector, String orderNumber,
                                boolean autoMarkDownloaded, boolean autoAudit);

    /** 查询指定订货单的getWaitShips物流数据；该接口没有分页，业务键为订单号。 */
    WaitShips getWaitShips(Connector connector, String orderNumber);

    /** 查询订货宝 getReturnsList 退货单列表；分页使用 begin/step 偏移语义。 */
    Page<ReturnSummary> getReturns(Connector connector, ReturnQuery query);

    /** 查询订货宝 getReturnsContent 退货单明细；业务键为 returnsSn。 */
    ReturnDetail getReturnContent(Connector connector, String returnNumber);

    /** 查询订货宝 getReceiptsList 收款单列表；分页使用 begin/step 偏移语义。 */
    Page<Receipt> getReceipts(Connector connector, ReceiptQuery query);

    /** 查询订货宝 getPaymentList 付款单列表；分页使用 begin/step 偏移语义。 */
    Page<Payment> getPayments(Connector connector, PaymentQuery query);

    /** 外部连接配置；secretRef 只能是 Secret 引用，不允许放密码或 API Key。 */
    record Connector(UUID tenantId, UUID connectorId, String baseUrl, String secretRef) {
        public Connector {
            if (tenantId == null || connectorId == null) {
                throw new IllegalArgumentException("tenantId and connectorId are required");
            }
        }
    }

    /** 订货宝的 begin/step 偏移分页。step 的上限由订货宝文档规定为 1000。 */
    record PageRequest(int begin, int step) {
        public PageRequest {
            if (begin < 0) {
                throw new IllegalArgumentException("begin must be >= 0");
            }
            if (step < 1 || step > 1000) {
                throw new IllegalArgumentException("step must be between 1 and 1000");
            }
        }

        public static PageRequest first(int step) {
            return new PageRequest(0, step);
        }
    }

    record Page<T>(PageRequest request, long total, List<T> items) {
        public Page {
            if (request == null || items == null) {
                throw new IllegalArgumentException("request and items are required");
            }
            items = List.copyOf(items);
        }

        public boolean hasNext() {
            return !items.isEmpty() && (total >= 0
                    ? request.begin() + items.size() < total
                    : items.size() == request.step());
        }

        public PageRequest nextRequest() {
            if (!hasNext()) {
                throw new IllegalStateException("page has no next page");
            }
            return new PageRequest(request.begin() + items.size(), request.step());
        }
    }

    /** 创建/更新时间窗口；订货宝接口要求东八区的 YYYY-MM-DD HH:mm:ss 文本。 */
    record TimeWindow(Instant from, Instant to) {
        public TimeWindow {
            if (from == null || to == null || !from.isBefore(to)) {
                throw new IllegalArgumentException("time window must be ordered and non-empty");
            }
        }
    }

    record ProductQuery(PageRequest page, String status, String putaway, String goodsCode,
                        TimeWindow updatedWindow, String barcode) {
        public ProductQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public ProductQuery(PageRequest page, String status, String putaway, String goodsCode) {
            this(page, status, putaway, goodsCode, null, null);
        }

        public static ProductQuery first(int step) {
            return new ProductQuery(PageRequest.first(step), null, null, null, null, null);
        }
    }

    record CustomerQuery(PageRequest page, Integer status, Integer dataType,
                         String timeType, TimeWindow window, String clientNo,
                         Integer clientArea, Integer typeId) {
        public CustomerQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
            if (timeType != null && !timeType.equals("create_date") && !timeType.equals("update_date")) {
                throw new IllegalArgumentException("timeType must be create_date or update_date");
            }
            if (window != null && timeType == null) {
                throw new IllegalArgumentException("timeType is required with a time window");
            }
        }

        public CustomerQuery(PageRequest page, Integer status, Integer dataType,
                             String timeType, TimeWindow window) {
            this(page, status, dataType, timeType, window, null, null, null);
        }

        public static CustomerQuery first(int step, String timeType, TimeWindow window) {
            return new CustomerQuery(PageRequest.first(step), null, null, timeType, window,
                    null, null, null);
        }
    }

    record OrderQuery(PageRequest page, String orderStatus, TimeWindow createdWindow,
                      TimeWindow updatedWindow, String exceptionStatus, String apiStatus,
                      String payStatus, Integer splitType) {
        public OrderQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public OrderQuery(PageRequest page, String orderStatus, TimeWindow createdWindow,
                          TimeWindow updatedWindow, String exceptionStatus, String payStatus) {
            this(page, orderStatus, createdWindow, updatedWindow, exceptionStatus, null,
                    payStatus, null);
        }

        public static OrderQuery first(int step, TimeWindow createdWindow, TimeWindow updatedWindow) {
            return new OrderQuery(PageRequest.first(step), null, createdWindow, updatedWindow,
                    null, null, null, null);
        }
    }

    /** getShipsList 查询参数；时间字段统一使用 UTC Instant，由适配器转换为东八区文本。 */
    record ShipmentQuery(
            /** 本平台零基偏移分页。 */ PageRequest page,
            /** getShipsList.status状态筛选。 */ String status,
            /** getShipsList.is_api下载状态筛选。 */ String isApi,
            /** getShipsList.type_id出库类型筛选。 */ String typeId,
            /** getShipsList.create_date_egt/create_date_elt时间窗口。 */ TimeWindow createdWindow,
            /** getShipsList.update_date_egt/update_date_elt时间窗口。 */ TimeWindow updatedWindow,
            /** getShipsList.client_num客户编号筛选。 */ String clientNumber,
            /** getShipsList.stock_id仓库ID筛选。 */ String stockId,
            /** getShipsList.stock_num仓库编码筛选。 */ String stockNumber) {
        public ShipmentQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public static ShipmentQuery first(int step, TimeWindow createdWindow,
                                          TimeWindow updatedWindow) {
            return new ShipmentQuery(PageRequest.first(step), null, "F", null,
                    createdWindow, updatedWindow, null, null, null);
        }
    }

    /** getReturnsList 查询参数；时间字段统一使用 UTC Instant。 */
    record ReturnQuery(PageRequest page, String status, String isApi,
                       TimeWindow createdWindow, TimeWindow updatedWindow,
                       String stockId, String stockNumber) {
        public ReturnQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public static ReturnQuery first(int step, TimeWindow createdWindow,
                                        TimeWindow updatedWindow) {
            return new ReturnQuery(PageRequest.first(step), null, "F",
                    createdWindow, updatedWindow, null, null);
        }
    }

    /** getReceiptsList 查询参数；updateDateGe 是订货宝单向更新时间下限。 */
    record ReceiptQuery(PageRequest page, String orderNumber,
                        TimeWindow createdWindow, Instant updatedFrom, String status) {
        public ReceiptQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public static ReceiptQuery first(int step) {
            return new ReceiptQuery(PageRequest.first(step), null, null, null, null);
        }
    }

    /** getPaymentList 查询参数；官方列表接口不提供更新时间筛选。 */
    record PaymentQuery(PageRequest page, String orderNumber,
                        TimeWindow createdWindow, String status) {
        public PaymentQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public static PaymentQuery first(int step) {
            return new PaymentQuery(PageRequest.first(step), null, null, null);
        }
    }

    record Product(String sourceId, String code, String name, String putaway,
                   Map<String, Object> attributes) {
        public Product {
            attributes = immutableAttributes(attributes);
        }
    }

    record Customer(String sourceId, String account, String number, String name,
                    String status, Instant createdAt, Instant updatedAt,
                    Map<String, Object> attributes) {
        public Customer {
            attributes = immutableAttributes(attributes);
        }
    }

    record OrderSummary(String sourceId, String orderNumber, String status,
                        BigDecimal amount, Instant createdAt, Instant updatedAt,
                        String customerNumber, String paymentStatus,
                        Map<String, Object> attributes) {
        public OrderSummary {
            attributes = immutableAttributes(attributes);
        }
    }

    record OrderDetail(String orderNumber, String status, BigDecimal amount,
                       Map<String, Object> attributes) {
        public OrderDetail {
            attributes = immutableAttributes(attributes);
        }
    }

    /** getShipsList 返回的出库/发货单摘要；attributes 保留订货宝原始业务字段。 */
    record Shipment(String sourceId, String shipmentNumber, String orderNumber,
                    String status, String statusName, String typeId, String typeName,
                    String customerNumber, String customerName, String customerGuid,
                    String warehouseNumber, String warehouseName, String warehouseGuid,
                    Instant shipmentAt, String logisticsName, String trackingNumber,
                    String remark, Instant createdAt, Instant updatedAt,
                    Map<String, Object> attributes) {
        public Shipment {
            attributes = immutableAttributes(attributes);
        }
    }

    /** getShipsContent 返回的出库/发货单详情，完整主单及嵌套明细保存在 attributes。 */
    record ShipmentDetail(String shipmentNumber, Map<String, Object> attributes) {
        public ShipmentDetail {
            attributes = immutableAttributes(attributes);
        }
    }

    /** getReturnsList 退货单摘要；attributes 保留订货宝原始业务字段。 */
    record ReturnSummary(String sourceId, String returnNumber, String orderNumber,
                         String status, String staffName, BigDecimal returnAmount,
                         BigDecimal settlementAmount, Instant returnedAt, Instant updatedAt,
                         String reason, String customerNumber, String customerGuid,
                         String consignee, String phone, String address,
                         String logisticsCompany, String logisticsNumber, String returnType,
                         String deliveryMode, Map<String, Object> attributes) {
        public ReturnSummary {
            attributes = immutableAttributes(attributes);
        }
    }

    /** getReturnsContent 退货单主信息与商品明细。 */
    record ReturnDetail(String returnNumber, ReturnSummary summary,
                        List<ReturnLine> lines, Map<String, Object> attributes) {
        public ReturnDetail {
            lines = lines == null ? List.of() : List.copyOf(lines);
            attributes = immutableAttributes(attributes);
        }
    }

    /** getReturnsContent.body 退货商品明细。 */
    record ReturnLine(String sourceId, String productGuid, String skuNumber,
                      String productCode, String productName, BigDecimal quantity,
                      BigDecimal confirmedQuantity, BigDecimal unitPrice,
                      BigDecimal confirmedPrice, String unit, BigDecimal unitQuantity,
                      BigDecimal confirmedUnitQuantity, BigDecimal conversionNumber,
                      String remark, String warehouseNumber, String warehouseName,
                      String warehouseGuid, Map<String, Object> attributes) {
        public ReturnLine {
            attributes = immutableAttributes(attributes);
        }
    }

    /** getReceiptsList 收款单；attributes 保留订货宝原始业务字段。 */
    record Receipt(String sourceId, String receiptNumber, String orderNumber,
                   String customerNumber, String customerGuid, String businessType,
                   String paymentMethod, BigDecimal amount, String status,
                   Instant transactionAt, Instant createdAt, Instant updatedAt,
                   String serialNumber, String accountName, String bankName,
                   String accountNumber, String remark, Map<String, Object> attributes) {
        public Receipt {
            attributes = immutableAttributes(attributes);
        }
    }

    /** getPaymentList 付款单；attributes 保留订货宝原始业务字段。 */
    record Payment(String sourceId, String paymentNumber, String receiptNumber,
                   String orderNumber, String customerNumber, String customerGuid,
                   String businessType, String paymentMethod, BigDecimal amount,
                   String status, Instant transactionAt, Instant createdAt,
                   String serialNumber, String accountName, String bankName,
                   String accountNumber, String remark, Map<String, Object> attributes) {
        public Payment {
            attributes = immutableAttributes(attributes);
        }
    }

    record WaitShips(String orderNumber, List<WaitShipment> shipped, List<WaitStock> waitStock,
                     Map<String, Object> attributes) {
        public WaitShips {
            shipped = shipped == null ? List.of() : List.copyOf(shipped);
            waitStock = waitStock == null ? List.of() : List.copyOf(waitStock);
            attributes = immutableAttributes(attributes);
        }
    }

    record WaitShipment(String sourceId, String shipmentNo, String status, String logisticsName,
                        String logisticsCode, String trackingNo, Instant shipmentAt,
                        Instant stockUpAt, String warehouseNo, String warehouseName,
                        List<WaitShipmentLine> lines, Map<String, Object> attributes) {
        public WaitShipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
            attributes = immutableAttributes(attributes);
        }
    }

    record WaitShipmentLine(String sourceLineId, String orderLineId, String productId,
                            String skuNo, String listType, String productCode, String productName,
                            String specification, String unit, String containerUnit,
                            BigDecimal conversionNumber, BigDecimal quantity, String remark,
                            Map<String, Object> attributes) {
        public WaitShipmentLine { attributes = immutableAttributes(attributes); }
    }

    record WaitStock(String sourceLineId, String productId, String skuNo, String listType,
                     String productCode, String productName, String specification, String unit,
                     String containerUnit, BigDecimal conversionNumber, String warehouseNo,
                     String warehouseName, BigDecimal orderedQuantity, BigDecimal stockedQuantity,
                     BigDecimal realStock, BigDecimal waitQuantity, String remark,
                     Map<String, Object> attributes) {
        public WaitStock { attributes = immutableAttributes(attributes); }
    }

    record ConnectionTestResult(boolean success, String code, String message,
                                Instant tokenExpiresAt) {
        public static ConnectionTestResult success(Instant tokenExpiresAt) {
            return new ConnectionTestResult(true, "OK", "订货宝认证成功", tokenExpiresAt);
        }

        public static ConnectionTestResult failure(String code, String message) {
            return new ConnectionTestResult(false, code, message, null);
        }
    }

    private static Map<String, Object> immutableAttributes(Map<String, Object> attributes) {
        return attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
