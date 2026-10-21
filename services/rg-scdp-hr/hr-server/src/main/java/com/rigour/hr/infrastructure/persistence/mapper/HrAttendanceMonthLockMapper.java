package com.rigour.hr.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.hr.infrastructure.persistence.entity.HrAttendanceMonthLockEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HrAttendanceMonthLockMapper extends BaseMapper<HrAttendanceMonthLockEntity> {

    @org.apache.ibatis.annotations.Insert(
            "INSERT IGNORE INTO hr_attendance_month_lock(tenant_id,attendance_month)"
                    + " VALUES(#{tenant},#{month})")
    void ensureMonth(
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("month") String month);

    @org.apache.ibatis.annotations.Select(
            "SELECT attendance_month FROM hr_attendance_month_lock WHERE tenant_id=#{tenant} AND"
                    + " attendance_month=#{month} FOR UPDATE")
    String lockMonth(
            @org.apache.ibatis.annotations.Param("tenant") String tenant,
            @org.apache.ibatis.annotations.Param("month") String month);
}
