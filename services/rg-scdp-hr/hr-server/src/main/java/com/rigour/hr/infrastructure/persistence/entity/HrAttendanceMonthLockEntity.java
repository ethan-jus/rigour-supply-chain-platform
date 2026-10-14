package com.rigour.hr.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 考勤持久化实体；复合业务键的查询、更新必须显式限定租户及月份。 */
@TableName("hr_attendance_month_lock")
public class HrAttendanceMonthLockEntity {
    public String tenantId;
    public String attendanceMonth;
}
