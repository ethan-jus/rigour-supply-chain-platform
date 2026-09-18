package com.rigour.hr.api.v1.model;

/** HR 岗位维护命令。 */
public record HrPositionCommand(
        String positionName,
        String statusCode,
        String remark,
        Integer revision,
        String positionCode,
        Integer sortOrder) {
}
