package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 一条报销费用的实际用途与证据；行程编号只分组，不另产生一份金额。 */
public record HrExpenseDetails(
        String costCategory,
        String purpose,
        String payeeName,
        String itemName,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        String amountReason,
        String tripReference,
        LocalDate startDate,
        LocalDate endDate,
        String departure,
        String destination,
        String evidenceNo,
        String noEvidenceReason,
        String freightLeg,
        HrExpenseCostReference relatedCost,
        Courier courier) {
    /** 兼容既有费用调用与历史 JSON；快递业务新增信息仅在 COURIER 类型校验。 */
    public HrExpenseDetails(
            String costCategory, String purpose, String payeeName, String itemName,
            BigDecimal quantity, String unit, BigDecimal unitPrice, String amountReason,
            String tripReference, LocalDate startDate, LocalDate endDate, String departure,
            String destination, String evidenceNo, String noEvidenceReason, String freightLeg,
            HrExpenseCostReference relatedCost) {
        this(costCategory, purpose, payeeName, itemName, quantity, unit, unitPrice, amountReason,
                tripReference, startDate, endDate, departure, destination, evidenceNo,
                noEvidenceReason, freightLeg, relatedCost, null);
    }

    /** 非销售寄送的业务凭据，收件对象不同于报销员工和实际收款对象。 */
    public record Courier(String contents, String recipientName, String companyName, String trackingNo) {}
}
