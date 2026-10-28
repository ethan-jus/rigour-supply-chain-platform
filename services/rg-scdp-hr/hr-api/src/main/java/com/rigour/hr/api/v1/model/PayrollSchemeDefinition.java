package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollSchemeDefinition(
        String name,
        String role,
        List<Long> departmentIds,
        List<String> employeeCodes,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        PayrollSchemeBaseSalary baseSalary,
        BigDecimal performanceBase,
        String performanceMode,
        PayrollSalesPolicy sales,
        PayrollManagerPolicy manager,
        PayrollWorkCalendar calendar,
        PayrollProrationPolicy proration,
        List<PayrollSchemeAllowance> allowances,
        PayrollSchemeCommissionPolicy commission,
        String remark,
        PayrollRuleReferences ruleReferences,
        PayrollPersonalPolicy personalPolicy) {
    public PayrollSchemeDefinition(
            String name,
            String role,
            List<Long> departmentIds,
            List<String> employeeCodes,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            PayrollSchemeBaseSalary baseSalary,
            BigDecimal performanceBase,
            String performanceMode,
            PayrollSalesPolicy sales,
            PayrollManagerPolicy manager,
            PayrollWorkCalendar calendar,
            PayrollProrationPolicy proration,
            List<PayrollSchemeAllowance> allowances,
            PayrollSchemeCommissionPolicy commission,
            String remark,
            PayrollRuleReferences ruleReferences) {
        this(
                name,
                role,
                departmentIds,
                employeeCodes,
                effectiveFrom,
                effectiveTo,
                baseSalary,
                performanceBase,
                performanceMode,
                sales,
                manager,
                calendar,
                proration,
                allowances,
                commission,
                remark,
                ruleReferences,
                null);
    }

    /** Historical payroll snapshots predate component references. */
    public PayrollSchemeDefinition(
            String name,
            String role,
            List<Long> departmentIds,
            List<String> employeeCodes,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            PayrollSchemeBaseSalary baseSalary,
            BigDecimal performanceBase,
            String performanceMode,
            PayrollSalesPolicy sales,
            PayrollManagerPolicy manager,
            PayrollWorkCalendar calendar,
            PayrollProrationPolicy proration,
            List<PayrollSchemeAllowance> allowances,
            PayrollSchemeCommissionPolicy commission,
            String remark) {
        this(
                name,
                role,
                departmentIds,
                employeeCodes,
                effectiveFrom,
                effectiveTo,
                baseSalary,
                performanceBase,
                performanceMode,
                sales,
                manager,
                calendar,
                proration,
                allowances,
                commission,
                remark,
                null);
    }

    public PayrollSchemeDefinition withProration(PayrollProrationPolicy value) {
        return new PayrollSchemeDefinition(
                name,
                role,
                departmentIds,
                employeeCodes,
                effectiveFrom,
                effectiveTo,
                baseSalary,
                performanceBase,
                performanceMode,
                sales,
                manager,
                calendar,
                value,
                allowances,
                commission,
                remark,
                ruleReferences,
                personalPolicy);
    }

    public PayrollSchemeDefinition withCalendar(PayrollWorkCalendar value, String versionId) {
        var refs =
                ruleReferences == null
                        ? null
                        : new PayrollRuleReferences(
                                ruleReferences.salaryId(),
                                ruleReferences.performanceId(),
                                ruleReferences.commissionId(),
                                versionId,
                                ruleReferences.allowanceId());
        return new PayrollSchemeDefinition(
                name,
                role,
                departmentIds,
                employeeCodes,
                effectiveFrom,
                effectiveTo,
                baseSalary,
                performanceBase,
                performanceMode,
                sales,
                manager,
                value,
                proration,
                allowances,
                commission,
                remark,
                refs,
                personalPolicy);
    }
}
