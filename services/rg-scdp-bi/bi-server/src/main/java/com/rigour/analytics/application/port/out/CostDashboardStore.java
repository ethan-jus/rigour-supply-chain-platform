package com.rigour.analytics.application.port.out;

import com.rigour.analytics.domain.cost.CostFact;

import java.time.*;
import java.util.*;

public interface CostDashboardStore {
    record Snapshot(
            String id,
            String tenant,
            Instant generatedAt,
            LocalDate cutoff,
            Map<String, String> versions,
            List<City> cities,
            List<CostFact> records) {}

    record City(
            String code,
            String name,
            String sourceRegion,
            String departmentId,
            List<String> departmentPath) {}

    record State(String snapshotId, Instant attemptedAt, Instant succeededAt, String status) {}

    State state(String tenant);

    Snapshot snapshot(String tenant, String id);

    List<String> tenants();

    void register(String tenant);
}
