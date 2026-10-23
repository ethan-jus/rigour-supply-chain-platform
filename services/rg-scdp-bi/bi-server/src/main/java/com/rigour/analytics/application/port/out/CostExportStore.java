package com.rigour.analytics.application.port.out;

import java.time.Instant;

public interface CostExportStore {
    record Task(
            String id,
            String tenant,
            String actor,
            String snapshot,
            String authorizationHash,
            String filterJson,
            String status,
            String error,
            String content,
            Long count,
            Instant createdAt) {}

    void create(Task task);

    Task find(String tenant, String actor, String id);

    boolean start(String tenant, String id);

    void finish(String tenant, String id, String content, long count);

    void fail(String tenant, String id, String error);

    void cancel(String tenant, String actor, String id);

    void expire();
}
