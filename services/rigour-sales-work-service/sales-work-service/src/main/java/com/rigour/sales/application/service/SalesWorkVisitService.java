package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CreateVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.NearbyStorePageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.NearbyStoreView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.PoiTargetCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitCheckpointView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitActivitySummaryView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitResultCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetSnapshotView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitView;
import com.rigour.sales.application.port.out.AmapPoiClient;
import com.rigour.sales.application.port.out.AmapPoiException;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.WorkDaySnapshot;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.StoreProjection;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.VisitPolicy;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitPlanRepository;
import com.rigour.sales.application.port.out.SalesWorkVisitPlanRepository.VisitPlanRow;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitCheckpointSnapshot;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitSnapshot;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository.VisitTargetSnapshot;
import com.rigour.sales.infrastructure.persistence.JdbcSalesIdempotencyStore;
import com.rigour.sales.infrastructure.persistence.JdbcSalesIdempotencyStore.Reservation;
import com.rigour.sales.infrastructure.persistence.JdbcSalesIdempotencyStore.Status;
import com.rigour.shared.audit.AuditEvent;
import com.rigour.shared.audit.AuditSink;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.outbox.OutboxMessage;
import com.rigour.shared.outbox.OutboxStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * 拜访用例：附近门店（高德）、创建拜访（到店）、离店、本人拜访列表/详情。
 *
 * <p>目标三来源：① 主管计划（CRM负责门店）② 本人负责门店临时拜访 ③ 附近 POI 临时拜访。
 * POI 首次拜访成功后沉淀进 CRM 投影，第二次起从“我的门店”选择。创建与签退均按固化规则执行距离校验。</p>
 */
