package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 拜访聚合事实持久化端口；同时承担 CRM 门店投影的补偿写入（见实现注释）。 */
public interface SalesWorkVisitRepository {

    Optional<VisitSnapshot> findVisit(UUID tenantId, UUID salesProfileId, UUID visitId);

    List<VisitSnapshot> findVisits(UUID tenantId, UUID salesProfileId, int limit, int offset);

    List<VisitSnapshot> findVisits(UUID tenantId, UUID salesProfileId, LocalDate businessDate,
                                   int limit, int offset);

    long countVisits(UUID tenantId, UUID salesProfileId);

    long countVisits(UUID tenantId, UUID salesProfileId, LocalDate businessDate);

    VisitActivitySummaryRow summarizeVisits(UUID tenantId, UUID salesProfileId,
                                             LocalDate from, LocalDate to);

    List<VisitSnapshot> findVisitsByWorkDay(UUID tenantId, UUID salesProfileId, UUID workDayId);

    boolean existsVisitBefore(UUID tenantId, UUID salesProfileId, UUID storeId, Instant checkedInAt);

    /** 串行化同一销售的拜访创建，防止并发产生多个进行中拜访。 */
    void lockSalesProfile(UUID tenantId, UUID salesProfileId);

    Optional<VisitSnapshot> findActiveVisit(UUID tenantId, UUID salesProfileId);

    void insertVisit(UUID id, UUID tenantId, UUID workDayId, UUID salesProfileId,
                     String targetType, UUID customerId, UUID storeId,
                     UUID visitPolicyVersionId, Instant checkedInAt);

    void insertTargetSnapshot(UUID id, UUID tenantId, UUID visitId, String targetType,
                              UUID customerId, String customerName, UUID storeId,
                              String storeName, String storeAddress,
                              BigDecimal longitude, BigDecimal latitude,
                              UUID assignedSalesProfileId, Instant capturedAt);

    void insertCheckpoint(UUID id, UUID tenantId, UUID visitId, String checkpointType,
                          String deviceEventId, Instant clientOccurredAt, Instant serverReceivedAt,
                          BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
                          BigDecimal distanceToTargetMeters, String evidenceStatus);

    int checkOutVisit(UUID tenantId, UUID salesProfileId, UUID visitId, Instant checkedOutAt);

    /** 拜访结果采集（接触结果/KP/电话/意向/备注）；只允许本人更新自己的拜访。 */
    int updateVisitResult(UUID tenantId, UUID salesProfileId, UUID visitId, String contactOutcome, String kpName,
                          String kpPhone, String intentionLevel, String resultNote, Instant submittedAt);

    Optional<VisitTargetSnapshot> findTargetSnapshot(UUID tenantId, UUID visitId);

    List<VisitTargetSnapshot> findTargetSnapshots(UUID tenantId, List<UUID> visitIds);

    List<VisitCheckpointSnapshot> findCheckpoints(UUID tenantId, UUID visitId);

    void upsertPoiStoreProjection(UUID tenantId, UUID storeId, String storeName, String storeAddress,
                                  BigDecimal longitude, BigDecimal latitude, Instant at);

    void upsertPoiAssignmentProjection(UUID tenantId, UUID salesProfileId, UUID storeId, Instant at);

    record VisitSnapshot(
            UUID id, UUID workDayId, UUID salesProfileId, String targetType,
            UUID customerId, UUID storeId, String status,
            Instant checkedInAt, Instant checkedOutAt, UUID visitPolicyVersionId,
            Instant createdAt, String contactOutcome, String kpName, String kpPhone, String intentionLevel,
            String resultNote, Instant resultSubmittedAt, Instant submittedAt,
            Instant finalizedAt, String finalReasonCode) {
    }

    record VisitActivitySummaryRow(
            long totalVisitCount, long completedVisitCount, long inProgressVisitCount,
            long effectiveVisitCount, long pendingReviewVisitCount,
            long firstVisitCount, long revisitCount, long uniqueStoreCount,
            long assignedStoreCount) {
    }

    record VisitTargetSnapshot(
            UUID visitId, String targetType, UUID customerId, UUID storeId, String customerName,
            String storeName, String storeAddress, BigDecimal longitude, BigDecimal latitude,
            UUID assignedSalesProfileId) {
    }

    record VisitCheckpointSnapshot(
            UUID id, String checkpointType, String deviceEventId,
            Instant clientOccurredAt, Instant serverReceivedAt,
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracyMeters,
            BigDecimal distanceToTargetMeters, String evidenceStatus) {
    }
}
