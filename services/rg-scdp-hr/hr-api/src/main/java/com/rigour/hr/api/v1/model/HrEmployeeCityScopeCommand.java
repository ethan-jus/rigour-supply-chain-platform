package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

/** 与员工资料一并提交的管辖范围增量；省略的历史记录保持不变。 */
public record HrEmployeeCityScopeCommand(
        String id, Integer revision, String requestId, String cityCode, String cityName,
        LocalDate effectiveFrom, LocalDate effectiveTo, boolean enabled, String remark) {}
