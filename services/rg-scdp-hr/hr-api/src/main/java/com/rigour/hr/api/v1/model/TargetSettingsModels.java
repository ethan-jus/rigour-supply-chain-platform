package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TargetSettingsModels {
    private TargetSettingsModels() {}

    public record Subject(
            String dimensionType,
            String code,
            String name,
            String cityCode,
            String cityName,
            String departmentName,
            String employmentStatus,
            boolean writable) {}

    public record Target(
            String month,
            String dimensionType,
            String code,
            String name,
            String metric,
            BigDecimal value,
            int revision) {}

    public record Settings(String month, List<Subject> subjects, List<Target> targets) {}

    public record Change(
            String dimensionType,
            String code,
            String metric,
            BigDecimal value,
            int expectedRevision) {}

    public record Batch(String month, List<Change> changes, String reason) {}

    public record History(
            String metric,
            BigDecimal value,
            int revision,
            String reason,
            String actor,
            Instant occurredAt) {}
}
