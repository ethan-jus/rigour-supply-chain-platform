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
            boolean writable,
            String parentCode,
            boolean historicalPlacementUnknown) {
        public Subject(String dimensionType, String code, String name, String cityCode, String cityName,
                String departmentName, String employmentStatus, boolean writable) {
            this(dimensionType, code, name, cityCode, cityName, departmentName, employmentStatus, writable, null, false);
        }
    }

    public record Target(
            String month,
            String dimensionType,
            String code,
            String name,
            String metric,
            BigDecimal value,
            int revision) {}

    public record BusinessTargets(BigDecimal salesTargetAmount, BigDecimal receiptTargetAmount) {}

    public record Settings(String month, List<Subject> subjects, List<Target> targets, boolean archived) {}


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
