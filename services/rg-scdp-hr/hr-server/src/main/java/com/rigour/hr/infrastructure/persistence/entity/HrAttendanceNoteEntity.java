package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 考勤持久化实体；复合业务键的查询、更新必须显式限定租户及月份。 */
@TableName("hr_attendance_note")
public class HrAttendanceNoteEntity {
    public String tenantId;
    public String attendanceMonth;
    public String employeeCode;
    public String reason;
    public Integer revision;
    public String updatedBy;
    public java.time.LocalDateTime updatedTime;
    public java.math.BigDecimal actualDaysOverride;
    public java.math.BigDecimal absentDaysOverride;
    public String leaveTypeCode;
    public String attendanceStatus;
    public String confirmedSnapshotJson;
    public Long confirmedDepartmentId;
    public Long confirmedRuleDepartmentId;
}
