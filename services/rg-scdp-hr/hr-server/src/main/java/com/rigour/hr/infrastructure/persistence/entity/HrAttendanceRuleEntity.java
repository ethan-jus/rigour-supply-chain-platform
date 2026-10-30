package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 考勤持久化实体；复合业务键的查询、更新必须显式限定租户及月份。 */
@TableName("hr_attendance_rule")
public class HrAttendanceRuleEntity {
    public String tenantId;
    public String attendanceMonth;
    public Long departmentId;
    public Integer expectedDays;
    public Integer revision;
    public String createdBy;
    public String createdByName;
    public java.time.LocalDateTime createdTime;
    public String updatedBy;
    public java.time.LocalDateTime updatedTime;
}
