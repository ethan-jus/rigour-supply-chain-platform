package com.rigour.hr.api.v1.model;

import java.time.Instant;

/** HR 岗位职位视图；用于页面展示和员工主档职位编码引用。 */
public record HrPositionView(
        Long id,
        String positionCode,
        String positionName,
        String positionType,
        String statusCode,
        String sourceSystem,
        String remark,
        Integer revision,
        String createdBy,
        Instant createdTime,
        String updatedBy,
        Instant updatedTime) {
}
