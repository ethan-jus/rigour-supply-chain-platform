package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 销售订单列表行。 */
public record SalesOrderSummaryView(
        Long id,
        String orderNo,
        String sourceSystemCode,
        String sourceOrderNo,
        Long customerId,
        String customerNameSnapshot,
        String contactPhoneSnapshot,
        String regionCode,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerStaffCode,
        String ownerStaffNameSnapshot,
        Instant orderDate,
        String orderStatusCode,
        String paymentStatusCode,
        String outboundStatusCode,
        BigDecimal totalQuantity,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        Integer revision,
        Instant updatedTime) {
    public SalesOrderSummaryView(Long id, String orderNo, Long customerId,
                                 String customerNameSnapshot, String contactPhoneSnapshot,
                                 String regionCode, String ownerSalesUserId,
                                 String ownerSalesName, String ownerStaffCode,
                                 String ownerStaffNameSnapshot, Instant orderDate,
                                 String orderStatusCode, String paymentStatusCode,
                                 String outboundStatusCode, BigDecimal totalQuantity,
                                 BigDecimal payableAmount, BigDecimal paidAmount,
                                 BigDecimal unpaidAmount, Integer revision,
                                 Instant updatedTime) {
        this(id, orderNo, null, null, customerId, customerNameSnapshot, contactPhoneSnapshot,
                regionCode, ownerSalesUserId, ownerSalesName, ownerStaffCode,
                ownerStaffNameSnapshot, orderDate, orderStatusCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, payableAmount, paidAmount,
                unpaidAmount, revision, updatedTime);
    }

    public SalesOrderSummaryView(Long id, String orderNo, Long customerId,
                                 String customerNameSnapshot, String contactPhoneSnapshot,
                                 String regionCode, String ownerSalesUserId,
                                 String ownerSalesName, Instant orderDate,
                                 String orderStatusCode, String paymentStatusCode,
                                 String outboundStatusCode, BigDecimal totalQuantity,
                                 BigDecimal payableAmount, BigDecimal paidAmount,
                                 BigDecimal unpaidAmount, Integer revision,
                                 Instant updatedTime) {
        this(id, orderNo, null, null, customerId, customerNameSnapshot, contactPhoneSnapshot,
                regionCode, ownerSalesUserId, ownerSalesName, null, null, orderDate,
                orderStatusCode, paymentStatusCode, outboundStatusCode, totalQuantity,
                payableAmount, paidAmount, unpaidAmount, revision, updatedTime);
    }
}
