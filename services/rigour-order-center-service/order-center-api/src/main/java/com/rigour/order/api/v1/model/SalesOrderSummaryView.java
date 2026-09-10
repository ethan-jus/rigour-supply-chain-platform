package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 销售订单列表行。 */
public record SalesOrderSummaryView(
        Long id,
        String orderNo,
        String sourceSystemCode,
        String sourceOrderNo,
        String sourceStatusCode,
        String sourceCreatorId,
        String sourceCreatorStaffCode,
        String sourceCreatorName,
        String dataQualityStatusCode,
        String dataQualityMessage,
        Long customerId,
        String customerNameSnapshot,
        String contactPhoneSnapshot,
        String regionCode,
        String regionName,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerEmployeeCode,
        String ownerEmployeeNameSnapshot,
        Instant orderDate,
        Instant paymentTime,
        Instant shipmentTime,
        String shipmentStatusCode,
        String orderStatusCode,
        String paymentStatusCode,
        String outboundStatusCode,
        BigDecimal totalQuantity,
        BigDecimal originalAmount,
        BigDecimal discountAmount,
        BigDecimal payableAmount,
        BigDecimal paidAmount,
        BigDecimal unpaidAmount,
        Integer revision,
        Instant updatedTime) {
    public SalesOrderSummaryView(Long id, String orderNo, String sourceSystemCode, String sourceOrderNo,
                                 String sourceStatusCode, String sourceCreatorId,
                                 String sourceCreatorStaffCode, String sourceCreatorName,
                                 Long customerId, String customerNameSnapshot,
                                 String contactPhoneSnapshot, String regionCode,
                                 String ownerSalesUserId, String ownerSalesName,
                                 String ownerEmployeeCode, String ownerEmployeeNameSnapshot,
                                 Instant orderDate, Instant paymentTime,
                                 Instant shipmentTime, String shipmentStatusCode,
                                 String orderStatusCode, String paymentStatusCode,
                                 String outboundStatusCode, BigDecimal totalQuantity,
                                 BigDecimal payableAmount, BigDecimal paidAmount,
                                 BigDecimal unpaidAmount, Integer revision,
                                 Instant updatedTime) {
        this(id, orderNo, sourceSystemCode, sourceOrderNo, sourceStatusCode,
                sourceCreatorId, sourceCreatorStaffCode, sourceCreatorName,
                null, null, customerId, customerNameSnapshot, contactPhoneSnapshot,
                regionCode, null, ownerSalesUserId, ownerSalesName, ownerEmployeeCode,
                ownerEmployeeNameSnapshot, orderDate, paymentTime, shipmentTime,
                shipmentStatusCode, orderStatusCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, null, null, payableAmount, paidAmount,
                unpaidAmount, revision, updatedTime);
    }

    public SalesOrderSummaryView(Long id, String orderNo, String sourceSystemCode, String sourceOrderNo,
                                 Long customerId, String customerNameSnapshot,
                                 String contactPhoneSnapshot, String regionCode,
                                 String ownerSalesUserId, String ownerSalesName,
                                 String ownerEmployeeCode, String ownerEmployeeNameSnapshot,
                                 Instant orderDate, Instant paymentTime,
                                 Instant shipmentTime, String orderStatusCode,
                                 String paymentStatusCode, String outboundStatusCode,
                                 BigDecimal totalQuantity, BigDecimal payableAmount,
                                 BigDecimal paidAmount, BigDecimal unpaidAmount,
                                 Integer revision, Instant updatedTime) {
        this(id, orderNo, sourceSystemCode, sourceOrderNo, null, null, null, null,
                null, null, customerId,
                customerNameSnapshot, contactPhoneSnapshot, regionCode, null,
                ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                orderDate, paymentTime, shipmentTime, null, orderStatusCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, null, null, payableAmount, paidAmount,
                unpaidAmount, revision, updatedTime);
    }

    public SalesOrderSummaryView(Long id, String orderNo, String sourceSystemCode, String sourceOrderNo,
                                 String sourceStatusCode, Long customerId,
                                 String customerNameSnapshot, String contactPhoneSnapshot,
                                 String regionCode, String ownerSalesUserId,
                                 String ownerSalesName, String ownerEmployeeCode,
                                 String ownerEmployeeNameSnapshot, Instant orderDate,
                                 Instant paymentTime, Instant shipmentTime,
                                 String orderStatusCode, String paymentStatusCode,
                                 String outboundStatusCode, BigDecimal totalQuantity,
                                 BigDecimal payableAmount, BigDecimal paidAmount,
                                 BigDecimal unpaidAmount, Integer revision,
                                 Instant updatedTime) {
        this(id, orderNo, sourceSystemCode, sourceOrderNo, sourceStatusCode, null, null, null,
                null, null, customerId, customerNameSnapshot, contactPhoneSnapshot, regionCode,
                null, ownerSalesUserId, ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                orderDate, paymentTime, shipmentTime, null, orderStatusCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, null, null, payableAmount, paidAmount,
                unpaidAmount, revision, updatedTime);
    }

    public SalesOrderSummaryView(Long id, String orderNo, Long customerId,
                                 String customerNameSnapshot, String contactPhoneSnapshot,
                                 String regionCode, String ownerSalesUserId,
                                 String ownerSalesName, String ownerEmployeeCode,
                                 String ownerEmployeeNameSnapshot, Instant orderDate,
                                 String orderStatusCode, String paymentStatusCode,
                                 String outboundStatusCode, BigDecimal totalQuantity,
                                 BigDecimal payableAmount, BigDecimal paidAmount,
                                 BigDecimal unpaidAmount, Integer revision,
                                 Instant updatedTime) {
        this(id, orderNo, null, null, null, null, null, null, null, null, customerId,
                customerNameSnapshot, contactPhoneSnapshot, regionCode, null,
                ownerSalesUserId, ownerSalesName, ownerEmployeeCode,
                ownerEmployeeNameSnapshot, orderDate, null, null, null, orderStatusCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, null, null, payableAmount, paidAmount,
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
        this(id, orderNo, null, null, null, null, null, null, null, null, customerId,
                customerNameSnapshot, contactPhoneSnapshot, regionCode, null,
                ownerSalesUserId, ownerSalesName, null, null, orderDate,
                null, null, null, orderStatusCode, paymentStatusCode,
                outboundStatusCode, totalQuantity, null, null, payableAmount, paidAmount,
                unpaidAmount, revision, updatedTime);
    }
}
