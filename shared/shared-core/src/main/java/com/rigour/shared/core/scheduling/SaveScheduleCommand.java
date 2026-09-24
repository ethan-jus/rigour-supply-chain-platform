package com.rigour.shared.core.scheduling;

public record SaveScheduleCommand(ScheduleSettings settings, long expectedVersion) {
    public SaveScheduleCommand {
        if (settings == null || expectedVersion < 0)
            throw new IllegalArgumentException("配置及版本不能为空");
    }
}
