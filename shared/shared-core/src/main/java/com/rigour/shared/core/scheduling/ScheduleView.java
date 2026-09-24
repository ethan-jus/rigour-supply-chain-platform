package com.rigour.shared.core.scheduling;

import java.time.Instant;

/** managed=false 表示仍由原服务配置管理；不能把建议值显示为已保存。 */
public record ScheduleView(
        String key,
        ScheduleSettings settings,
        long version,
        boolean managed,
        Instant nextRunAt,
        Instant lastStartedAt,
        Instant lastFinishedAt,
        String lastStatus,
        String lastMessage,
        String runningJobId) {}
