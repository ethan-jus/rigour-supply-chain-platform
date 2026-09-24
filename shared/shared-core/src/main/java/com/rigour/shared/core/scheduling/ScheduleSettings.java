package com.rigour.shared.core.scheduling;

import java.time.*;

/** 两种业务可读的调度方式；时区固定，避免服务器默认时区改变业务计划。 */
public record ScheduleSettings(
        boolean enabled, String mode, Integer intervalMinutes, String dailyTime) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public ScheduleSettings {
        if (!"FIXED_DELAY".equals(mode) && !"DAILY".equals(mode))
            throw new IllegalArgumentException("请选择完成后间隔或每天定点");
        if ("FIXED_DELAY".equals(mode)) {
            if (intervalMinutes == null || intervalMinutes < 5 || intervalMinutes > 1440)
                throw new IllegalArgumentException("间隔须为5至1440分钟");
            dailyTime = null;
        } else {
            if (dailyTime == null || !dailyTime.matches("([01]\\d|2[0-3]):[0-5]\\d"))
                throw new IllegalArgumentException("执行时间须为HH:mm");
            intervalMinutes = null;
        }
    }

    /** 保存/启用不立即执行；每天定点错过的轮次不集中补跑。 */
    public Instant nextAfter(Instant completedAt) {
        if (!enabled) return null;
        if ("FIXED_DELAY".equals(mode))
            return completedAt.plus(Duration.ofMinutes(intervalMinutes));
        var local = completedAt.atZone(ZONE);
        var next = local.toLocalDate().atTime(LocalTime.parse(dailyTime)).atZone(ZONE);
        if (!next.toInstant().isAfter(completedAt)) next = next.plusDays(1);
        return next.toInstant();
    }
}
