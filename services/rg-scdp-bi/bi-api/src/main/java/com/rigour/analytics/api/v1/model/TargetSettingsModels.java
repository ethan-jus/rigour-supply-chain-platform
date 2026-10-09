package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TargetSettingsModels {
    private TargetSettingsModels() {}
    public record Subject(String dimensionType, String code, String name, String cityCode,
            String cityName, String departmentName, String employmentStatus, boolean writable) {}
    /** Deleted overrides retain their revision so restore-to-default cannot lose a concurrent update. */
    public record TargetOverride(String dimensionType, String code, String metric, BigDecimal value,
            int revision, boolean deleted, String updatedBy, Instant updatedAt) {}
    public record DefaultRule(String dimensionType, String effectiveMonth, String metric,
            BigDecimal value, int revision) {}
    public record Settings(String month, List<Subject> subjects, List<TargetOverride> overrides,
            List<DefaultRule> defaults, boolean defaultsWritable) {}
    public record Change(String dimensionType, String code, String metric, BigDecimal value,
            int expectedRevision) {}
    public record Batch(String month, List<Change> changes, String reason) {}
    public record DefaultsBatch(String effectiveMonth, String dimensionType,
            List<DefaultChange> changes, String reason) {}
    public record DefaultChange(String metric, BigDecimal value, int expectedRevision) {}
    public record History(String metric, BigDecimal value, boolean deleted, int revision,
            String reason, String actor, Instant occurredAt) {}
}
