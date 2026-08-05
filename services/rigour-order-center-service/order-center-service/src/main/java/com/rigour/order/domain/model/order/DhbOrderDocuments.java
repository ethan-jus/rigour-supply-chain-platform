package com.rigour.order.domain.model.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 订单中心保存的订货宝发货、退货和收付款只读来源事实。 */
public final class DhbOrderDocuments {
    private DhbOrderDocuments() {
    }

    /**
     * 独立发货单来源事实。
     * status保留订货宝原值：shipped待发货、receivedin待收货、received已收货、cancelled已取消。
     */
    public record Shipment(
            String id, String tenantId, String sourceShipmentId, String shipmentNo, String orderNo,
            String status, String statusName, String typeId, String typeName, String customerNo,
            String customerName, String customerGuid, String warehouseNo, String warehouseName,
            String warehouseGuid, LocalDateTime shipmentAt, String logisticsName, String trackingNo,
            String remark, LocalDateTime sourceCreatedAt, LocalDateTime sourceUpdatedAt,
            String payloadHash, boolean detailAvailable, LocalDateTime syncedAt) {
    }

    /** 发货单明细；sourceLineId在同一发货单内唯一。 */
    public record ShipmentLine(
            String id, String sourceLineId, String sourceProductGuid, String skuNo, String productCode,
            String productName, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
            String unit, String warehouseNo, String remark) {
    }

    /** 发货单聚合查询结果。 */
    public record ShipmentDetail(Shipment shipment, List<ShipmentLine> lines) {
        public ShipmentDetail { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /**
     * 退货单来源事实。
     * status保留订货宝原值：return_audit待审核、shipp_cust待客户发货、shipped待收货、
     * refunded待退款、finished已完成、cancelled已取消。
     */
    public record ReturnDocument(
            String id, String tenantId, String returnNo, String orderNo, String status, String staffName,
            BigDecimal returnAmount, BigDecimal settlementAmount, LocalDateTime returnedAt,
            LocalDateTime sourceUpdatedAt, String reason, String customerNo, String customerGuid,
            String consignee, String phone, String address, String logisticsCompany, String logisticsNo,
            String returnType, String deliveryMode, String payloadHash, boolean detailAvailable,
            LocalDateTime syncedAt) {
    }

    /** 退货单明细；数量和确认数量均沿用订货宝小单位语义。 */
    public record ReturnLine(
            String id, String sourceLineId, String sourceProductGuid, String skuNo, String productCode,
            String productName, BigDecimal quantity, BigDecimal confirmedQuantity, BigDecimal unitPrice,
            BigDecimal confirmedPrice, String unit, String warehouseNo, String warehouseName, String remark) {
    }

    /** 退货单聚合查询结果。 */
    public record ReturnDetail(ReturnDocument returnDocument, List<ReturnLine> lines) {
        public ReturnDetail { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /**
     * 收付款来源事实。documentType为RECEIPT收款单或PAYMENT付款单；status保留来源原值：
     * pend_receipt待确认、pend_receipted已确认、canceled已取消。
     */
    public record FinancialDocument(
            String id, String tenantId, String documentType, String documentNo,
            String relatedDocumentNo, String orderNo, String customerNo, String customerGuid,
            String businessType, String paymentMethod, BigDecimal amount, String status,
            LocalDateTime transactionAt, LocalDateTime sourceCreatedAt, LocalDateTime sourceUpdatedAt,
            String serialNumber, String accountName, String bankName, String accountNumber,
            String remark, String payloadHash, LocalDateTime syncedAt) {
    }
}
