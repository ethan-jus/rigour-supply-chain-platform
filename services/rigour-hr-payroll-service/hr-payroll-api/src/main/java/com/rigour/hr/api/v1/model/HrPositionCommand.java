package com.rigour.hr.api.v1.model;

/** HR 岗位/职位维护命令。 */
public record HrPositionCommand(
        String positionName,
        String positionType,
        String statusCode,
        String sourceSystem,
        String remark,
        Integer revision) {
}
