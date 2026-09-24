package com.rigour.integration.application.port.out;

import com.rigour.shared.core.scheduling.*;

import java.time.Instant;
import java.util.*;

public interface SyncScheduleStore {
    record Entry(String tenant, ScheduleView plan, Instant heartbeatAt) {}

    Optional<ScheduleView> find(String tenant, String key);

    ScheduleView save(
            String tenant, String key, SaveScheduleCommand command, String actor, Instant now);

    List<Entry> due(Instant now);

    List<Entry> running();

    boolean claim(String tenant, String key, long version, String job, Instant now);

    void heartbeat(String tenant, String key, String job, Instant now);

    void finish(String tenant, String key, String job, String status, String message, Instant now);
}
