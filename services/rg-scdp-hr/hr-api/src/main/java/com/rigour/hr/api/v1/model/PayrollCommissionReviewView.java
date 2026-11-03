package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 只读补算差异，未解决项不能变成零，原金额为空时不计算伪差额。 */
public record PayrollCommissionReviewView(String payrollId, int payrollRevision, String month,
        String employeeCode, String employeeName, String sourceFingerprint,
        BigDecimal savedCommission, BigDecimal previewCommission, BigDecimal difference,
        BigDecimal resolvedSubtotal, List<Line> lines, List<String> issues) {
    public record Line(String sourceLineId, String recipientRole, String orderNo, String productName,
            String unitCode, BigDecimal quantity, BigDecimal savedAmount, BigDecimal previewAmount,
            String ruleName, String basis) {}
}
