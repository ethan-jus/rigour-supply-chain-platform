package com.rigour.hr.domain.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 销售档案只读取明确数值底薪；月内变化需 HR 人工核定，不擅自按天折算。 */
public final class EmployeeSalaryResolver {
    private EmployeeSalaryResolver() {}

    public static BigDecimal resolve(YearMonth month, Boolean regularized, LocalDate regularizationDate,
            LocalDate effectiveFrom, String regularSalary, String probationSalary, List<String> issues) {
        if (regularized == null) {
            issues.add("员工档案未填写是否转正，请完善档案后重新核算");
            return null;
        }
        if (effectiveFrom == null || effectiveFrom.isAfter(month.atDay(1))) {
            issues.add("档案薪资生效日期缺失、晚于核算月份或月内调薪，请核对当月底薪");
            return null;
        }
        if (regularized && regularizationDate == null) {
            issues.add("员工档案缺少转正日期，请核对当月底薪");
            return null;
        }
        if (regularized && regularizationDate.isAfter(month.atDay(1))
                && !regularizationDate.isAfter(month.atEndOfMonth())) {
            issues.add("当月中途转正，请在月度调整中核定当月底薪并填写原因");
            return null;
        }
        boolean regular = regularized && !regularizationDate.isAfter(month.atDay(1));
        String value = regular ? regularSalary : probationSalary;
        if (value == null || !value.trim().matches("\\d{1,14}(\\.\\d{1,2})?")) {
            issues.add("员工档案" + (regular ? "转正" : "试用期") + "底薪缺失或不是明确金额，请完善档案");
            return null;
        }
        return new BigDecimal(value.trim()).setScale(2);
    }
}
