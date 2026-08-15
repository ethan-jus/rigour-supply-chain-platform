package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Integration 完成订货宝协议解析和防腐转换后交给 Order Center 的订单域批次。
 *
 * <p>该服务间契约不携带订货宝账号、密码、Token 或 HTTP 协议字段；rawJson 只保存
 * 单条来源业务数据，用于审计、重放和 SHA-256 幂等核对。</p>
 */
public record DhbOrderImportBatch(
        /** 订单列表及可选详情；来源函数为 getOrderList/getOrderContent。 */ List<OrderItem> orders,
        /** 独立发货单及可选详情；来源函数为 getShipsList/getShipsContent。 */ List<ShipmentItem> shipments,
        /** 出库/发货物流快照；来源函数为 getWaitShips，按orders_num查询。 */ List<ShipmentLogisticsItem> shipmentLogistics,
        /** 退货单及可选详情；来源函数为 getReturnsList/getReturnsContent。 */ List<ReturnItem> returns,
        /** 收款单和付款单；来源函数为 getReceiptsList/getPaymentList。 */ List<FinancialItem> financialDocuments) {

    public DhbOrderImportBatch {
        orders = orders == null ? List.of() : List.copyOf(orders);
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
        shipmentLogistics = shipmentLogistics == null ? List.of() : List.copyOf(shipmentLogistics);
        returns = returns == null ? List.of() : List.copyOf(returns);
        financialDocuments = financialDocuments == null ? List.of() : List.copyOf(financialDocuments);
    }

    /** 兼容未接入物流快照时的四类单据构造方式。 */
    public DhbOrderImportBatch(List<OrderItem> orders, List<ShipmentItem> shipments,
                               List<ReturnItem> returns, List<FinancialItem> financialDocuments) {
        this(orders, shipments, List.of(), returns, financialDocuments);
    }

    /** @return 当前批次订单、发货、物流、退货和收付款数据的记录总数。 */
    public int size() {
        return orders.size() + shipments.size() + shipmentLogistics.size()
                + returns.size() + financialDocuments.size();
    }

    public record OrderItem(
            /** 订货宝订单号 OrderSN，本租户内幂等业务键。 */ String sourceOrderNo,
            /** pricing待核价、pending待审核、stockup待出库、shipped待发货、received待收货、finished已完成、forcedone强制完成、cancelled已取消。 */ String sourceStatus,
            /** getOrderList.PayStatus：oblig待收款、uncollect部分收款、paided已收款、cancelled已取消、wait待确认、part部分确认。 */ String paymentStatus,
            /** 订货宝订单类型原值。 */ String orderType,
            /** 订单总金额 OrderTotal。 */ BigDecimal totalAmount,
            /** 来源下单时间 OrderDate。 */ Instant orderedAt,
            /** 来源最后更新时间 OrderUpdateDate。 */ Instant sourceUpdatedAt,
            /** 来源更新时间字段原始文本。 */ String sourceUpdateTime,
            /** 来源要求交付日期。 */ String deliveryDate,
            /** 订单备注。 */ String remark,
            /** 来源客户编号 ClientNO。 */ String customerNo,
            /** 来源客户 ERP 外码 ClientGUID。 */ String customerGuid,
            /** 客户名称快照。 */ String customerName,
            /** 收货人姓名。 */ String receiverName,
            /** 收货单位名称。 */ String receiverCompany,
            /** 收货联系电话，敏感字段。 */ String receiverPhone,
            /** 收货详细地址，敏感字段。 */ String receiverAddress,
            /** 收货省份。 */ String province,
            /** 收货城市。 */ String city,
            /** 收货区县。 */ String district,
            /** 来源下载状态：F未下载、T已下载。 */ String sourceApiStatus,
            /** 来源异常标记：F正常、T异常。 */ String sourceExceptionStatus,
            /** 来源发货方式。 */ String sourceSendType,
            /** 来源最后下单时间原值。 */ String sourceLastOrderAt,
            /** 来源下单设备。 */ String sourceDevice,
            /** 是否管理员订单原值。 */ String sourceAdminOrder,
            /** 来源拆单类型原值。 */ String splitType,
            /** 来源拆单类型中文名。 */ String splitTypeName,
            /** getOrderContent 商品明细；未补拉详情时为空。 */ List<OrderLineItem> lines,
            /** getOrderContent.Ships 发货快照。 */ List<OrderShipmentItem> shipmentSnapshots,
            /** getOrderList 单条原始 JSON，不含认证字段。 */ String rawListJson,
            /** getOrderContent 单条原始 JSON；未补拉详情时为空。 */ String rawDetailJson,
            /** 当前有效原始 JSON 的 SHA-256。 */ String payloadHash,
            /** 是否已调用并包含 getOrderContent 详情。 */ boolean detailIncluded,
            String customerType,
            String customerArea,
            String adminUser,
            String operationName,
            String salesPerson,
            String salesPersonMobile,
            String assistantSalesPersons,
            String auditAt,
            String settlementMethod,
            BigDecimal goodsWeight,
            BigDecimal taxAmount,
            BigDecimal discountPrice,
            BigDecimal discountTotal,
            BigDecimal freightAmount,
            BigDecimal applyTotal,
            BigDecimal couponDiscountedAmount,
            String customerRemark,
            String internalComment,
            String invoiceTitle,
            String invoiceContent,
            String invoiceBank,
            String invoiceBankAccount,
            String taxpayerNumber,
            /** 订货宝客户标签。 */
            String customerTag,
            /** 订货宝发票类型。 */
            String invoiceType) {
        public OrderItem {
            lines = lines == null ? List.of() : List.copyOf(lines);
            shipmentSnapshots = shipmentSnapshots == null ? List.of() : List.copyOf(shipmentSnapshots);
        }
    }

    public record OrderLineItem(
            /** 订货宝订单明细 ID orders_list_id；与订单号共同保证幂等。 */ String sourceLineId,
            /** 来源商品 ERP 外码 Guid/TrueGuid。 */ String sourceProductGuid,
            /** 来源 SKU 编码。 */ String skuNo,
            /** 订货宝商品选项编号 OptionsGoodsNo。 */ String sourceOptionsGoodsNo,
            /** 订货宝商品条码 Barcode。 */ String sourceBarcode,
            /** 商品名称快照。 */ String productName,
            /** 商品编码 Coding。 */ String productCode,
            /** 第一层规格。 */ String specificationFirst,
            /** 第二层规格。 */ String specificationSecond,
            /** 组合规格名称。 */ String specificationName,
            /** 来源订单单价。 */ BigDecimal unitPrice,
            /** 来源订购数量。 */ BigDecimal quantity,
            /** 来源明细金额。 */ BigDecimal lineAmount,
            /** 来源计量单位。 */ String unit,
            /** 订单明细备注。 */ String remark,
            BigDecimal purchasePrice,
            BigDecimal conversionNumber,
            BigDecimal offerPrice,
            BigDecimal actualAmount,
            BigDecimal goodsWeight,
            String preSale,
            String contentType,
            String invoiceTax,
            BigDecimal contentPercent) {
    }

    public record OrderShipmentItem(
            /** getOrderContent.Ships 中的发货单号 ships_num。 */ String sourceShipmentNo,
            /** 来源发货状态原值。 */ String status,
            /** 来源发货时间原值。 */ String shipmentDate,
            /** 来源备货时间原值。 */ String stockUpTime) {
    }

    public record ShipmentItem(
            /** 订货宝发货单主键 ships_id，仅用于来源追溯。 */ String sourceShipmentId,
            /** 发货单号 ships_num，本租户内幂等业务键。 */ String shipmentNo,
            /** 关联订单号 orders_num。 */ String orderNo,
            /** shipped待发货、receivedin待收货、received已收货、cancelled已取消。 */ String status,
            /** 来源状态中文名 status_name。 */ String statusName,
            /** 出库类型ID：-2采购退货、10销售出库、11盘亏、17其他、18调拨、19联营。 */ String typeId,
            /** 出库类型名称。 */ String typeName,
            /** 来源客户编号。 */ String customerNo,
            /** 客户名称快照。 */ String customerName,
            /** 客户 ERP 外码。 */ String customerGuid,
            /** 出库仓库编号。 */ String warehouseNo,
            /** 出库仓库名称。 */ String warehouseName,
            /** 出库仓库 ERP 外码。 */ String warehouseGuid,
            /** 来源发货时间。 */ Instant shipmentAt,
            /** 物流公司名称。 */ String logisticsName,
            /** 物流运单号。 */ String trackingNo,
            /** 发货单备注。 */ String remark,
            /** 来源单据创建时间。 */ Instant createdAt,
            /** 来源单据更新时间。 */ Instant updatedAt,
            /** getShipsContent 商品明细；未补拉详情时为空。 */ List<ShipmentLineItem> lines,
            /** 当前来源原始 JSON；含详情时为 list+detail 组合对象。 */ String rawJson,
            /** rawJson 的 SHA-256。 */ String payloadHash,
            /** 是否已调用并包含 getShipsContent 详情。 */ boolean detailIncluded) {
        public ShipmentItem { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record ShipmentLineItem(
            /** 发货单明细来源ID；来源无稳定ID时由 Integration 生成内容稳定键。 */ String sourceLineId,
            /** 来源商品 ERP 外码或订货宝商品ID。 */ String sourceProductGuid,
            /** 规格商品编码。 */ String skuNo,
            /** 商品编码 coding。 */ String productCode,
            /** 商品名称快照。 */ String productName,
            /** 本次发货数量，沿用来源小单位。 */ BigDecimal quantity,
            /** 来源发货单价。 */ BigDecimal unitPrice,
            /** 来源明细金额。 */ BigDecimal amount,
            /** 来源计量单位。 */ String unit,
            /** 明细出库仓库编号。 */ String warehouseNo,
            /** 发货明细备注。 */ String remark) {
    }

    /** 一个订货单对应的一次getWaitShips本地快照，按订单号幂等更新。 */
    public record ShipmentLogisticsItem(
            /** 订货宝订单号orders_num，本租户内幂等业务键。 */ String orderNo,
            /** getWaitShips.shipped已出库/已发货清单。 */ List<ShipmentLogisticsRecord> shipped,
            /** getWaitShips.wait_stock待出库商品清单。 */ List<WaitStockItem> waitStock,
            /** getWaitShips完整业务原始JSON，不含sKey。 */ String rawJson,
            /** rawJson的SHA-256摘要。 */ String payloadHash) {
        public ShipmentLogisticsItem {
            shipped = shipped == null ? List.of() : List.copyOf(shipped);
            waitStock = waitStock == null ? List.of() : List.copyOf(waitStock);
        }
    }

    /** getWaitShips.shipped中的已出库/已发货记录。 */
    public record ShipmentLogisticsRecord(
            /** 发货单主键ships_id。 */ String sourceShipmentId,
            /** 出库单/发货单号ships_num。 */ String shipmentNo,
            /** shipped待发货、receivedin待收货、received已收货、cancelled已取消。 */ String status,
            /** 物流公司名称。 */ String logisticsName,
            /** 物流公司编码。 */ String logisticsCode,
            /** 物流单号express_num。 */ String trackingNo,
            /** 发货时间ships_date。 */ Instant shipmentAt,
            /** 出库时间ships_time。 */ Instant stockUpAt,
            /** 仓库编号stock_num。 */ String warehouseNo,
            /** 仓库名称stock_name。 */ String warehouseName,
            /** 已出库/已发货商品明细。 */ List<ShipmentLogisticsLineItem> lines) {
        public ShipmentLogisticsRecord { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /** getWaitShips.shipped.list明细。 */
    public record ShipmentLogisticsLineItem(
            /** 明细类型，固定SHIPPED。 */ String lineType,
            /** 发货明细IDships_list_id。 */ String sourceLineId,
            /** 订单明细IDorders_list_id。 */ String orderLineId,
            /** 商品IDgoods_id。 */ String productId,
            /** 规格商品编码options_goods_num。 */ String skuNo,
            /** 买品或赠品。 */ String listType,
            /** 商品编码goods_num。 */ String productCode,
            /** 商品名称goods_name。 */ String productName,
            /** 商品规格goods_options。 */ String specification,
            /** 小单位base_units。 */ String unit,
            /** 大单位container_units。 */ String containerUnit,
            /** 换算关系conversion_number。 */ BigDecimal conversionNumber,
            /** 出库数量ships_number。 */ BigDecimal quantity,
            /** 明细备注remark。 */ String remark,
            /** 明细来源仓库编号，getWaitShips shipped明细未返回时为空。 */ String warehouseNo,
            /** 明细来源仓库名称，getWaitShips shipped明细未返回时为空。 */ String warehouseName) {
    }

    /** getWaitShips.wait_stock明细。 */
    public record WaitStockItem(
            /** 明细类型，固定WAIT_STOCK。 */ String lineType,
            /** 订单明细IDorders_list_id。 */ String sourceLineId,
            /** 商品IDgoods_id。 */ String productId,
            /** 规格商品编码options_goods_num。 */ String skuNo,
            /** 买品或赠品。 */ String listType,
            /** 商品编码goods_num。 */ String productCode,
            /** 商品名称goods_name。 */ String productName,
            /** 商品规格goods_options。 */ String specification,
            /** 小单位base_units。 */ String unit,
            /** 大单位container_units。 */ String containerUnit,
            /** 换算关系conversion_number。 */ BigDecimal conversionNumber,
            /** 仓库编号stock_num。 */ String warehouseNo,
            /** 仓库名称stock_name。 */ String warehouseName,
            /** 订购数量orders_number。 */ BigDecimal orderedQuantity,
            /** 已出库数量stock_number。 */ BigDecimal stockedQuantity,
            /** 实际库存real_number。 */ BigDecimal realStock,
            /** 待出库数量wait_stock_number。 */ BigDecimal waitQuantity,
            /** 明细备注remark。 */ String remark) {
    }

    public record ReturnItem(
            /** 订货宝退货单号 ReturnsSN，本租户内幂等业务键。 */ String returnNo,
            /** 关联订货宝订单号 OrdersNum。 */ String orderNo,
            /** return_audit待审核、shipp_cust待客户发货、shipped待收货、refunded待退款、finished已完成、cancelled已取消。 */ String status,
            /** 退货单经办人 StaffName。 */ String staffName,
            /** 申请退货金额 ReturnsTotal。 */ BigDecimal returnAmount,
            /** 确认结算金额 ReturnsDiscountTotal。 */ BigDecimal settlementAmount,
            /** 来源退货日期 ReturnsDate。 */ Instant returnedAt,
            /** 来源最后更新时间 ReturnsUpdateDate。 */ Instant updatedAt,
            /** 退货原因 ReturnsReason。 */ String reason,
            /** 来源客户编号 ClientNum。 */ String customerNo,
            /** 客户 ERP 外码 ClientGUID。 */ String customerGuid,
            /** 退单收货人 ReturnsConsignee。 */ String consignee,
            /** 退单联系电话，敏感字段。 */ String phone,
            /** 退货地址，敏感字段。 */ String address,
            /** 退货物流公司。 */ String logisticsCompany,
            /** 退货物流单号。 */ String logisticsNo,
            /** 退货类型：0未确认、1退货退款、2仅退款。 */ String returnType,
            /** 退货配送方式。 */ String deliveryMode,
            /** getReturnsContent 商品明细；未补拉详情时为空。 */ List<ReturnLineItem> lines,
            /** 当前来源原始 JSON；含详情时为 list+detail 组合对象。 */ String rawJson,
            /** rawJson 的 SHA-256。 */ String payloadHash,
            /** 是否已调用并包含 getReturnsContent 详情。 */ boolean detailIncluded) {
        public ReturnItem { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record ReturnLineItem(
            /** 退货明细来源ID；来源无ID时由 Integration 生成稳定键。 */ String sourceLineId,
            /** 商品 ERP 外码 Guid/TrueGuid。 */ String sourceProductGuid,
            /** 规格商品编码 OptionsGoodsNum。 */ String skuNo,
            /** 商品编码 Coding。 */ String productCode,
            /** 商品名称 Name。 */ String productName,
            /** 申请退货数量 ReturnsNumber。 */ BigDecimal quantity,
            /** 确认退货数量 ReturnsConfirmNumber。 */ BigDecimal confirmedQuantity,
            /** 申请退货价格 ReturnsPrice。 */ BigDecimal unitPrice,
            /** 确认退货价格 ReturnsConfirmPrice。 */ BigDecimal confirmedPrice,
            /** 退货单位 ReturnsUnitsName。 */ String unit,
            /** 退货仓库编号或 ERP 外码。 */ String warehouseNo,
            /** 退货仓库名称。 */ String warehouseName,
            /** 退货明细备注 ReturnsRemark。 */ String remark) {
    }

    public record FinancialItem(
            /** RECEIPT收款单、PAYMENT付款单。 */ String documentType,
            /** 收款单 ReceiptsNum 或付款单 PaymentNum。 */ String documentNo,
            /** 付款关联收款单等来源关联单号。 */ String relatedDocumentNo,
            /** 关联订货宝订单号 OrdersNum。 */ String orderNo,
            /** 来源客户编号 ClientNum。 */ String customerNo,
            /** 客户 ERP 外码 ClientGuid。 */ String customerGuid,
            /** IncexpId：1普通充值、19预付款充值、13订单收款、8期初充值、2退货退款、10退款失败回冲、9退款红冲。 */ String businessType,
            /** 来源支付方式兼容字段 TypeId；来源未返回时为空。 */ String paymentMethod,
            /** 收款或付款金额 Amount。 */ BigDecimal amount,
            /** pend_receipt待确认、pend_receipted已确认、canceled已取消；来源未返回时为空。 */ String status,
            /** 来源转账日期 ReceiptsDate。 */ Instant transactionAt,
            /** 来源录入时间 CreateDate。 */ Instant createdAt,
            /** 来源更新时间 UpdateDate。 */ Instant updatedAt,
            /** 来源收付款流水号 SerialNumber。 */ String serialNumber,
            /** 来源开户名称 AccountName。 */ String accountName,
            /** 来源开户行 BankName。 */ String bankName,
            /** 来源银行账号 AccountNumber，敏感字段。 */ String accountNumber,
            /** 来源备注 Remark。 */ String remark,
            /** 单条来源原始 JSON，不含认证字段。 */ String rawJson,
            /** rawJson 的 SHA-256。 */ String payloadHash) {
    }
}
