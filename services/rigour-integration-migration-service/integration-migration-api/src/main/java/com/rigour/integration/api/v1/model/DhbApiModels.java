package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration V1 的跨服务请求/响应模型。
 *
 * <p>这些模型描述本平台的 Integration 契约，不等同于订货宝官方的 {@code f/v} 报文；
 * 账号密码、Token 和原始回执不会进入该模块。</p>
 */
public final class DhbApiModels {

    private DhbApiModels() {
    }

    public record ConnectorView(UUID id, UUID tenantId, String code, String name,
                                String baseUrl, String authSecretRef, String status, long version) {
    }

    public record ConnectorCommand(String code, String name, String baseUrl,
                                   String authSecretRef, String status, long version) {
    }

    public record SyncTaskView(UUID id, UUID tenantId, UUID connectorId, String code,
                               String objectType, String status, Instant lastRunAt,
                               Instant nextRunAt, long version) {
    }

    public record SyncTaskCommand(UUID connectorId, String code, String objectType,
                                  String status, Instant nextRunAt, long version) {
    }

    /** 手动订单同步请求；首次联调建议显式提供时间窗口，避免误拉全量数据。 */
    public record SyncRunCommand(Instant from, Instant to, Integer pageSize) {
    }

    /** 手动同步结果；不包含第三方凭据、令牌或原始回执。 */
    public record SyncRunView(UUID runId, UUID taskId, String status,
                              Instant windowFrom, Instant windowTo,
                              long fetchedCount, long acceptedCount,
                              long duplicateCount, long rejectedCount,
                              String errorCode, String errorMessage) {
    }

    public record OrderMirrorView(UUID id, UUID tenantId, String sourceOrderId, String orderNo,
                                  String sourceStatus, BigDecimal amount, Instant orderTime,
                                  String mirrorStatus, long version) {
    }

    public record SyncLogView(UUID id, UUID tenantId, UUID taskId, UUID runId, String level,
                              String message, String errorCode, Instant occurredAt) {
    }

    public record FieldMappingView(UUID id, UUID tenantId, UUID connectorId, String sourceField,
                                   String targetField, String transformType, boolean enabled,
                                   long version) {
    }

    public record FieldMappingCommand(UUID connectorId, String sourceField, String targetField,
                                      String transformType, boolean enabled, long version) {
    }

    /** 商品查询参数，对应订货宝 getGoodsList 的非认证参数。 */
    public record ProductQueryCommand(Integer begin, Integer step, String status, String putaway,
                                      String goodsCode, Instant updatedFrom, Instant updatedTo,
                                      String barcode) {
    }

    /** 客户查询参数，对应订货宝 getDealersList 的非认证参数。 */
    public record CustomerQueryCommand(Integer begin, Integer step, Integer status, Integer dataType,
                                       String timeType, Instant updatedFrom, Instant updatedTo,
                                       String clientNo, Integer clientArea, Integer typeId) {
    }

    /** 订单查询参数，对应订货宝 getOrderList 的非认证参数。 */
    public record OrderQueryCommand(Integer begin, Integer step, String orderStatus,
                                    Instant createdFrom, Instant createdTo,
                                    Instant updatedFrom, Instant updatedTo,
                                    String exceptionStatus, String apiStatus, String payStatus,
                                    Integer splitType) {
    }

    /** 订单明细查询命令；自动标记和自动审核必须由调用方显式决定。 */
    public record OrderContentCommand(Boolean autoMarkDownloaded, Boolean autoAudit) {
    }

