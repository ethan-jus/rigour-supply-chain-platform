package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.scheduling.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class DhbScheduleService {
    private final SyncScheduleStore schedules;
    private final DhbPageSyncJobStore jobs;
    private final DhbPageSyncJobService worker;
    private final DhbSyncOrchestrationService orchestration;
    private final Clock clock;

    public DhbScheduleService(
            SyncScheduleStore schedules,
            DhbPageSyncJobStore jobs,
            DhbPageSyncJobService worker,
            DhbSyncOrchestrationService orchestration,
            Clock clock) {
        this.schedules = schedules;
        this.jobs = jobs;
        this.worker = worker;
        this.orchestration = orchestration;
        this.clock = clock;
    }

    public ScheduleView get(CallerIdentity actor, UUID connector) {
        require(actor, false);
        orchestration.validateBusinessChain(actor.tenantId(), connector);
        return schedules
                .find(actor.tenantId().toString(), connector.toString())
                .orElseGet(
                        () ->
                                new ScheduleView(
                                        connector.toString(),
                                        new ScheduleSettings(false, "FIXED_DELAY", 60, null),
                                        0,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null));
    }

    public ScheduleView save(CallerIdentity actor, UUID connector, SaveScheduleCommand command) {
        require(actor, true);
        orchestration.validateBusinessChain(actor.tenantId(), connector);
        return schedules.save(
                actor.tenantId().toString(),
                connector.toString(),
                command,
                actor.principalId().toString(),
                clock.instant());
    }

    private static void require(CallerIdentity actor, boolean write) {
        if (actor == null || actor.tenantId() == null || actor.userId() == null)
            throw new AuthorizationDeniedException("tenant-user-caller");
        var required =
                write
                        ? Set.of(
                                "integration:dhb:read", "integration:dhb:write", "hr:employee:sync")
                        : Set.of("integration:dhb:read");
        if (!actor.permissions().contains("*:*:*") && !actor.permissions().containsAll(required))
            throw new AuthorizationDeniedException(
                    write ? "integration:dhb:write + hr:employee:sync" : "integration:dhb:read");
    }

    public void tick() {
        for (var entry : schedules.running()) {
            var p = entry.plan();
            if ("BI_REFRESH".equals(p.key())) continue;
            var job = jobs.find(UUID.fromString(entry.tenant()), UUID.fromString(p.runningJobId()));
            if (job.isEmpty()) {
                if (p.lastStartedAt().isBefore(clock.instant().minusSeconds(120)))
                    schedules.finish(
                            entry.tenant(),
                            p.key(),
                            p.runningJobId(),
                            "UNKNOWN",
                            "任务提交状态待核实，已暂停自动重试",
                            clock.instant());
                continue;
            }
            var j = job.get();
            if (Set.of("QUEUED", "RUNNING").contains(j.status())) {
                if (j.heartbeatAt().isBefore(clock.instant().minusSeconds(90)))
                    schedules.finish(
                            entry.tenant(),
                            p.key(),
                            p.runningJobId(),
                            "UNKNOWN",
                            "任务心跳中断，请核实原任务；不会重复提交",
                            clock.instant());
                continue;
            }
            String status = j.result() == null ? j.status() : j.result().status();
            schedules.finish(
                    entry.tenant(),
                    p.key(),
                    p.runningJobId(),
                    status,
                    j.stage(),
                    j.finishedAt() == null ? clock.instant() : j.finishedAt());
        }
        for (var entry : schedules.due(clock.instant())) {
            var p = entry.plan();
            if ("BI_REFRESH".equals(p.key())) continue;
            var id = UUID.randomUUID();
            if (!schedules.claim(
                    entry.tenant(), p.key(), p.version(), id.toString(), clock.instant())) continue;
            try {
                var job =
                        worker.startScheduled(
                                UUID.fromString(entry.tenant()), id, UUID.fromString(p.key()));
                if (!job.jobId().equals(id))
                    schedules.finish(
                            entry.tenant(),
                            p.key(),
                            id.toString(),
                            "SKIPPED",
                            "连接器已有同步任务，本轮跳过",
                            clock.instant());
            } catch (RuntimeException error) {
                // 提交回执异常时可能已经进入工作线程，不能推断未执行。
                schedules.finish(
                        entry.tenant(),
                        p.key(),
                        id.toString(),
                        "UNKNOWN",
                        "任务提交结果待核实，已暂停自动重试",
                        clock.instant());
            }
        }
    }
}
