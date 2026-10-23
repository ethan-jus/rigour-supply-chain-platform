package com.rigour.hr.api.v1.model;

import java.time.LocalDateTime;

/** 本地业务时间使用 Asia/Shanghai，结束时间不包含在区间内；出勤及缺勤天数由 HR 核定。 */
public record AttendanceExceptionPeriod(
        LocalDateTime start, LocalDateTime end, String kind, String reason) {}