    /**
     * 出库/发货单列表查询参数，对应订货宝 getShipsList。
     * begin 为本平台零基偏移，Integration 会转换为订货宝 page；step 最大1000。
     */
    public record ShipmentQueryCommand(
            /** 本平台零基偏移，默认0；Integration转换为订货宝page。 */ Integer begin,
            /** 每页数量，默认100，范围1..1000；转换为订货宝page_size。 */ Integer step,
            /** 单据状态：shipped待发货、receivedin待收货、received已收货、cancelled已取消；支持逗号分隔。 */ String status,
            /** 下载状态：F未下载、T已下载；支持逗号分隔，默认遵循订货宝接口值。 */ String isApi,
            /** 出库类型：-2采购退货、10销售出库、11盘亏、17其他、18调拨、19联营；支持逗号分隔。 */ String typeId,
            /** 单据创建开始时间，必须与createdTo同时提供。 */ Instant createdFrom,
            /** 单据创建结束时间，必须与createdFrom同时提供。 */ Instant createdTo,
            /** 单据更新时间开始时间，必须与updatedTo同时提供。 */ Instant updatedFrom,
            /** 单据更新时间结束时间，必须与updatedFrom同时提供。 */ Instant updatedTo,
            /** 关联客户编号client_num。 */ String clientNumber,
            /** 订货宝仓库ID，支持逗号分隔。 */ String stockId,
            /** 订货宝仓库编码stock_num，支持逗号分隔。 */ String stockNumber) {
    }

    /**
     * 退货单列表查询参数，对应订货宝 getReturnsList。
     * begin/step 使用本平台零基偏移语义；时间参数由 Integration 转为东八区文本。
     */
    public record ReturnQueryCommand(
            /** 本平台零基偏移，默认0。 */ Integer begin,
            /** 返回条数，默认100，最大1000。 */ Integer step,
            /** 退货状态：return_audit待审核、shipp_cust待客户发货、shipped待收货、refunded待退款、finished已完成、cancelled已取消；支持逗号分隔。 */ String status,
            /** 下载状态：F未下载、T已下载、All全部；默认遵循订货宝接口值。 */ String isApi,
            /** 退货单创建开始时间，必须与createdTo同时提供。 */ Instant createdFrom,
            /** 退货单创建结束时间，必须与createdFrom同时提供。 */ Instant createdTo,
            /** 退货单更新时间开始时间，必须与updatedTo同时提供。 */ Instant updatedFrom,
            /** 退货单更新时间结束时间，必须与updatedFrom同时提供。 */ Instant updatedTo,
            /** 订货宝仓库ID，支持逗号分隔。 */ String stockId,
            /** 订货宝仓库编号，支持逗号分隔。 */ String stockNumber) {
    }

    /** 收款单列表查询参数，对应订货宝 getReceiptsList。 */
    public record ReceiptQueryCommand(
            /** 订单编号；仅查询订单收款时使用。 */ String orderNumber,
            /** 本平台零基偏移，默认0。 */ Integer begin,
            /** 返回条数，默认100，最大1000。 */ Integer step,
            /** 收款单转账开始时间，必须与createdTo同时提供。 */ Instant createdFrom,
            /** 收款单转账结束时间，必须与createdFrom同时提供。 */ Instant createdTo,
            /** 收款单更新时间下限，对应订货宝 updateDateGe。 */ Instant updatedFrom,
            /** 收款状态：pend_receipt待确认、pend_receipted已确认、canceled已取消、all全部。 */ String status) {
    }

    /** 付款单列表查询参数，对应订货宝 getPaymentList。 */
    public record PaymentQueryCommand(
            /** 订单编号；仅查询订单付款时使用。 */ String orderNumber,
            /** 本平台零基偏移，默认0。 */ Integer begin,
            /** 返回条数，默认100，最大1000。 */ Integer step,
            /** 付款单转账开始时间，必须与createdTo同时提供。 */ Instant createdFrom,
            /** 付款单转账结束时间，必须与createdFrom同时提供。 */ Instant createdTo,
            /** 付款状态：pend_receipt待确认、pend_receipted已确认、canceled已取消、all全部。 */ String status) {
    }

    public record ProductPageView(long total, List<ProductView> items) {
    }

    public record ProductView(String sourceId, String code, String name, String putaway,
                              Map<String, Object> sourceFields) {
    }

