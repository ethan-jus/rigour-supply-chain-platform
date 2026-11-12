package com.rigour.integration.application.port.out.thirdparty.huolala;

import com.rigour.integration.api.v1.model.HuolalaModels.*;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.*;

public interface HuolalaStore {
    record Stored(Connection connection, String leaseId) {}

    Optional<Connection> connection(UUID tenant);

    Connection save(UUID tenant, UUID actor, SaveConnection command);

    boolean claim(UUID tenant, String runId, Instant now);

    void heartbeat(UUID tenant, String runId, Instant now);

    void finish(UUID tenant, String runId, String error, Instant now);

    List<UUID> due(Instant now);

    UUID startRun(UUID tenant, UUID actor, String scope, String trigger, Instant now);

    void finishRun(
            UUID tenant,
            UUID runId,
            long fetched,
            long accepted,
            long unchanged,
            long rejected,
            String error,
            Instant now);

    void progress(
            UUID tenant,
            UUID runId,
            long fetched,
            long accepted,
            long unchanged,
            long rejected,
            java.time.LocalDate from,
            java.time.LocalDate to,
            int page);

    default void stages(UUID tenant, UUID run, StageCounts stages) {}

    List<Run> runs(UUID tenant);

    List<SyncIssue> issues(UUID tenant, UUID run);

    boolean historyDue(UUID tenant, java.time.LocalDate today);

    String raw(
            UUID tenant, UUID connector, UUID run, String type, String sourceId, JsonNode payload);

    void delivery(UUID tenant, UUID run, String rawId, String sourceId, String error);

    List<Employee> employees(UUID tenant);

    Optional<Employee> employee(UUID tenant, String staffId);

    void observeEmployee(UUID tenant, UUID connector, UUID run, JsonNode employee);

    void bind(
            UUID tenant,
            UUID actor,
            Employee external,
            EmployeeCandidate internal,
            long revision,
            String reason);

    String mappedEmployee(UUID tenant, String userId);

    record PendingBinding(
            UUID id,
            UUID tenant,
            int attempts,
            com.rigour.order.api.v1.model.LogisticsImportModels.EmployeeMappingUpdate update) {}

    default List<PendingBinding> bindingsDue(Instant now) {
        return List.of();
    }

    default boolean claimBinding(PendingBinding job, Instant now) {
        return false;
    }

    default void finishBinding(PendingBinding job, boolean success, Instant now) {}
}