@Service
public class SalesWorkVisitService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(48);
    private static final int DEFAULT_RADIUS_METERS = 3000;
    private static final int MAX_RADIUS_METERS = 5000;
    private static final int MAX_PAGE_SIZE = 20;
    private static final String POI_STORE_ID_PREFIX = "amap:";
    private static final java.util.Set<String> INTENTION_LEVELS = java.util.Set.of(
            "HIGH", "MEDIUM", "LOW", "NONE");
    private static final java.util.Set<String> CONTACT_OUTCOMES = java.util.Set.of(
            "CONTACTED", "STORE_CLOSED", "KP_ABSENT", "REFUSED", "OTHER_NO_CONTACT");

    private final SalesWorkContextService contextService;
    private final SalesWorkAttendanceRepository attendanceRepository;
    private final SalesWorkVisitRepository visitRepository;
    private final SalesWorkVisitPlanRepository planRepository;
    private final SalesWorkQueryRepository queryRepository;
    private final AmapPoiClient amapPoiClient;
    private final JdbcSalesIdempotencyStore idempotencyStore;
    private final SalesWorkVisitAssessmentService assessmentService;
    private final OutboxStore outboxStore;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SalesWorkVisitService(SalesWorkContextService contextService,
                                 SalesWorkAttendanceRepository attendanceRepository,
                                 SalesWorkVisitRepository visitRepository,
                                 SalesWorkVisitPlanRepository planRepository,
                                 SalesWorkQueryRepository queryRepository,
                                 AmapPoiClient amapPoiClient,
                                 JdbcSalesIdempotencyStore idempotencyStore,
                                 SalesWorkVisitAssessmentService assessmentService,
                                 OutboxStore outboxStore,
                                 AuditSink auditSink,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.contextService = contextService;
        this.attendanceRepository = attendanceRepository;
        this.visitRepository = visitRepository;
        this.planRepository = planRepository;
        this.queryRepository = queryRepository;
        this.amapPoiClient = amapPoiClient;
        this.idempotencyStore = idempotencyStore;
        this.assessmentService = assessmentService;
        this.outboxStore = outboxStore;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public NearbyStorePageView nearbyStores(BigDecimal longitude, BigDecimal latitude,
                                            Integer radiusMeters, String query,
                                            int page, int pageSize) {
        CallerIdentity caller = requireCaller("sales:poi:read");
        validateCoordinates(longitude, latitude, null, ErrorCode.SALES_POI_INVALID);
        int radius = radiusMeters == null ? DEFAULT_RADIUS_METERS : radiusMeters;
        if (radius < 100 || radius > MAX_RADIUS_METERS) {
            throw new BusinessException(ErrorCode.SALES_POI_INVALID, "半径必须在100到5000米之间", List.of());
        }
        validatePage(page, pageSize);
        contextService.resolveIdentity(caller, clock.instant());
        var nearby = amapPoiClient.searchAround(normalizeKeyword(query), longitude, latitude,
                radius, page, Math.min(pageSize, MAX_PAGE_SIZE));
        List<NearbyStoreView> items = nearby.items().stream()
                .map(poi -> toNearbyStore(caller.tenantId(), poi))
                .toList();
        return new NearbyStorePageView(items, nearby.page(), nearby.pageSize(), nearby.total());
    }

    @Transactional
    public VisitView createVisit(CreateVisitCommand command) {
        CallerIdentity caller = requireCaller("sales:visit:own:write");
        validateCreateCommand(command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay = attendanceRepository
                .findWorkDay(caller.tenantId(), identity.profile().id(), command.workDayId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        if (!"ACTIVE".equals(workDay.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_REQUIRES_ACTIVE_WORK_DAY);
        }
        VisitPolicy policy = queryRepository
                .findActiveVisitPolicy(caller.tenantId(), identity.profile().id(),
                        identity.profile().cityOrgId(), receivedAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND));

        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "VISIT_CREATE", key, command);
        if (reservation.status() == Status.COMPLETED) {
            return replayVisit(caller, identity.profile().id(), reservation);
        }
        throwIfNotReserved(reservation);

        VisitPlanRow requestedPlan = command.visitPlanId() == null ? null
                : requireExecutablePlan(caller, identity, command.visitPlanId(), workDay.businessDate(),
                policy, receivedAt, false);
        Target target = requestedPlan == null
                ? resolveTarget(caller, identity, command, policy, receivedAt)
                : targetFromPlan(requestedPlan);
        validateLocation(command.location());
        double distance = distanceMeters(command.location().longitude(), command.location().latitude(),
                target.longitude(), target.latitude());
        if (BigDecimal.valueOf(distance)
                .compareTo(BigDecimal.valueOf(policy.checkInRadiusMeters())) > 0) {
            throw new BusinessException(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS,
                    "当前位置距离目标 " + Math.round(distance) + " 米，超过规则允许的 "
                            + policy.checkInRadiusMeters() + " 米", List.of());
        }

        visitRepository.lockSalesProfile(caller.tenantId(), identity.profile().id());
        visitRepository.findActiveVisit(caller.tenantId(), identity.profile().id())
                .ifPresent(active -> {
                    throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                            "请先结束当前进行中的拜访", List.of());
                });

        if (command.visitPlanId() != null) {
            requestedPlan = requireExecutablePlan(caller, identity, command.visitPlanId(),
                    workDay.businessDate(), policy, receivedAt, true);
            target = targetFromPlan(requestedPlan);
            distance = distanceMeters(command.location().longitude(), command.location().latitude(),
                    target.longitude(), target.latitude());
            if (BigDecimal.valueOf(distance)
                    .compareTo(BigDecimal.valueOf(policy.checkInRadiusMeters())) > 0) {
                throw new BusinessException(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS,
                        "当前位置距离计划门店 " + Math.round(distance) + " 米，超过规则允许的 "
                                + policy.checkInRadiusMeters() + " 米", List.of());
            }
        }

        UUID visitId = UUID.randomUUID();
        try {
            visitRepository.insertVisit(visitId, caller.tenantId(), workDay.id(), command.visitPlanId(),
                    identity.profile().id(),
                    target.targetType(), target.customerId(), target.storeId(), policy.id(), receivedAt);
            if (command.visitPlanId() != null && planRepository.markInProgress(
                    caller.tenantId(), identity.profile().id(), command.visitPlanId(), receivedAt) != 1) {
                throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                        "计划状态已变化，请刷新今日计划", List.of());
            }
            visitRepository.insertTargetSnapshot(UUID.randomUUID(), caller.tenantId(), visitId,
                    target.targetType(), target.customerId(), target.customerName(), target.storeId(),
                    target.storeName(), target.storeAddress(), target.longitude(), target.latitude(),
                    "MY_STORE".equals(target.targetType()) ? identity.profile().id() : null, receivedAt);
            visitRepository.insertCheckpoint(UUID.randomUUID(), caller.tenantId(), visitId, "CHECK_IN",
                    deviceEventKey("visit-check-in", key), command.clientOccurredAt(), receivedAt,
                    command.location().longitude(), command.location().latitude(),
                    command.location().accuracyMeters(), BigDecimal.valueOf(distance), "RECEIVED");
            if ("POI".equals(target.targetType())) {
                visitRepository.upsertPoiStoreProjection(caller.tenantId(), target.storeId(),
                        target.storeName(), target.storeAddress(), target.longitude(), target.latitude(),
                        receivedAt);
                visitRepository.upsertPoiAssignmentProjection(caller.tenantId(), identity.profile().id(),
                        target.storeId(), receivedAt);
            }
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID,
                    "设备事件或拜访目标已被占用", List.of());
        }

        Map<String, Object> createdPayload = new LinkedHashMap<>(Map.of(
                "visitId", visitId.toString(),
                "workDayId", workDay.id().toString(),
                "salesProfileId", identity.profile().id().toString(),
                "targetType", target.targetType(),
                "storeId", target.storeId().toString(),
                "checkedInAt", receivedAt.toString(),
                "visitPolicyVersionId", policy.id().toString(),
                "visitSource", command.visitPlanId() == null ? "TEMPORARY" : "MANAGER_PLAN"));
        Map<String, String> createdAudit = new LinkedHashMap<>(Map.of(
                "targetType", target.targetType(),
                "storeId", target.storeId().toString(),
                "visitPolicyVersionId", policy.id().toString(),
                "visitSource", command.visitPlanId() == null ? "TEMPORARY" : "MANAGER_PLAN"));
        if (command.visitPlanId() != null) {
            createdPayload.put("visitPlanId", command.visitPlanId().toString());
            createdAudit.put("visitPlanId", command.visitPlanId().toString());
        }
        appendOutbox(caller, visitId, "SalesVisitCreated", createdPayload);
        appendAudit(caller, "SALES_VISIT_CREATE", visitId, createdAudit);
        complete(caller, "VISIT_CREATE", key, visitId.toString());
        return visitView(caller.tenantId(), identity.profile().id(), visitId);
    }

    @Transactional
    public VisitView checkOutVisit(UUID visitId, CheckOutVisitCommand command) {
        CallerIdentity caller = requireCaller("sales:visit:own:write");
        validateCheckOutCommand(visitId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);

        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "VISIT_CHECK_OUT", key, command);
        if (reservation.status() == Status.COMPLETED) {
            return replayVisit(caller, identity.profile().id(), reservation);
        }
        throwIfNotReserved(reservation);

        VisitSnapshot visit = visitRepository.findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        if (!"CHECKED_IN".equals(visit.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE, "只有到店签到后的拜访可以签退", List.of());
        }
        VisitPolicy policy = queryRepository.findVisitPolicy(caller.tenantId(), visit.visitPolicyVersionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_POLICY_NOT_FOUND));
        long dwellMinutes = Duration.between(visit.checkedInAt(), receivedAt).toMinutes();
        if (visit.resultSubmittedAt() == null) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                    "请先填写并保存拜访结果", List.of());
        }
        // 停留和录音时长是有效拜访核验条件，不是限制销售离店的状态机门槛。
        // 关门、KP不在、拒绝接待等真实场景必须允许保存结果后立即签退，后续进入待复核。
        validateLocation(command.location());
        VisitTargetSnapshot target = visitRepository.findTargetSnapshot(caller.tenantId(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_TARGET_INVALID));
        double distance = distanceMeters(command.location().longitude(), command.location().latitude(),
                target.longitude(), target.latitude());
        if (BigDecimal.valueOf(distance)
                .compareTo(BigDecimal.valueOf(policy.checkInRadiusMeters())) > 0) {
            throw new BusinessException(ErrorCode.SALES_VISIT_OUTSIDE_RADIUS,
                    "当前位置距离目标 " + Math.round(distance) + " 米，超过规则允许的 "
                            + policy.checkInRadiusMeters() + " 米", List.of());
        }

        if (visitRepository.checkOutVisit(caller.tenantId(), identity.profile().id(), visitId, receivedAt) != 1) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE);
        }
        visitRepository.insertCheckpoint(UUID.randomUUID(), caller.tenantId(), visitId, "CHECK_OUT",
                deviceEventKey("visit-check-out", key), command.clientOccurredAt(), receivedAt,
                command.location().longitude(), command.location().latitude(),
                command.location().accuracyMeters(), BigDecimal.valueOf(distance), "RECEIVED");
        UUID completedPlanId = planRepository.findPlanIdByVisit(caller.tenantId(), visitId).orElse(null);
        if (completedPlanId != null
                && planRepository.markCompletedByVisit(caller.tenantId(), visitId, receivedAt) == 0
                && !"COMPLETED".equals(planRepository.findStatusByVisit(caller.tenantId(), visitId)
                .orElse(null))) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                    "拜访已离店但主管计划状态未能完成，请刷新后重试", List.of());
        }

        Map<String, Object> checkedOutPayload = new LinkedHashMap<>(Map.of(
                "visitId", visitId.toString(),
                "workDayId", visit.workDayId().toString(),
                "checkedOutAt", receivedAt.toString(),
                "dwellMinutes", dwellMinutes));
        if (completedPlanId != null) checkedOutPayload.put("visitPlanId", completedPlanId.toString());
        appendOutbox(caller, visitId, "SalesVisitCheckedOut", checkedOutPayload);
        appendAudit(caller, "SALES_VISIT_CHECK_OUT", visitId, Map.of(
                "dwellMinutes", Long.toString(dwellMinutes)));
        assessmentService.assess(caller, visitId);
        complete(caller, "VISIT_CHECK_OUT", key, visitId.toString());
        return visitView(caller.tenantId(), identity.profile().id(), visitId);
    }

    public VisitPageView visits(int page, int pageSize) {
        return visits(null, page, pageSize);
    }

    public VisitPageView visits(LocalDate date, int page, int pageSize) {
        CallerIdentity caller = requireCaller("sales:visit:own:read");
        validatePage(page, pageSize);
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, clock.instant());
        int offset = Math.multiplyExact(page - 1, pageSize);
        List<VisitSnapshot> visits = date == null
                ? visitRepository.findVisits(caller.tenantId(), identity.profile().id(), pageSize, offset)
                : visitRepository.findVisits(caller.tenantId(), identity.profile().id(), date, pageSize, offset);
        List<VisitTargetSnapshot> snapshots = visitRepository.findTargetSnapshots(caller.tenantId(),
                visits.stream().map(VisitSnapshot::id).toList());
        Map<UUID, VisitTargetSnapshot> snapshotByVisit = new java.util.HashMap<>();
        for (VisitTargetSnapshot snapshot : snapshots) {
            snapshotByVisit.put(snapshot.visitId(), snapshot);
        }
        List<VisitView> items = visits.stream()
                .map(visit -> view(visit, snapshotByVisit.get(visit.id()), List.of(),
                        visitType(caller.tenantId(), identity.profile().id(), visit), reviewStatus(visit)))
                .toList();
        return new VisitPageView(items, page, pageSize,
                date == null
                        ? visitRepository.countVisits(caller.tenantId(), identity.profile().id())
                        : visitRepository.countVisits(caller.tenantId(), identity.profile().id(), date));
    }

    public VisitActivitySummaryView activitySummary(LocalDate from, LocalDate to) {
        CallerIdentity caller = requireCaller("sales:visit:own:read");
        if (from == null || to == null || from.isAfter(to)) {
            throw targetInvalid("日期范围无效");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) > 366) {
            throw targetInvalid("日期范围不能超过366天");
        }
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, clock.instant());
        var summary = visitRepository.summarizeVisits(
                caller.tenantId(), identity.profile().id(), from, to);
        return new VisitActivitySummaryView(from, to, summary.totalVisitCount(),
                summary.completedVisitCount(), summary.inProgressVisitCount(),
                summary.effectiveVisitCount(), summary.pendingReviewVisitCount(),
                summary.firstVisitCount(), summary.revisitCount(), summary.uniqueStoreCount(),
                summary.assignedStoreCount());
    }

    public VisitView visit(UUID visitId) {
        CallerIdentity caller = requireCaller("sales:visit:own:read");
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, clock.instant());
        return visitView(caller.tenantId(), identity.profile().id(), visitId);
    }

    /** 拜访结果采集：实际接触与未接触场景采用不同校验；进行中或已签退均可补录。 */
    @Transactional
    public VisitView submitVisitResult(UUID visitId, VisitResultCommand command) {
        CallerIdentity caller = requireCaller("sales:visit:own:write");
        if (visitId == null || command == null) {
            throw targetInvalid("拜访结果请求无效");
        }
        String contactOutcome = required(command.contactOutcome(), "contactOutcome", 32)
                .toUpperCase(Locale.ROOT);
        if (!CONTACT_OUTCOMES.contains(contactOutcome)) {
            throw targetInvalid("contactOutcome不支持当前值");
        }
        boolean contacted = "CONTACTED".equals(contactOutcome);
        String kpName = contacted ? required(command.kpName(), "kpName", 128) : null;
        String kpPhone = contacted ? required(command.kpPhone(), "kpPhone", 32) : null;
        String intention = contacted
                ? required(command.intentionLevel(), "intentionLevel", 24).toUpperCase(Locale.ROOT)
                : null;
        if (contacted && !INTENTION_LEVELS.contains(intention)) {
            throw targetInvalid("intentionLevel仅支持HIGH/MEDIUM/LOW/NONE");
        }
        String resultNote = bounded(command.resultNote(), 1024);
        if (!contacted && !StringUtils.hasText(resultNote)) {
            throw targetInvalid("未接触KP时必须填写现场说明");
        }
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        VisitSnapshot visit = visitRepository.findVisit(caller.tenantId(), identity.profile().id(), visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        if (!"CHECKED_IN".equals(visit.status()) && !"CHECKED_OUT".equals(visit.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE, "当前拜访状态不允许录入结果", List.of());
        }
        if (visitRepository.updateVisitResult(caller.tenantId(), identity.profile().id(), visitId,
                contactOutcome, kpName, kpPhone, intention, resultNote, receivedAt) != 1) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE);
        }
        appendAudit(caller, "SALES_VISIT_RESULT", visitId, Map.of(
                "contactOutcome", contactOutcome,
                "hasKp", Boolean.toString(contacted),
                "intentionLevel", intention == null ? "" : intention));
        if ("CHECKED_OUT".equals(visit.status())) assessmentService.assess(caller, visitId);
        return visitView(caller.tenantId(), identity.profile().id(), visitId);
    }

    private VisitView visitView(UUID tenantId, UUID salesProfileId, UUID visitId) {
        VisitSnapshot visit = visitRepository.findVisit(tenantId, salesProfileId, visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_VISIT_NOT_FOUND));
        VisitTargetSnapshot target = visitRepository.findTargetSnapshot(tenantId, visitId)
                .orElse(null);
        List<VisitCheckpointSnapshot> checkpoints = visitRepository.findCheckpoints(tenantId, visitId);
        return view(visit, target, checkpoints,
                visitType(tenantId, salesProfileId, visit), reviewStatus(visit));
    }

    private static VisitView view(VisitSnapshot visit, VisitTargetSnapshot target,
                                  List<VisitCheckpointSnapshot> checkpoints,
                                  String visitType, String reviewStatus) {
        return new VisitView(visit.id(), visit.workDayId(), visit.salesProfileId(), visit.targetType(),
                visit.customerId(), visit.storeId(), visit.status(), visit.checkedInAt(), visit.checkedOutAt(),
                visit.visitPolicyVersionId(), target == null ? null : targetView(target),
                checkpoints.stream().map(SalesWorkVisitService::checkpointView).toList(), visit.createdAt(),
                visit.contactOutcome(), visit.kpName(), visit.kpPhone(), visit.intentionLevel(), visit.resultNote(),
                visit.resultSubmittedAt(), visitType, reviewStatus);
    }

    private String visitType(UUID tenantId, UUID salesProfileId, VisitSnapshot visit) {
        return visitRepository.existsVisitBefore(tenantId, salesProfileId, visit.storeId(), visit.checkedInAt())
                ? "REVISIT" : "FIRST_VISIT";
    }

    private static String reviewStatus(VisitSnapshot visit) {
        if (visit.checkedOutAt() == null) return "IN_PROGRESS";
        if (visit.finalizedAt() == null) return "PENDING_REVIEW";
        return "EFFECTIVE".equals(visit.finalReasonCode()) ? "EFFECTIVE" : "INEFFECTIVE";
    }

    private static VisitTargetSnapshotView targetView(VisitTargetSnapshot target) {
        return new VisitTargetSnapshotView(target.targetType(), target.customerId(), target.storeId(),
                target.customerName(), target.storeName(), target.storeAddress(), target.longitude(),
                target.latitude(), target.assignedSalesProfileId());
    }

    private static VisitCheckpointView checkpointView(VisitCheckpointSnapshot checkpoint) {
        return new VisitCheckpointView(checkpoint.id(), checkpoint.checkpointType(),
                checkpoint.deviceEventId(), checkpoint.clientOccurredAt(), checkpoint.serverReceivedAt(),
                checkpoint.longitude(), checkpoint.latitude(), checkpoint.accuracyMeters(),
                checkpoint.distanceToTargetMeters(), checkpoint.evidenceStatus());
    }

    private NearbyStoreView toNearbyStore(UUID tenantId, AmapPoiClient.NearbyPoi poi) {
        UUID storeId = stablePoiStoreId(poi.poiId());
        return new NearbyStoreView(poi.poiId(), poi.name(), poi.address(), poi.type(), poi.typeCode(),
                poi.longitude(), poi.latitude(), poi.distanceMeters(), storeId,
                queryRepository.existsStore(tenantId, storeId), "AMAP");
    }

    private Target resolveTarget(CallerIdentity caller, SalesWorkContextService.SalesIdentity identity,
                                 CreateVisitCommand command, VisitPolicy policy, Instant at) {
        String targetType = required(command.targetType(), "targetType", 24).toUpperCase(Locale.ROOT);
        if ("MY_STORE".equals(targetType)) {
            if (command.storeId() == null) throw targetInvalid("门店ID不能为空");
            StoreProjection store = queryRepository.findStoreById(caller.tenantId(), command.storeId())
                    .orElseThrow(() -> targetInvalid("门店不存在或已停用"));
            if (policy.requireAssignedTarget()
                    && !queryRepository.isStoreAssignedToProfile(caller.tenantId(),
                    identity.profile().id(), store.storeId(), at)) {
                throw targetInvalid("当前门店不在本人负责范围内");
            }
            if (store.longitude() == null || store.latitude() == null) {
                throw targetInvalid("门店缺少坐标，无法校验到店距离");
            }
            return new Target(targetType, store.customerId(), store.storeId(), store.customerName(),
                    store.storeName(), store.storeAddress(), store.longitude(), store.latitude());
        }
        if ("POI".equals(targetType)) {
            if (!policy.allowProspectTarget()) throw targetInvalid("当前拜访规则不允许拜访新门店");
            PoiTargetCommand poi = command.poi();
            if (poi == null) throw targetInvalid("POI目标不能为空");
            String poiId = required(poi.poiId(), "poi.poiId", 128);
            String name = required(poi.name(), "poi.name", 256);
            validateCoordinates(poi.longitude(), poi.latitude(), null, ErrorCode.SALES_VISIT_TARGET_INVALID);
            return new Target(targetType, null, stablePoiStoreId(poiId), null, name,
                    bounded(poi.address(), 512), poi.longitude(), poi.latitude());
        }
        throw targetInvalid("targetType仅支持MY_STORE或POI");
    }

    private VisitPlanRow requireExecutablePlan(
            CallerIdentity caller, SalesWorkContextService.SalesIdentity identity,
            UUID planId, LocalDate businessDate, VisitPolicy policy, Instant at, boolean lock) {
        VisitPlanRow plan = planRepository.findOwnPlan(
                        caller.tenantId(), identity.profile().id(), planId, lock)
                .orElseThrow(() -> targetInvalid("今日拜访计划不存在"));
        if (!"PLANNED".equals(plan.status())) {
            throw new BusinessException(ErrorCode.SALES_VISIT_INVALID_STATE,
                    "该计划已开始、完成或取消", List.of());
        }
        if (!businessDate.equals(plan.plannedDate())) {
            throw targetInvalid("该计划不属于当前工作日");
        }
        if (!"MY_STORE".equals(plan.targetType()) || plan.storeId() == null
                || plan.longitude() == null || plan.latitude() == null
                || !StringUtils.hasText(plan.storeName())) {
            throw targetInvalid("计划门店资料不完整，请联系主管调整计划");
        }
        if (policy.requireAssignedTarget() && !queryRepository.isStoreAssignedToProfile(
                caller.tenantId(), identity.profile().id(), plan.storeId(), at)) {
            throw targetInvalid("计划门店已不在本人负责范围内，请联系主管调整计划");
        }
        return plan;
    }

    private static Target targetFromPlan(VisitPlanRow plan) {
        return new Target("MY_STORE", plan.customerId(), plan.storeId(), plan.customerName(),
                plan.storeName(), plan.storeAddress(), plan.longitude(), plan.latitude());
    }

    private static UUID stablePoiStoreId(String poiId) {
        return UUID.nameUUIDFromBytes((POI_STORE_ID_PREFIX + poiId).getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException targetInvalid(String message) {
        return new BusinessException(ErrorCode.SALES_VISIT_TARGET_INVALID, message, List.of());
    }

    private CallerIdentity requireCaller(String permission) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private Reservation reserve(CallerIdentity caller, String operation, String key, Object command) {
        return idempotencyStore.reserveCommand(caller.tenantId(), caller.userId(), operation, key,
                JdbcSalesIdempotencyStore.sha256(operation + "|" + writeJson(command)), IDEMPOTENCY_TTL);
    }

    private void complete(CallerIdentity caller, String operation, String key, String reference) {
        idempotencyStore.complete(new com.rigour.shared.idempotency.IdempotencyKey(
                caller.tenantId().toString(), operation, key), reference);
    }

    private void throwIfNotReserved(Reservation reservation) {
        if (reservation.status() == Status.CONFLICT) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        }
        if (reservation.status() == Status.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_IN_PROGRESS);
        }
    }

    private VisitView replayVisit(CallerIdentity caller, UUID profileId, Reservation reservation) {
        UUID visitId;
        try {
            visitId = UUID.fromString(reservation.reference());
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        }
        return visitView(caller.tenantId(), profileId, visitId);
    }

    private void appendOutbox(CallerIdentity caller, UUID visitId, String eventType,
                              Map<String, Object> payload) {
        outboxStore.append(new OutboxMessage(UUID.randomUUID(), caller.tenantId().toString(),
                "SALES_VISIT", visitId.toString(), eventType, 1, writeJson(payload),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private void appendAudit(CallerIdentity caller, String action, UUID visitId,
                             Map<String, String> attributes) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), action, "SALES_VISIT", visitId.toString(), attributes,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("拜访请求序列化失败", error);
        }
    }

    private static void validateCreateCommand(CreateVisitCommand command) {
        if (command == null) throw badRequest("拜访请求不能为空");
        required(command.idempotencyKey(), "idempotencyKey", 128);
        if (command.workDayId() == null) throw badRequest("workDayId不能为空");
        if (command.visitPlanId() == null) required(command.targetType(), "targetType", 24);
        if (command.clientOccurredAt() == null) throw badRequest("clientOccurredAt不能为空");
        required(command.deviceEventId(), "deviceEventId", 128);
    }

    private static void validateCheckOutCommand(UUID visitId, CheckOutVisitCommand command) {
        if (visitId == null || command == null) throw badRequest("签退请求不能为空");
        required(command.idempotencyKey(), "idempotencyKey", 128);
        if (command.clientOccurredAt() == null) throw badRequest("clientOccurredAt不能为空");
        required(command.deviceEventId(), "deviceEventId", 128);
    }

    private static void validateLocation(LocationEvidence location) {
        if (location == null) throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID);
        validateCoordinates(location.longitude(), location.latitude(), location.accuracyMeters(),
                ErrorCode.SALES_LOCATION_INVALID);
        required(location.source(), "source", 24);
    }

    private static void validateCoordinates(BigDecimal longitude, BigDecimal latitude, BigDecimal accuracy,
                                            ErrorCode errorCode) {
        if (longitude == null || latitude == null
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || (accuracy != null && accuracy.signum() < 0)) {
            throw new BusinessException(errorCode, "定位坐标无效", List.of());
        }
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页参数无效", List.of());
        }
    }

    private static String normalizeKeyword(String value) {
        return value == null ? null : value.trim().substring(0, Math.min(64, value.trim().length()));
    }

    private static String key(String value) {
        return required(value, "idempotencyKey", 128);
    }

    private static String required(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value) || value.trim().length() > maxLength) {
            throw badRequest(field + "不能为空且长度不能超过" + maxLength);
        }
        return value.trim();
    }

    private static String bounded(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(maxLength, value.length()));
    }

    private static String deviceEventKey(String prefix, String key) {
        String value = prefix + ":" + key;
        return value.substring(0, Math.min(128, value.length()));
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }

    private static double distanceMeters(BigDecimal lng1, BigDecimal lat1,
                                         BigDecimal lng2, BigDecimal lat2) {
        double lat1Rad = Math.toRadians(lat1.doubleValue());
        double lat2Rad = Math.toRadians(lat2.doubleValue());
        double dLat = Math.toRadians(lat2.subtract(lat1).doubleValue());
        double dLng = Math.toRadians(lng2.subtract(lng1).doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6_371_000 * Math.asin(Math.sqrt(a));
    }

    private record Target(String targetType, UUID customerId, UUID storeId,
                          String customerName, String storeName, String storeAddress,
                          BigDecimal longitude, BigDecimal latitude) {
    }
}
