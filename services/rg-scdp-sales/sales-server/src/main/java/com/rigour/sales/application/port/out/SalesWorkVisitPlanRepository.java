package com.rigour.sales.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 主管拜访计划与销售执行关联的持久化边界。 */
public interface SalesWorkVisitPlanRepository {

    List<VisitPlanRow> findOwnPlans(UUID tenantId, UUID salesProfileId, LocalDate date);

    Optional<VisitPlanRow> findOwnPlan(
            UUID tenantId, UUID salesProfileId, UUID planId, boolean lock);

    List<VisitPlanRow> findManagementPlans(
            UUID tenantId, LocalDate from, LocalDate to, String status, int limit, int offset);

    long countManagementPlans(UUID tenantId, LocalDate from, LocalDate to, String status);

    Optional<VisitPlanRow> findManagementPlan(UUID tenantId, UUID planId, boolean lock);

    List<ProfileOptionRow> findActiveProfiles(UUID tenantId);

    Optional<ProfileOptionRow> findActiveProfile(UUID tenantId, UUID salesProfileId);

    boolean existsActiveDuplicate(
            UUID tenantId, UUID salesProfileId, LocalDate plannedDate, UUID storeId, UUID excludedPlanId);

    void insertPlan(UUID id, UUID tenantId, UUID salesProfileId, LocalDate plannedDate,
                    UUID customerId, UUID storeId, String objective, UUID createdBy, Instant now);

    int updatePlannedPlan(UUID tenantId, UUID planId, long expectedVersion,
                          UUID salesProfileId, LocalDate plannedDate,
                          UUID customerId, UUID storeId, String objective, Instant now);

    int cancelPlannedPlan(UUID tenantId, UUID planId, long expectedVersion, Instant now);

    int markInProgress(UUID tenantId, UUID salesProfileId, UUID planId, Instant now);

    int markCompletedByVisit(UUID tenantId, UUID visitId, Instant now);

    Optional<UUID> findPlanIdByVisit(UUID tenantId, UUID visitId);

    Optional<String> findStatusByVisit(UUID tenantId, UUID visitId);

    record ProfileOptionRow(UUID salesProfileId, UUID employeeId, String salesNo, UUID cityOrgId) {
    }

    record VisitPlanRow(
            UUID id, UUID salesProfileId, String salesNo, LocalDate plannedDate,
            String targetType, UUID customerId, UUID storeId,
            String customerName, String storeName, String storeAddress,
            BigDecimal longitude, BigDecimal latitude,
            String objective, String status, UUID visitId, long version,
            UUID createdBy, Instant createdAt, Instant updatedAt) {
    }
}
