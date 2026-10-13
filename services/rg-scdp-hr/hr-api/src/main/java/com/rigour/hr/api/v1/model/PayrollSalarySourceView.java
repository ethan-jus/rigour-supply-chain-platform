package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

/** 当前档案薪资来源，随月度核算保存快照；不返回身份证、银行卡等个人资料。 */
public record PayrollSalarySourceView(
        String payrollCategory, Boolean regularized, LocalDate regularizationDate,
        String regularSalary, String probationSalary, LocalDate effectiveFrom) {}
