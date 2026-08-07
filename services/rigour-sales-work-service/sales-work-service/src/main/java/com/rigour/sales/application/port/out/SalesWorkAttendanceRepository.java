package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 阶段 2 外勤事实持久化端口；只操作 Sales Work 自有 Schema。 */
public interface SalesWorkAttendanceRepository {

    Optional<WorkDaySnapshot> findWorkDay(UUID tenantId, UUID salesProfileId, UUID workDayId);

    Optional<WorkDaySnapshot> findWorkDay(UUID tenantId, UUID salesProfileId, LocalDate businessDate);

    void insertWorkDay(UUID id, UUID tenantId, UUID employeeId, UUID salesProfileId,
                       LocalDate businessDate, String timezoneId, UUID fieldPolicyVersionId,
                       Instant checkedInAt);

    void insertPunchEvent(UUID id, UUID tenantId, UUID workDayId, String eventType,
                          String deviceEventId, Instant clientOccurredAt, Instant serverReceivedAt,
                          BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
                          String deviceIdHash, String networkType, UUID policyVersionId);

    void insertLocationSession(UUID id, UUID tenantId, UUID workDayId, Instant startedAt,
                               int expectedIntervalMinutes);

    List<String> findExistingLocationEventIds(UUID tenantId, List<String> deviceEventIds);

    void insertLocationPoint(UUID id, UUID tenantId, UUID locationSessionId, UUID workDayId,
                             String deviceEventId, BigDecimal longitude, BigDecimal latitude,
                             BigDecimal accuracyMeters, Instant clientOccurredAt,
                             Instant serverReceivedAt, String source, String qualityStatus);

    int incrementLocationPointCount(UUID tenantId, UUID workDayId, UUID locationSessionId,
                                    int amount);

    void insertInterruption(UUID id, UUID tenantId, UUID workDayId, String interruptionType,
                            Instant startedAt, Instant endedAt, Integer durationSeconds,
                            String clientDetail);

    int incrementInterruptionCount(UUID tenantId, UUID workDayId, UUID locationSessionId);

    int finishWorkDay(UUID tenantId, UUID workDayId, Instant checkedOutAt, int verifiedWorkMinutes);

    int closeLocationSession(UUID tenantId, UUID workDayId, UUID locationSessionId, Instant endedAt);

    void insertWorkDaySummary(UUID id, UUID tenantId, UUID workDayId, Instant checkInAt,
                              Instant checkOutAt, int verifiedWorkMinutes, int locationPointCount,
                              int interruptionCount, String evidenceQuality, Instant finalizedAt);

    int incrementWorkDayVersion(UUID tenantId, UUID workDayId);

    record WorkDaySnapshot(
            UUID id, UUID employeeId, UUID salesProfileId, LocalDate businessDate,
            String timezoneId, UUID fieldPolicyVersionId, String status,
            Instant checkedInAt, Instant checkedOutAt, UUID locationSessionId,
            int locationPointCount, int interruptionCount, int verifiedWorkMinutes,
            String evidenceQuality) {
    }
}
