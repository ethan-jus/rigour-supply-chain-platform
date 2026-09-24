package com.rigour.integration.application.service.dhb;
import org.springframework.scheduling.annotation.Scheduled;
/** 页面管理的计划是唯一自动入口；默认无计划，不沿用历史全量窗口。 */
public final class DhbSyncOrchestrationScheduler {
    private final DhbScheduleService schedules;
    public DhbSyncOrchestrationScheduler(DhbScheduleService schedules) { this.schedules=schedules; }
    @Scheduled(fixedDelayString="${rigour.integration.dhb.schedule-poll-ms:30000}", initialDelay=30000)
    public void synchronize() { schedules.tick(); }
}
