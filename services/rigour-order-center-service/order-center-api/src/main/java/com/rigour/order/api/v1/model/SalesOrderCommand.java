package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售订单保存命令；订单号由后端生成，修改通过 revision 做乐观锁保护。 */
public record SalesOrderCommand(
        Long customerId,
        String sourceSystemCode,
        String sourceOrderNo,
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
        String orderTypeCode,
        String paymentMethodCode,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        String remark,
        List<SalesOrderLineCommand> lines,
        Boolean submit,
        Integer revision) {
    public SalesOrderCommand(Long customerId, String customerCodeSnapshot,
                             String customerNameSnapshot, String contactNameSnapshot,
                             String contactPhoneSnapshot, String regionCode,
                             String ownerSalesUserId, String ownerSalesName,
                             String ownerStaffCode, String ownerStaffNameSnapshot,
                             Instant orderDate, String orderTypeCode,
                             String paymentMethodCode, BigDecimal discountRate,
                             BigDecimal discountAmount, String remark,
                             List<SalesOrderLineCommand> lines, Boolean submit,
                             Integer revision) {
        this(customerId, null, null, customerCodeSnapshot, customerNameSnapshot,
                contactNameSnapshot, contactPhoneSnapshot, regionCode, ownerSalesUserId,
                ownerSalesName, ownerStaffCode, ownerStaffNameSnapshot, orderDate,
                orderTypeCode, paymentMethodCode, discountRate, discountAmount,
                remark, lines, submit, revision);
    }

    public SalesOrderCommand(Long customerId, String customerCodeSnapshot,
                             String customerNameSnapshot, String contactNameSnapshot,
                             String contactPhoneSnapshot, String regionCode,
                             String ownerSalesUserId, String ownerSalesName,
                             Instant orderDate, String orderTypeCode,
                             String paymentMethodCode, BigDecimal discountRate,
                             BigDecimal discountAmount, String remark,
                             List<SalesOrderLineCommand> lines, Boolean submit,
                             Integer revision) {
        this(customerId, null, null, customerCodeSnapshot, customerNameSnapshot, contactNameSnapshot,
                contactPhoneSnapshot, regionCode, ownerSalesUserId, ownerSalesName,
                null, null, orderDate, orderTypeCode, paymentMethodCode,
                discountRate, discountAmount, remark, lines, submit, revision);
    }
}
