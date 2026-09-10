package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售订单保存命令；订单号由后端生成，修改通过 revision 做乐观锁保护。 */
public record SalesOrderCommand(
        Long customerId,
        String sourceSystemCode,
        String sourceOrderNo,
        String sourceStatusCode,
        String sourceCreatorId,
        String sourceCreatorStaffCode,
        String sourceCreatorName,
        String customerCodeSnapshot,
        String customerNameSnapshot,
        String contactNameSnapshot,
        String contactPhoneSnapshot,
        String regionCode,
        String ownerSalesUserId,
        String ownerSalesName,
        String ownerEmployeeCode,
        String ownerEmployeeNameSnapshot,
        Instant orderDate,
        String orderTypeCode,
        String paymentMethodCode,
        List<String> paymentVoucherKeys,
        BigDecimal sourceUnpaidAmount,
        BigDecimal discountRate,
        BigDecimal discountAmount,
        String remark,
        List<SalesOrderLineCommand> lines,
        Boolean submit,
        Integer revision) {
    public SalesOrderCommand {
        paymentVoucherKeys = paymentVoucherKeys == null ? List.of() : List.copyOf(paymentVoucherKeys);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public SalesOrderCommand(Long customerId, String sourceSystemCode, String sourceOrderNo,
                             String sourceStatusCode, String sourceCreatorId,
                             String sourceCreatorStaffCode, String sourceCreatorName,
                             String customerCodeSnapshot, String customerNameSnapshot,
                             String contactNameSnapshot, String contactPhoneSnapshot,
                             String regionCode, String ownerSalesUserId,
                             String ownerSalesName, String ownerEmployeeCode,
                             String ownerEmployeeNameSnapshot, Instant orderDate,
                             String orderTypeCode, String paymentMethodCode,
                             List<String> paymentVoucherKeys,
                             BigDecimal discountRate, BigDecimal discountAmount,
                             String remark, List<SalesOrderLineCommand> lines,
                             Boolean submit, Integer revision) {
        this(customerId, sourceSystemCode, sourceOrderNo, sourceStatusCode,
                sourceCreatorId, sourceCreatorStaffCode, sourceCreatorName,
                customerCodeSnapshot, customerNameSnapshot, contactNameSnapshot,
                contactPhoneSnapshot, regionCode, ownerSalesUserId,
                ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                orderDate, orderTypeCode, paymentMethodCode, paymentVoucherKeys,
                null, discountRate, discountAmount, remark, lines, submit, revision);
    }

    public SalesOrderCommand(Long customerId, String sourceSystemCode, String sourceOrderNo,
                             String sourceStatusCode, String sourceCreatorId,
                             String sourceCreatorStaffCode, String sourceCreatorName,
                             String customerCodeSnapshot, String customerNameSnapshot,
                             String contactNameSnapshot, String contactPhoneSnapshot,
                             String regionCode, String ownerSalesUserId,
                             String ownerSalesName, String ownerEmployeeCode,
                             String ownerEmployeeNameSnapshot, Instant orderDate,
                             String orderTypeCode, String paymentMethodCode,
                             BigDecimal discountRate, BigDecimal discountAmount,
                             String remark, List<SalesOrderLineCommand> lines,
                             Boolean submit, Integer revision) {
        this(customerId, sourceSystemCode, sourceOrderNo, sourceStatusCode,
                sourceCreatorId, sourceCreatorStaffCode, sourceCreatorName,
                customerCodeSnapshot, customerNameSnapshot, contactNameSnapshot,
                contactPhoneSnapshot, regionCode, ownerSalesUserId,
                ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                orderDate, orderTypeCode, paymentMethodCode, List.of(),
                null, discountRate, discountAmount, remark, lines, submit, revision);
    }

    public SalesOrderCommand(Long customerId, String sourceSystemCode, String sourceOrderNo,
                             String customerCodeSnapshot, String customerNameSnapshot,
                             String contactNameSnapshot, String contactPhoneSnapshot,
                             String regionCode, String ownerSalesUserId,
                             String ownerSalesName, String ownerEmployeeCode,
                             String ownerEmployeeNameSnapshot, Instant orderDate,
                             String orderTypeCode, String paymentMethodCode,
                             BigDecimal discountRate, BigDecimal discountAmount,
                             String remark, List<SalesOrderLineCommand> lines,
                             Boolean submit, Integer revision) {
        this(customerId, sourceSystemCode, sourceOrderNo, null, null, null, null,
                customerCodeSnapshot, customerNameSnapshot, contactNameSnapshot,
                contactPhoneSnapshot, regionCode, ownerSalesUserId, ownerSalesName,
                ownerEmployeeCode, ownerEmployeeNameSnapshot, orderDate, orderTypeCode,
                paymentMethodCode, List.of(), null, discountRate, discountAmount, remark, lines,
                submit, revision);
    }

    public SalesOrderCommand(Long customerId, String customerCodeSnapshot,
                             String customerNameSnapshot, String contactNameSnapshot,
                             String contactPhoneSnapshot, String regionCode,
                             String ownerSalesUserId, String ownerSalesName,
                             String ownerEmployeeCode, String ownerEmployeeNameSnapshot,
                             Instant orderDate, String orderTypeCode,
                             String paymentMethodCode, BigDecimal discountRate,
                             BigDecimal discountAmount, String remark,
                             List<SalesOrderLineCommand> lines, Boolean submit,
                             Integer revision) {
        this(customerId, null, null, null, null, null, null, customerCodeSnapshot,
                customerNameSnapshot, contactNameSnapshot, contactPhoneSnapshot,
                regionCode, ownerSalesUserId, ownerSalesName, ownerEmployeeCode,
                ownerEmployeeNameSnapshot, orderDate, orderTypeCode, paymentMethodCode,
                List.of(), null, discountRate, discountAmount, remark, lines, submit, revision);
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
        this(customerId, null, null, null, null, null, null, customerCodeSnapshot,
                customerNameSnapshot, contactNameSnapshot, contactPhoneSnapshot,
                regionCode, ownerSalesUserId, ownerSalesName, null, null,
                orderDate, orderTypeCode, paymentMethodCode, List.of(), null, discountRate,
                discountAmount, remark, lines, submit, revision);
    }
}
