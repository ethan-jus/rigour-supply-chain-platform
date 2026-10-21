package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 考勤持久化实体；复合业务键的查询、更新必须显式限定租户及月份。 */
@TableName("hr_attendance_audit")
public class HrAttendanceAuditEntity {
    @TableId(type = IdType.AUTO)
    public Long id;

    public String tenantId;
    public String attendanceMonth;
    public String employeeCode;
    public Long departmentId;
    public String actionCode;
    public Integer expectedDays;
    public String reason;
    public Integer revision;
    public String actorId;
    public java.time.LocalDateTime createdTime;
    public java.math.BigDecimal actualDaysOverride;
    public java.math.BigDecimal absentDaysOverride;
    public String leaveTypeCode;
    public String confirmedSnapshotJson;
}
