package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售订单详情。 */
public record SalesOrderDetailView(
        Long id,
        String orderNo,
        String sourceSystemCode,
        String sourceOrderNo,
        Long customerId,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String contactNameSnapshot,
        String contactPhoneSnapshot,
        String regionCode,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerStaffCode,
        String ownerStaffNameSnapshot,
        Instant orderDate,
        String orderStatusCode,
        String orderTypeCode,
        String paymentMethodCode,
        String paymentStatusCode,
        String outboundStatusCode,
        BigDecimal totalQuantity,
        BigDecimal originalAmount,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime,
        List<SalesOrderLineView> lines) {
    public SalesOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public SalesOrderDetailView(Long id, String orderNo, Long customerId,
                                String customerCodeSnapshot, String customerNameSnapshot,
                                String contactNameSnapshot, String contactPhoneSnapshot,
                                String regionCode, String ownerSalesUserId,
                                String ownerSalesName, String ownerStaffCode,
                                String ownerStaffNameSnapshot, Instant orderDate,
                                String orderStatusCode, String orderTypeCode,
                                String paymentMethodCode, String paymentStatusCode,
                                String outboundStatusCode, BigDecimal totalQuantity,
                                BigDecimal originalAmount, BigDecimal discountRate,
                                BigDecimal discountAmount, BigDecimal payableAmount,
                                BigDecimal paidAmount, BigDecimal unpaidAmount,
                                String remark, Integer revision, String createdBy,
                                Instant createdTime, String updatedBy, Instant updatedTime,
                                List<SalesOrderLineView> lines) {
        this(id, orderNo, null, null, customerId, customerCodeSnapshot, customerNameSnapshot,
                contactNameSnapshot, contactPhoneSnapshot, regionCode, ownerSalesUserId,
                ownerSalesName, ownerStaffCode, ownerStaffNameSnapshot, orderDate,
                orderStatusCode, orderTypeCode, paymentMethodCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, originalAmount, discountRate,
                discountAmount, payableAmount, paidAmount, unpaidAmount, remark,
                revision, createdBy, createdTime, updatedBy, updatedTime, lines);
    }

    public SalesOrderDetailView(Long id, String orderNo, Long customerId,
                                String customerCodeSnapshot, String customerNameSnapshot,
                                String contactNameSnapshot, String contactPhoneSnapshot,
                                String regionCode, String ownerSalesUserId,
                                String ownerSalesName, Instant orderDate,
                                String orderStatusCode, String orderTypeCode,
                                String paymentMethodCode, String paymentStatusCode,
                                String outboundStatusCode, BigDecimal totalQuantity,
                                BigDecimal originalAmount, BigDecimal discountRate,
                                BigDecimal discountAmount, BigDecimal payableAmount,
                                BigDecimal paidAmount, BigDecimal unpaidAmount,
                                String remark, Integer revision, String createdBy,
                                Instant createdTime, String updatedBy, Instant updatedTime,
                                List<SalesOrderLineView> lines) {
        this(id, orderNo, null, null, customerId, customerCodeSnapshot, customerNameSnapshot,
                contactNameSnapshot, contactPhoneSnapshot, regionCode, ownerSalesUserId,
                ownerSalesName, null, null, orderDate, orderStatusCode, orderTypeCode,
                paymentMethodCode, paymentStatusCode, outboundStatusCode, totalQuantity,
                originalAmount, discountRate, discountAmount, payableAmount, paidAmount,
                unpaidAmount, remark, revision, createdBy, createdTime, updatedBy,
                updatedTime, lines);
    }
}