    public record CustomerPageView(long total, List<CustomerView> items) {
    }

    public record CustomerView(String sourceId, String account, String number, String name,
                               String status, Instant createdAt, Instant updatedAt,
                               Map<String, Object> sourceFields) {
    }

    public record OrderPageView(long total, List<OrderView> items) {
    }

    public record OrderView(String sourceId, String orderNumber, String status, BigDecimal amount,
                            Instant createdAt, Instant updatedAt, String customerNumber,
                            String paymentStatus, Map<String, Object> sourceFields) {
    }

    public record OrderContentView(String orderNumber, String status, BigDecimal amount,
                                   Map<String, Object> sourceFields) {
    }

    /** getShipsList 出库/发货单列表响应；sourceFields 保留列表原始业务字段，不含sKey。 */
    public record ShipmentPageView(long total, List<ShipmentView> items) {
    }

    /** getShipsList 单条出库/发货单摘要。 */
    public record ShipmentView(
            /** 订货宝ships_id，来源主键。 */ String sourceId,
            /** 订货宝ships_num，出库/发货单业务键。 */ String shipmentNumber,
            /** 订货宝orders_num，关联订货单号。 */ String orderNumber,
            /** 单据状态原值。 */ String status,
            /** 单据状态中文名。 */ String statusName,
            /** 出库类型ID原值。 */ String typeId,
            /** 出库类型名称。 */ String typeName,
            /** 客户编号。 */ String customerNumber,
            /** 客户名称。 */ String customerName,
            /** 客户ERP外码。 */ String customerGuid,
            /** 仓库编号。 */ String warehouseNumber,
            /** 仓库名称。 */ String warehouseName,
            /** 仓库ERP外码。 */ String warehouseGuid,
            /** 发货时间，已转换为UTC。 */ Instant shipmentAt,
            /** 物流公司名称。 */ String logisticsName,
            /** 物流单号express_num。 */ String trackingNumber,
            /** 单据备注。 */ String remark,
            /** 单据创建时间，已转换为UTC。 */ Instant createdAt,
            /** 单据更新时间，已转换为UTC。 */ Instant updatedAt,
            /** 订货宝列表原始业务字段，不含sKey。 */ Map<String, Object> sourceFields) {
    }

    /** getShipsContent 出库/发货单详情；sourceFields 含address、collaborator和list。 */
    public record ShipmentContentView(String shipmentNumber, Map<String, Object> sourceFields) {
    }

    /** getReturnsList 退货单列表响应。 */
    public record ReturnPageView(long total, List<ReturnView> items) {
    }

    /** getReturnsList/getReturnsContent 退货单主信息；sourceFields保留原始业务字段。 */
    public record ReturnView(
            /** 订货宝 ReturnsSN，来源业务键。 */ String sourceId,
            /** 退货单编号 ReturnsSN。 */ String returnNumber,
            /** 关联订货宝订单号 OrdersNum，列表接口未返回时为空。 */ String orderNumber,
            /** 退货单状态：return_audit、shipp_cust、shipped、refunded、finished、cancelled。 */ String status,
            /** 退货单经办人 StaffName。 */ String staffName,
            /** 申请退货金额 ReturnsTotal。 */ BigDecimal returnAmount,
            /** 确认结算金额 ReturnsDiscountTotal。 */ BigDecimal settlementAmount,
            /** 退货单创建时间 ReturnsDate，已转换为UTC。 */ Instant returnedAt,
            /** 退货单更新时间 ReturnsUpdateDate，已转换为UTC。 */ Instant updatedAt,
            /** 退货原因 ReturnsReason。 */ String reason,
            /** 客户编号 ClientNum。 */ String customerNumber,
            /** 客户ERP外码 ClientGUID。 */ String customerGuid,
            /** 退单收货人 ReturnsConsignee。 */ String consignee,
            /** 退单联系电话。 */ String phone,
            /** 退货地址。 */ String address,
            /** 退货物流公司 ReturnsSendCompany。 */ String logisticsCompany,
            /** 退货物流单号 ReturnsSendNo。 */ String logisticsNumber,
            /** 退货类型：0未确认、1退货退款、2仅退款。 */ String returnType,
            /** 退货配送方式 ReturnsSendMode。 */ String deliveryMode,
            /** 订货宝原始退货业务字段，不含sKey。 */ Map<String, Object> sourceFields) {
    }

