package com.rigour.settings.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("settings_statutory_calendar_history")
public class StatutoryCalendarHistoryEntity {
    @TableId(type = IdType.INPUT)
    public String id;

    public String tenantId;
    public Integer calendarYear;
    public String contentJson;
    public String contentVersion;
    public LocalDateTime fetchedAt;
}
