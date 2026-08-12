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

    /** 下载订货宝商品图片；实际字节只在 Integration 内部流转，不跨服务返回。 */
    DownloadedImage downloadProductImage(Connector connector, String sourceUrl);

    /** 查询订货宝 getSite 商品分类；供应商接口为全量数组，不返回父级关系。 */
    List<ProductCategory> getProductCategories(Connector connector);

    /** 查询订货宝 getBrands 商品品牌；供应商接口为全量数组。 */
    List<ProductBrand> getProductBrands(Connector connector);

    /** 查询订货宝 getMultiOptionsList 规格维度及规格值。 */
    Page<ProductSpecification> getProductSpecifications(Connector connector, PageRequest page);

    /** 查询订货宝 getGoodsTag 商品标签；不同版本字段名由适配器兼容。 */
    Page<ProductTag> getProductTags(Connector connector, PageRequest page);

    /** 查询 getSupplierList 供应商；地址、联系方式、税号和银行账号按当前同步要求输出完整值。 */
    Page<Supplier> getSuppliers(Connector connector, PageRequest page);

    /** 查询 getPurchaseList 后逐单补齐 getPurchaseContent 明细。 */
    Page<PurchaseOrder> getPurchaseOrders(Connector connector, PageRequest page);

    /** 查询 getPurchaseReturnList 后逐单补齐 getPurchaseReturnContent 明细。 */
    Page<PurchaseReturn> getPurchaseReturns(Connector connector, PageRequest page);

    /** 查询 getWarehousingList 后逐单补齐 getWarehousingContent 明细。 */
    Page<WarehousingReceipt> getWarehousingReceipts(Connector connector, PageRequest page);

    /** 查询 getStockInfo 仓库档案。 */
    Page<Warehouse> getWarehouses(Connector connector, PageRequest page);

    /** 调用 batchGetStock；调用方必须显式传入商品编码。 */
    List<InventoryBalance> getInventory(Connector connector, List<String> goodsCodes);

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
                        String barcode, Instant updatedFrom, Instant updatedTo) {
        public ProductQuery {
            if (page == null) {
                throw new IllegalArgumentException("page is required");
            }
        }

        public ProductQuery(PageRequest page, String status, String putaway, String goodsCode) {
            this(page, status, putaway, goodsCode, null, null, null);
        }

        public static ProductQuery first(int step) {
            return new ProductQuery(PageRequest.first(step), null, null, null, null, null, null);
        }
    }

    /** 供应商原始业务模型；跨服务转换时地址、联系方式、税号和银行账号按当前同步要求保留完整值。 */
    record Supplier(String sourceId, String sourceGuid, String code, String name,
                    String areaName, String address, String contactName, String mobile,
                    String phone, String email, String accountName, String bankName,
                    String bankAccount, String invoiceTitle, String taxpayerNumber,
                    String remark, Instant sourceUpdatedAt, Map<String, Object> attributes) {
        public Supplier { attributes = immutableAttributes(attributes); }
    }

    record Warehouse(String sourceId, String sourceGuid, String code, String name,
                     String status, Boolean defaultFlag, BigDecimal acreage, String phone,
                     String address, String collaboratorSourceId, String remark,
                     Map<String, Object> attributes) {
        public Warehouse { attributes = immutableAttributes(attributes); }
    }

    record PurchaseOrder(String sourceId, String number, String supplierSourceId,
                         String supplierCode, String supplierName, String warehouseSourceId,
                         String warehouseCode, String warehouseName, String staffSourceId,
                         String staffName, String status, String statusName, String paymentStatus,
                         String paymentStatusName, Instant deliveryAt, Instant createdAt,
                         Instant updatedAt, BigDecimal totalAmount, BigDecimal paidAmount,
                         BigDecimal goodsCount, Boolean downloaded, String remark,
                         String internalCommunication, List<PurchaseOrderLine> lines,
                         Map<String, Object> attributes) {
        public PurchaseOrder {
            lines = lines == null ? List.of() : List.copyOf(lines);
            attributes = immutableAttributes(attributes);
        }
    }

    record PurchaseOrderLine(String sourceLineId, String sourceGoodsId, String sourceGoodsGuid,
                             String goodsCode, String goodsName, String optionsId,
                             String optionsGoodsCode, String optionsSummary,
                             BigDecimal baseQuantity, BigDecimal unitPrice,
                             String purchaseUnitCode, String purchaseUnitName,
                             BigDecimal purchaseUnitQuantity, BigDecimal warehousedQuantity,
                             BigDecimal returnedQuantity, String remark,
                             Map<String, Object> attributes) {
        public PurchaseOrderLine { attributes = immutableAttributes(attributes); }
    }

    record PurchaseReturn(String sourceId, String number, String supplierSourceId,
                          String supplierCode, String supplierName, String warehouseSourceId,
                          String warehouseCode, String warehouseName, String staffSourceId,
                          String staffName, String status, String statusName,
                          BigDecimal returnAmount, BigDecimal discountAmount, String reason,
                          Instant createdAt, Instant sendAt, String internalCommunication,
                          String remark, Integer detailCount, String contactName,
                          String contactPhone, String contactAddress, List<String> cityIds,
                          List<String> cityNames, String sourceDevice,
                          String parentReturnSourceId, String parentCompanySourceId,
                          Boolean downloaded, List<PurchaseReturnLine> lines,
                          Map<String, Object> attributes) {
        public PurchaseReturn {
            cityIds = cityIds == null ? List.of() : List.copyOf(cityIds);
            cityNames = cityNames == null ? List.of() : List.copyOf(cityNames);
            lines = lines == null ? List.of() : List.copyOf(lines);
            attributes = immutableAttributes(attributes);
        }
    }

    record PurchaseReturnLine(String sourceLineId, String sourceGoodsId, String goodsCode,
                              String goodsName, String optionsId, String optionsGoodsCode,
                              String optionsSummary, BigDecimal requestedQuantity,
                              BigDecimal confirmedQuantity, BigDecimal returnPrice,
                              BigDecimal confirmedPrice, String unitCode, String unitName,
                              BigDecimal unitQuantity, BigDecimal confirmedUnitQuantity,
                              BigDecimal conversionNumber, BigDecimal amount,
                              BigDecimal costPrice, String purchaseOrderNo,
                              String categoryName, String brandName, String remark,
                              Map<String, Object> attributes) {
        public PurchaseReturnLine { attributes = immutableAttributes(attributes); }
    }

    record WarehousingReceipt(String sourceId, String number, String warehouseSourceId,
                              String warehouseName, String supplierSourceId, String supplierName,
                              String typeId, String typeName, String status, String statusName,
                              String staffName, String clientSourceId, String accountSourceId,
                              String collaboratorSourceId, String collaboratorName,
                              String logisticsSourceId, String expressNumber, Instant storageAt,
                              Instant createdAt, Instant updatedAt, BigDecimal freightAmount,
                              BigDecimal totalAmount, BigDecimal costAmount, Boolean apiFlag,
                              String splitType, String remark, List<WarehousingLine> lines,
                              List<PurchaseLink> purchaseLinks, Map<String, Object> attributes) {
        public WarehousingReceipt {
            lines = lines == null ? List.of() : List.copyOf(lines);
            purchaseLinks = purchaseLinks == null ? List.of() : List.copyOf(purchaseLinks);
            attributes = immutableAttributes(attributes);
        }
    }

    record WarehousingLine(String sourceLineId, String sourceGoodsId, String goodsCode,
                           String goodsName, String optionsId, String optionsGoodsCode,
                           String optionsSummary, BigDecimal baseQuantity,
                           BigDecimal unitQuantity, String unitCode, String unitName,
                           BigDecimal conversionNumber, BigDecimal costPrice,
                           BigDecimal unitCostPrice, BigDecimal purchasePrice,
                           BigDecimal wholesalePrice, String allocation, String barcode,
                           String goodsModel, BigDecimal sourceRealQuantity,
                           BigDecimal sourceAvailableQuantity, String collaboratorSourceId,
                           String collaboratorName, String remark, Map<String, Object> attributes) {
        public WarehousingLine { attributes = immutableAttributes(attributes); }
    }

    record PurchaseLink(String sourcePurchaseId, String purchaseOrderNo) { }

    record InventoryBalance(String goodsGuid, String goodsCode, String goodsName,
                            String warehouseGuid, String warehouseCode, String warehouseName,
                            String firstOptionGuid, String firstOptionCode, String firstOptionName,
                            String secondOptionGuid, String secondOptionCode, String secondOptionName,
                            BigDecimal availableQuantity, BigDecimal realQuantity,
                            Map<String, Object> attributes) {
        public InventoryBalance { attributes = immutableAttributes(attributes); }
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
                   String barcode, String unit, String categorySourceId,
                   String brandSourceId, String model, String subtitle, String keywords,
                   String allocation, String mainImageSource, String multiId,
                   BigDecimal orderPrice, BigDecimal marketPrice, BigDecimal purchasePrice,
                   BigDecimal price4, String middleUnit, String bigUnit, String middleBarcode,
                   String bigBarcode, String conversionBarcode, BigDecimal baseToMiddleRate,
                   BigDecimal baseToBigRate, BigDecimal minimumOrder, String minimumOrderUnit,
                   BigDecimal inventoryLower, BigDecimal inventoryUpper,
                   BigDecimal safetyInventory, BigDecimal middleOrderPrice,
                   BigDecimal bigOrderPrice, List<ProductImage> images,
                   Map<String, String> customFields, List<ProductSku> skus,
                   Map<String, Object> attributes) {
        public Product(String sourceId, String code, String name, String putaway,
                       String barcode, String unit, String categorySourceId,
                       String brandSourceId, List<ProductSku> skus,
                       Map<String, Object> attributes) {
            this(sourceId, code, name, putaway, barcode, unit, categorySourceId, brandSourceId,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null,
                    List.<ProductImage>of(), Map.<String, String>of(), skus, attributes);
        }

        public Product {
            images = images == null ? List.of() : List.copyOf(images);
            customFields = customFields == null ? Map.of() : Map.copyOf(customFields);
            skus = skus == null ? List.of() : List.copyOf(skus);
            attributes = immutableAttributes(attributes);
        }
    }

    record ProductSku(String sourceId, String code, String barcode,
                      String firstSpecificationValueSourceId,
                      String secondSpecificationValueSourceId,
                      String specificationName, String optionsId, BigDecimal orderPrice,
                      BigDecimal marketPrice, BigDecimal purchasePrice,
                      BigDecimal middleOrderPrice, BigDecimal bigOrderPrice,
                      String middleBarcode, String bigBarcode,
                      Map<String, Object> attributes) {
        public ProductSku(String sourceId, String code, String barcode,
                          String firstSpecificationValueSourceId,
                          String secondSpecificationValueSourceId,
                          String specificationName, Map<String, Object> attributes) {
            this(sourceId, code, barcode, firstSpecificationValueSourceId,
                    secondSpecificationValueSourceId, specificationName, null, null, null, null,
                    null, null, null, null, attributes);
        }

        public ProductSku {
            attributes = immutableAttributes(attributes);
        }
    }

    record DownloadedImage(byte[] content, String contentType) {
        public DownloadedImage {
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException("商品图片内容不能为空");
            }
        }
    }

    record ProductImage(String sourceResourceId, String sourceGoodsId, String originalName,
                        String fileName, Integer sortOrder, String sourceUrl) {
        public ProductImage(String sourceResourceId, String sourceGoodsId, String originalName,
                            String fileName, Integer sortOrder) {
            this(sourceResourceId, sourceGoodsId, originalName, fileName, sortOrder, null);
        }
    }

    record ProductCategory(String sourceId, String externalReferenceId, String name,
                           String categoryNumber, String parentSourceId, Boolean defaultCategory,
                           Map<String, Object> attributes) {
        public ProductCategory(String sourceId, String externalReferenceId, String name,
                               Map<String, Object> attributes) {
            this(sourceId, externalReferenceId, name, null, null, null, attributes);
        }

        public ProductCategory {
            attributes = immutableAttributes(attributes);
        }
    }

    record ProductBrand(String sourceId, String externalReferenceId, String name,
                         String brandNumber, Integer sortOrder, String description,
                         Map<String, Object> attributes) {
        public ProductBrand(String sourceId, String externalReferenceId, String name,
                            Map<String, Object> attributes) {
            this(sourceId, externalReferenceId, name, null, null, null, attributes);
        }

        public ProductBrand {
            attributes = immutableAttributes(attributes);
        }
    }

    record ProductSpecification(String sourceId, String code, String name,
                                String parentSourceId, List<ProductSpecificationValue> values,
                                Map<String, Object> attributes) {
        public ProductSpecification(String sourceId, String code, String name,
                                    List<ProductSpecificationValue> values,
                                    Map<String, Object> attributes) {
            this(sourceId, code, name, null, values, attributes);
        }

        public ProductSpecification {
            values = values == null ? List.of() : List.copyOf(values);
            attributes = immutableAttributes(attributes);
        }
    }

    record ProductSpecificationValue(String sourceId, String code, String name,
                                     String parentSourceId, Map<String, Object> attributes) {
        public ProductSpecificationValue {
            attributes = immutableAttributes(attributes);
        }
    }

    record ProductTag(String sourceId, String code, String name, Integer sortOrder,
                      Integer relationCount, Instant createdAt, Instant updatedAt,
                      String groupSourceId, String groupName, Map<String, Object> attributes) {
        public ProductTag(String sourceId, String code, String name,
                          Map<String, Object> attributes) {
            this(sourceId, code, name, null, null, null, null, null, null, attributes);
        }

        public ProductTag {
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
