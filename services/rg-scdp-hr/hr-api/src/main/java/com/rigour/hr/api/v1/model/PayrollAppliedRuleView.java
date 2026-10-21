package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

/** 工资生成时命中的不可变规则版本，历史工资不读取当前规则替换此依据。 */
public record PayrollAppliedRuleView(
        String kind,
        String versionId,
        String ruleCode,
        String name,
        int version,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
