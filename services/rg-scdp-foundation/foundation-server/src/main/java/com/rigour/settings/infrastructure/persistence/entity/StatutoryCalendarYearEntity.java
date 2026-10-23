package com.rigour.settings.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("settings_statutory_calendar_year")
public class StatutoryCalendarYearEntity {
    @TableId(type = IdType.INPUT)
    public String id;

    public String tenantId;
    public Integer calendarYear;
    public String status;
    public String contentJson;
    public String contentVersion;
    public LocalDateTime fetchedAt;
    public LocalDateTime attemptedAt;
    public String leaseToken;
    public LocalDateTime leaseUntil;
}
