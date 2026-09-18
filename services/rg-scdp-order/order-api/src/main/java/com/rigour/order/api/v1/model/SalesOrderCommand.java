package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 销售订单保存命令；订单号默认由后端生成，外部来源可通过 businessOrderNoOverride 指定真实来源单号。 */
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
        Integer revision,
        String businessOrderNoOverride,
        Instant sourceCreatedAt,
        Instant sourceUpdatedAt,
        String sourceModifierId,
        String sourceModifierName,
        String syncedBy,
        Instant syncedAt) {
    public SalesOrderCommand {
        paymentVoucherKeys = paymentVoucherKeys == null ? List.of() : List.copyOf(paymentVoucherKeys);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** 兼容旧调用：不传业务单号覆盖与来源审计字段时，行为与既有同步一致。 */
    public SalesOrderCommand(Long customerId, String sourceSystemCode, String sourceOrderNo,
                             String sourceStatusCode, String sourceCreatorId,
                             String sourceCreatorStaffCode, String sourceCreatorName,
                             String customerCodeSnapshot, String customerNameSnapshot,
                             String contactNameSnapshot, String contactPhoneSnapshot,
                             String regionCode, String ownerSalesUserId,
                             String ownerSalesName, String ownerEmployeeCode,
                             String ownerEmployeeNameSnapshot, Instant orderDate,
                             String orderTypeCode, String paymentMethodCode,
                             List<String> paymentVoucherKeys, BigDecimal sourceUnpaidAmount,
                             BigDecimal discountRate, BigDecimal discountAmount,
                             String remark, List<SalesOrderLineCommand> lines,
                             Boolean submit, Integer revision) {
        this(customerId, sourceSystemCode, sourceOrderNo, sourceStatusCode,
                sourceCreatorId, sourceCreatorStaffCode, sourceCreatorName,
                customerCodeSnapshot, customerNameSnapshot, contactNameSnapshot,
                contactPhoneSnapshot, regionCode, ownerSalesUserId,
                ownerSalesName, ownerEmployeeCode, ownerEmployeeNameSnapshot,
                orderDate, orderTypeCode, paymentMethodCode, paymentVoucherKeys,
                sourceUnpaidAmount, discountRate, discountAmount, remark, lines,
                submit, revision, null, null, null, null, null, null, null);
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
                null, discountRate, discountAmount, remark, lines, submit, revision,
                null, null, null, null, null, null, null);
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
                null, discountRate, discountAmount, remark, lines, submit, revision,
                null, null, null, null, null, null, null);
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
                submit, revision, null, null, null, null, null, null, null);
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
                List.of(), null, discountRate, discountAmount, remark, lines, submit, revision,
                null, null, null, null, null, null, null);
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
                discountAmount, remark, lines, submit, revision,
                null, null, null, null, null, null, null);
    }
}