    /** getReturnsContent 退货单明细响应；body为退货商品明细。 */
    public record ReturnContentView(String returnNumber, ReturnView summary,
                                    List<ReturnLineView> lines,
                                    Map<String, Object> sourceFields) {
        public ReturnContentView {
            lines = lines == null ? List.of() : List.copyOf(lines);
            sourceFields = sourceFields == null ? Map.of() : sourceFields;
        }
    }

    /** getReturnsContent.body 退货商品明细。 */
    public record ReturnLineView(
            /** 来源商品明细键，优先取 Guid。 */ String sourceId,
            /** 商品ERP外码 TrueGuid/Guid。 */ String productGuid,
            /** 规格商品编码 OptionsGoodsNum。 */ String skuNumber,
            /** 商品编码 Coding。 */ String productCode,
            /** 商品名称 Name。 */ String productName,
            /** 申请退货数量 ReturnsNumber。 */ BigDecimal quantity,
            /** 确认退货数量 ReturnsConfirmNumber。 */ BigDecimal confirmedQuantity,
            /** 申请退货单价 ReturnsPrice。 */ BigDecimal unitPrice,
            /** 确认退货单价 ReturnsConfirmPrice。 */ BigDecimal confirmedPrice,
            /** 退货单位名称 ReturnsUnitsName。 */ String unit,
            /** 退货单位数量 ReturnsUnitsNumber。 */ BigDecimal unitQuantity,
            /** 确认退货单位数量 ReturnsConfirmUnitsNumber。 */ BigDecimal confirmedUnitQuantity,
            /** 小单位与大单位换算关系 ConversionNumber。 */ BigDecimal conversionNumber,
            /** 退货明细备注 ReturnsRemark。 */ String remark,
            /** 退货仓库编号 Stock.StockId。 */ String warehouseNumber,
            /** 退货仓库名称 Stock.StockName。 */ String warehouseName,
            /** 退货仓库ERP外码 Stock.StockGuid。 */ String warehouseGuid,
            /** 订货宝原始退货明细字段。 */ Map<String, Object> sourceFields) {
    }

    /** getReceiptsList 收款单列表响应。 */
    public record ReceiptPageView(long total, List<ReceiptView> items) {
    }

    /** 收款单结构化响应；sourceFields保留原始业务字段。 */
    public record ReceiptView(
            /** 订货宝收款单号 ReceiptsNum。 */ String sourceId,
            /** 收款单编号 ReceiptsNum。 */ String receiptNumber,
            /** 关联订单号 OrdersNum。 */ String orderNumber,
            /** 客户编号 ClientNum。 */ String customerNumber,
            /** 客户ERP外码 ClientGuid。 */ String customerGuid,
            /** 收款类型 IncexpId。 */ String businessType,
            /** 支付方式 TypeId。 */ String paymentMethod,
            /** 收款金额 Amount。 */ BigDecimal amount,
            /** 收款状态；来源未返回时为空。 */ String status,
            /** 转账日期 ReceiptsDate，已转换为UTC。 */ Instant transactionAt,
            /** 收款单录入时间 CreateDate，已转换为UTC。 */ Instant createdAt,
            /** 收款单更新时间 UpdateDate，已转换为UTC。 */ Instant updatedAt,
            /** 收款流水号 SerialNumber。 */ String serialNumber,
            /** 开户名称 AccountName。 */ String accountName,
            /** 开户行 BankName。 */ String bankName,
            /** 银行账号 AccountNumber，敏感字段。 */ String accountNumber,
            /** 收款单备注 Remark。 */ String remark,
            /** 订货宝原始收款业务字段，不含sKey。 */ Map<String, Object> sourceFields) {
    }

