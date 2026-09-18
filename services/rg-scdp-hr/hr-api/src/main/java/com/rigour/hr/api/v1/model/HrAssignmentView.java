package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** 保留每次主部门和岗位任职，不随当前主档覆盖。 */
public record HrAssignmentView(
        Long id,
        Long departmentId,
        String departmentName,
        String positionCode,
        String positionName,
        Instant effectiveFrom,
        Instant effectiveTo) {}