    /** getPaymentList 付款单列表响应。 */
    public record PaymentPageView(long total, List<PaymentView> items) {
    }

    /** 付款单结构化响应；sourceFields保留原始业务字段。 */
    public record PaymentView(
            /** 订货宝付款单号 PaymentNum。 */ String sourceId,
            /** 付款单编号 PaymentNum。 */ String paymentNumber,
            /** 关联收款单号 ReceiptsNum。 */ String receiptNumber,
            /** 关联订单号 OrdersNum。 */ String orderNumber,
            /** 客户编号 ClientNum。 */ String customerNumber,
            /** 客户ERP外码 ClientGuid。 */ String customerGuid,
            /** 付款类型 IncexpId。 */ String businessType,
            /** 支付方式 TypeId。 */ String paymentMethod,
            /** 付款金额 Amount。 */ BigDecimal amount,
            /** 付款状态；来源未返回时为空。 */ String status,
            /** 转账日期 ReceiptsDate，已转换为UTC。 */ Instant transactionAt,
            /** 付款单录入时间 CreateDate，已转换为UTC。 */ Instant createdAt,
            /** 收付款流水号 SerialNumber。 */ String serialNumber,
            /** 开户名称 AccountName。 */ String accountName,
            /** 开户行 BankName。 */ String bankName,
            /** 银行账号 AccountNumber，敏感字段。 */ String accountNumber,
            /** 付款单备注 Remark。 */ String remark,
            /** 订货宝原始付款业务字段，不含sKey。 */ Map<String, Object> sourceFields) {
    }

    /** getWaitShips返回的指定订货单出库/发货及待出库物流数据。 */
    public record WaitShipsView(String orderNumber, List<WaitShipView> shipped,
                                List<WaitStockView> waitStock, Map<String, Object> sourceFields) {
        public WaitShipsView {
            shipped = shipped == null ? List.of() : List.copyOf(shipped);
            waitStock = waitStock == null ? List.of() : List.copyOf(waitStock);
            sourceFields = sourceFields == null ? Map.of() : sourceFields;
        }
    }

    /** getWaitShips.shipped已出库/已发货记录。 */
    public record WaitShipView(String sourceId, String shipmentNo, String status,
                               String logisticsName, String logisticsCode, String trackingNo,
                               Instant shipmentAt, Instant stockUpAt, String warehouseNo,
                               String warehouseName, List<WaitShipLineView> lines,
                               Map<String, Object> sourceFields) {
        public WaitShipView {
            lines = lines == null ? List.of() : List.copyOf(lines);
            sourceFields = sourceFields == null ? Map.of() : sourceFields;
        }
    }

    /** getWaitShips.shipped.list已出库/已发货商品明细。 */
    public record WaitShipLineView(String sourceLineId, String orderLineId, String productId,
                                   String skuNo, String listType, String productCode,
                                   String productName, String specification, String unit,
                                   String containerUnit, BigDecimal conversionNumber,
                                   BigDecimal quantity, String remark,
                                   Map<String, Object> sourceFields) {
        public WaitShipLineView {
            sourceFields = sourceFields == null ? Map.of() : sourceFields;
        }
    }

    /** getWaitShips.wait_stock待出库商品明细。 */
    public record WaitStockView(String sourceLineId, String productId, String skuNo,
                                String listType, String productCode, String productName,
                                String specification, String unit, String containerUnit,
                                BigDecimal conversionNumber, String warehouseNo,
                                String warehouseName, BigDecimal orderedQuantity,
                                BigDecimal stockedQuantity, BigDecimal realStock,
                                BigDecimal waitQuantity, String remark,
                                Map<String, Object> sourceFields) {
        public WaitStockView {
            sourceFields = sourceFields == null ? Map.of() : sourceFields;
        }
    }
}
