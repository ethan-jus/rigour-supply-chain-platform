package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.AttendanceMonthDayView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.AttendanceMonthView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.ResumeWorkCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkPeriodView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.InterruptionCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchResult;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationPointCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackPointView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackPunchView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackTravelSegmentView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.TrackVisitView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayTrackView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.WorkDaySnapshot;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.FieldPolicy;
import com.rigour.sales.application.port.out.SalesWorkVisitRepository;
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

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 阶段 2 外勤考勤用例；以 Sales Work 事实、规则版本和事务边界为准。 */
@Service
public class SalesWorkAttendanceService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(48);
    private static final int MAX_LOCATION_POINTS = 100;

    private final SalesWorkContextService contextService;
    private final SalesWorkAttendanceRepository repository;
    private final SalesWorkVisitRepository visitRepository;
    private final JdbcSalesIdempotencyStore idempotencyStore;
    private final OutboxStore outboxStore;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SalesFieldworkService fieldwork;
    private final SalesWorkDayExpiryWorker expiry;

    public SalesWorkAttendanceService(
            SalesWorkContextService contextService,
            SalesWorkAttendanceRepository repository,
            SalesWorkVisitRepository visitRepository,
            JdbcSalesIdempotencyStore idempotencyStore,
            OutboxStore outboxStore,
            AuditSink auditSink,
            ObjectMapper objectMapper,
            Clock clock, SalesFieldworkService fieldwork, SalesWorkDayExpiryWorker expiry) {
        this.contextService = contextService;
        this.repository = repository;
        this.visitRepository = visitRepository;
        this.idempotencyStore = idempotencyStore;
        this.outboxStore = outboxStore;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.fieldwork = fieldwork; this.expiry = expiry;
    }

    @Transactional
    public WorkDayView checkIn(CheckInCommand command) {
        CallerIdentity caller = requireCaller("sales:work-day:write");
        validateCheckInCommand(command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, receivedAt);
        expiry.expireOwn(caller.tenantId(), identity.profile().id());
        FieldPolicy policy =
                contextService.resolvePolicy(caller.tenantId(), identity.profile(), receivedAt);
        validateLocation(command.location(), true);
        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "CHECK_IN", key, command);
        if (reservation.status() == Status.COMPLETED)
            return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);

        LocalDate businessDate = businessDate(receivedAt, policy);
        var existing =
                repository.findWorkDay(caller.tenantId(), identity.profile().id(), businessDate);
        if (existing.isPresent()) {
            if ("ACTIVE".equals(existing.get().status())) {
                throw new BusinessException(ErrorCode.SALES_WORK_DAY_ALREADY_ACTIVE);
            }
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE, "当天已签退，请填写原因后恢复作业", List.of());
        }

        UUID workDayId = UUID.randomUUID();
        UUID sessionId = policy.locationEnabled() ? UUID.randomUUID() : null;
        try {
            repository.insertWorkDay(
                    workDayId,
                    caller.tenantId(),
                    identity.projection().employeeId(),
                    identity.profile().id(),
                    businessDate,
                    policy.timezoneId(),
                    policy.id(),
                    receivedAt);
            repository.insertPunchEvent(
                    UUID.randomUUID(),
                    caller.tenantId(),
                    workDayId,
                    "CHECK_IN",
                    deviceEventKey("check-in", key),
                    command.clientOccurredAt(),
                    receivedAt,
                    longitude(command.location()),
                    latitude(command.location()),
                    accuracy(command.location()),
                    bounded(command.deviceIdHash(), 128),
                    bounded(command.networkType(), 32),
                    policy.id());
            if (sessionId != null) {
                repository.insertLocationSession(
                        sessionId,
                        caller.tenantId(),
                        workDayId,
                        receivedAt,
                        policy.locationIntervalMinutes());
            }
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_ALREADY_ACTIVE);
        }

        appendOutbox(
                caller,
                workDayId,
                "SalesWorkDayCheckedIn",
                Map.of(
                        "workDayId", workDayId.toString(),
                        identity.profile().hrEmployeeId() == null ? "employeeId" : "hrEmployeeId", identity.employeeReference(),
                        "salesProfileId", identity.profile().id().toString(),
                        "businessDate", businessDate.toString(),
                        "fieldPolicyVersionId", policy.id().toString(),
                        "checkedInAt", receivedAt.toString()));
        appendAudit(
                caller,
                "SALES_WORK_DAY_CHECK_IN",
                workDayId,
                Map.of(
                        "businessDate",
                        businessDate.toString(),
                        "fieldPolicyVersionId",
                        policy.id().toString()));
        complete(caller, "CHECK_IN", key, workDayId.toString());
        return view(
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    @Transactional
    public LocationBatchResult uploadLocationPoints(UUID workDayId, LocationBatchCommand command) {
        CallerIdentity caller = requireCaller("sales:location:write");
        validateLocationBatchCommand(workDayId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay =
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        FieldPolicy policy =
                contextService.resolvePolicyVersion(
                        caller.tenantId(), workDay.fieldPolicyVersionId());
        if (!policy.locationEnabled() || workDay.locationSessionId() == null) {
            throw new BusinessException(
                    ErrorCode.SALES_LOCATION_INVALID, "当前工作日未启用定位会话", List.of());
        }

        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "LOCATION_BATCH", key, command);
        if (reservation.status() == Status.COMPLETED) return replayLocationBatch(reservation);
        throwIfNotReserved(reservation);
        UUID sessionId = command.locationSessionId();
        if (sessionId == null) throw badRequest("定位会话不能为空");
        var capture = repository.findLocationSession(caller.tenantId(), workDay.id(), sessionId)
                .orElseThrow(() -> badRequest("定位会话不属于本次作业"));
        Instant cutoff = workDay.businessDate().plusDays(1).atTime(policy.businessDayCutoff())
                .atZone(ZoneId.of(policy.timezoneId())).toInstant();
        Instant end = capture.endedAt() == null ? receivedAt : capture.endedAt();
        if (end.isAfter(cutoff)) end = cutoff;
        if ((capture.endedAt() != null || !receivedAt.isBefore(cutoff))
                && receivedAt.isAfter(end.plusSeconds(policy.offlineUploadDeadlineMinutes() * 60L)))
            throw badRequest("已超过原定位会话补传期限");
        for (var point : command.points()) {
            if (point.clientOccurredAt() == null || point.clientOccurredAt().isBefore(capture.startedAt().minusSeconds(60))
                    || point.clientOccurredAt().isAfter(end.plusSeconds(60)) || !point.clientOccurredAt().isBefore(cutoff))
                throw badRequest("定位点不在原作业时段内");
        }

        List<LocationPointCommand> uniquePoints = uniquePoints(command.points());
        Set<String> existingIds =
                new HashSet<>(
                        repository.findExistingLocationEventIds(
                                caller.tenantId(),
                                uniquePoints.stream()
                                        .map(LocationPointCommand::deviceEventId)
                                        .toList()));
        int duplicateCount = command.points().size() - uniquePoints.size();
        int acceptedCount = 0;
        for (LocationPointCommand point : uniquePoints) {
            if (existingIds.contains(point.deviceEventId())) {
                duplicateCount++;
                continue;
            }
            repository.insertLocationPoint(
                    UUID.randomUUID(),
                    caller.tenantId(),
                    sessionId,
                    workDay.id(),
                    point.deviceEventId(),
                    point.longitude(),
                    point.latitude(),
                    point.accuracyMeters(),
                    point.clientOccurredAt(),
                    receivedAt,
                    normalizeSource(point.source()),
                    qualityStatus(point.accuracyMeters(), policy), point.rawLongitude(), point.rawLatitude(), point.rawCoordinateSystem());
            acceptedCount++;
        }
        if (acceptedCount > 0) {
            if (repository.incrementLocationPointCount(
                            caller.tenantId(),
                            workDay.id(),
                            sessionId,
                            acceptedCount)
                    != 1) {
                throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
            }
            repository.incrementWorkDayVersion(caller.tenantId(), workDay.id());
            appendOutbox(
                    caller,
                    workDay.id(),
                    "SalesLocationPointsReceived",
                    Map.of(
                            "workDayId",
                            workDay.id().toString(),
                            "acceptedCount",
                            acceptedCount,
                            "duplicateCount",
                            duplicateCount,
                            "receivedAt",
                            receivedAt.toString()));
            appendAudit(
                    caller,
                    "SALES_LOCATION_POINTS_RECEIVED",
                    workDay.id(),
                    Map.of(
                            "acceptedCount", Integer.toString(acceptedCount),
                            "duplicateCount", Integer.toString(duplicateCount)));
        }
        String reference =
                batchReference(workDay.id(), acceptedCount, duplicateCount, 0, receivedAt);
        complete(caller, "LOCATION_BATCH", key, reference);
        return new LocationBatchResult(workDay.id(), acceptedCount, duplicateCount, 0, receivedAt);
    }

    @Transactional
    public WorkDayView checkOut(UUID workDayId, CheckOutCommand command) {
        CallerIdentity caller = requireCaller("sales:work-day:write");
        validateCheckOutCommand(workDayId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay =
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        FieldPolicy policy =
                contextService.resolvePolicyVersion(
                        caller.tenantId(), workDay.fieldPolicyVersionId());
        validateLocation(command.location(), true);
        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "CHECK_OUT", key, command);
        if (reservation.status() == Status.COMPLETED)
            return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);
        if (!"ACTIVE".equals(workDay.status())) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        if (visitRepository
                .findActiveVisit(caller.tenantId(), identity.profile().id())
                .isPresent()) {
            throw new BusinessException(
                    ErrorCode.SALES_WORK_DAY_INVALID_STATE, "请先结束进行中的拜访再签退工作日", List.of());
        }
        ensureCurrentWorkDay(workDay, policy, receivedAt);
        int verifiedMinutes = totalWorkMinutes(caller.tenantId(), workDay, receivedAt);
        repository.insertPunchEvent(
                UUID.randomUUID(),
                caller.tenantId(),
                workDay.id(),
                "CHECK_OUT",
                deviceEventKey("check-out", key),
                command.clientOccurredAt(),
                receivedAt,
                longitude(command.location()),
                latitude(command.location()),
                accuracy(command.location()),
                bounded(command.deviceIdHash(), 128),
                bounded(command.networkType(), 32),
                policy.id());
        if (repository.finishWorkDay(caller.tenantId(), workDay.id(), receivedAt, verifiedMinutes)
                != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        if (workDay.locationSessionId() != null
                && repository.closeLocationSession(
                                caller.tenantId(),
                                workDay.id(),
                                workDay.locationSessionId(),
                                receivedAt)
                        != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        repository.insertWorkDaySummary(
                UUID.randomUUID(),
                caller.tenantId(),
                workDay.id(),
                workDay.checkedInAt(),
                receivedAt,
                verifiedMinutes,
                workDay.locationPointCount(),
                workDay.interruptionCount(),
                "PENDING",
                receivedAt,
                repository.nextSummaryVersion(caller.tenantId(), workDay.id()));

        appendOutbox(
                caller,
                workDay.id(),
                "SalesWorkDayFinalized",
                Map.of(
                        "workDayId", workDay.id().toString(),
                        "summaryStatus", "PENDING_REVIEW",
                        "verifiedWorkMinutes", verifiedMinutes,
                        "locationPointCount", workDay.locationPointCount(),
                        "interruptionCount", workDay.interruptionCount(),
                        "fieldPolicyVersionId", policy.id().toString(),
                        "finalizedAt", receivedAt.toString()));
        appendAudit(
                caller,
                "SALES_WORK_DAY_CHECK_OUT",
                workDay.id(),
                Map.of(
                        "verifiedWorkMinutes", Integer.toString(verifiedMinutes),
                        "fieldPolicyVersionId", policy.id().toString()));
        complete(caller, "CHECK_OUT", key, workDay.id().toString());
        return view(
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), workDay.id())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    @Transactional
    public WorkDayView reportInterruption(UUID workDayId, InterruptionCommand command) {
        CallerIdentity caller = requireCaller("sales:location:write");
        validateInterruptionCommand(workDayId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay =
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        if (workDay.locationSessionId() == null) {
            throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID, "当前工作日没有定位会话", List.of());
        }
        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "LOCATION_INTERRUPTION", key, command);
        if (reservation.status() == Status.COMPLETED)
            return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);
        if (!"ACTIVE".equals(workDay.status())) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        Integer duration = command.durationSeconds();
        if (command.endedAt() != null) {
            duration =
                    Math.toIntExact(
                            Duration.between(command.startedAt(), command.endedAt()).getSeconds());
        }
        repository.insertInterruption(
                UUID.randomUUID(),
                caller.tenantId(),
                workDay.id(),
                normalizedType(command.interruptionType()),
                command.startedAt(),
                command.endedAt(),
                duration,
                bounded(command.clientDetail(), 512));
        if (repository.incrementInterruptionCount(
                        caller.tenantId(), workDay.id(), workDay.locationSessionId())
                != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        repository.incrementWorkDayVersion(caller.tenantId(), workDay.id());
        appendOutbox(
                caller,
                workDay.id(),
                "SalesLocationInterrupted",
                Map.of(
                        "workDayId", workDay.id().toString(),
                        "interruptionType", normalizedType(command.interruptionType()),
                        "startedAt", command.startedAt().toString(),
                        "endedAt", command.endedAt() == null ? "" : command.endedAt().toString()));
        appendAudit(
                caller,
                "SALES_LOCATION_INTERRUPTED",
                workDay.id(),
                Map.of("interruptionType", normalizedType(command.interruptionType())));
        complete(caller, "LOCATION_INTERRUPTION", key, workDay.id().toString());
        return view(
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), workDay.id())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    public com.rigour.sales.api.v1.model.SalesWorkApiModels.AttendanceContextView attendanceContext() {
        var caller=requireCaller("sales:context:read");
        var now=clock.instant();
        var identity=contextService.resolveIdentity(caller,now);
        expiry.expireOwn(caller.tenantId(),identity.profile().id());
        var policy=contextService.resolvePolicy(caller.tenantId(),identity.profile(),now);
        var date=businessDate(now,policy);
        var day=repository.findWorkDay(caller.tenantId(),identity.profile().id(),date).map(this::view).orElse(null);
        if(day!=null) policy=contextService.resolvePolicyVersion(caller.tenantId(),day.fieldPolicyVersionId());
        var until=date.plusDays(1).atTime(policy.businessDayCutoff()).atZone(java.time.ZoneId.of(policy.timezoneId())).toInstant();
        return new com.rigour.sales.api.v1.model.SalesWorkApiModels.AttendanceContextView(date,until,now,
                policy.locationEnabled(),policy.locationIntervalMinutes(),policy.offlineUploadDeadlineMinutes(),policy.allowResume(),day);
    }

    public WorkDayView workDay(LocalDate date) {
        CallerIdentity caller = requireCaller("sales:context:read");
        if (date == null) throw badRequest("业务日期不能为空");
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, clock.instant());
        return view(
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), date)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    /** 销售本人月度出勤事实。当前没有HR排班/请假投影，因此无工作日记录只返回NO_RECORD， 绝不在Sales Work内推断休息或旷工。 */
    public AttendanceMonthView attendanceMonth(String monthValue) {
        CallerIdentity caller = requireCaller("sales:context:read");
        YearMonth month;
        try {
            month = YearMonth.parse(monthValue);
        } catch (RuntimeException error) {
            throw badRequest("month必须使用yyyy-MM格式");
        }
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, clock.instant());
        FieldPolicy currentPolicy =
                contextService.resolvePolicy(
                        caller.tenantId(), identity.profile(), clock.instant());
        LocalDate today = businessDate(clock.instant(), currentPolicy);
        LocalDate from = month.atDay(1);
        LocalDate to = month.atEndOfMonth();
        Map<LocalDate, WorkDaySnapshot> recorded = new java.util.HashMap<>();
        for (WorkDaySnapshot row :
                repository.findWorkDays(caller.tenantId(), identity.profile().id(), from, to)) {
            recorded.put(row.businessDate(), row);
        }
        List<AttendanceMonthDayView> days = new ArrayList<>(month.lengthOfMonth());
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            WorkDaySnapshot row = recorded.get(date);
            if (row == null) {
                days.add(
                        new AttendanceMonthDayView(
                                date,
                                null,
                                "UNKNOWN",
                                date.isAfter(today) ? "FUTURE" : "NO_RECORD",
                                null,
                                null,
                                0,
                                null,
                                null));
                continue;
            }
            FieldPolicy pinned =
                    contextService.resolvePolicyVersion(
                            caller.tenantId(), row.fieldPolicyVersionId());
            days.add(
                    new AttendanceMonthDayView(
                            date,
                            row.id(),
                            "WORK_RECORDED",
                            attendanceStatus(row, date, today, pinned.workHoursEnabled() ? pinned.minimumWorkMinutes() : 0),
                            row.checkedInAt(),
                            row.checkedOutAt(),
                            row.verifiedWorkMinutes(),
                            pinned.minimumWorkMinutes(),
                            row.evidenceQuality()));
        }
        return new AttendanceMonthView(month.toString(), today, days);
    }

    static String attendanceStatus(
            WorkDaySnapshot row, LocalDate date, LocalDate today, int minimumWorkMinutes) {
        if ("ACTIVE".equals(row.status())) {
            return date.isBefore(today) ? "MISSING_CHECK_OUT" : "IN_PROGRESS";
        }
        if (row.checkedOutAt() == null) return "MISSING_CHECK_OUT";
        if ("PENDING_REVIEW".equals(row.status())) return "PENDING_REVIEW";
        return row.verifiedWorkMinutes() >= minimumWorkMinutes ? "MEETS_MINIMUM" : "SHORT";
    }

    /** 本人当日轨迹：定位点 + 签到/签退打点；仅限本人工作日，精确位置不出租户。 */
    public WorkDayTrackView track(LocalDate date) {
        CallerIdentity caller = requireCaller("sales:track:own:read");
        if (date == null) throw badRequest("业务日期不能为空");
        SalesWorkContextService.SalesIdentity identity =
                contextService.resolveIdentity(caller, clock.instant());
        return buildTrack(caller.tenantId(), identity.profile().id(), date);
    }

    /** 管理端精确轨迹查询；独立敏感权限控制并记录查看审计。 */
    public WorkDayTrackView managementTrack(UUID salesProfileId, LocalDate date) {
        CallerIdentity caller = requireCaller("sales:location:sensitive:read");
        if (salesProfileId == null || date == null) throw badRequest("销售人员和业务日期不能为空");
        fieldwork.requireProfile(salesProfileId, "sales:location:sensitive:read");
        WorkDayTrackView result = buildTrack(caller.tenantId(), salesProfileId, date);
        appendAudit(
                caller,
                "SALES_LOCATION_SENSITIVE_READ",
                result.workDayId(),
                Map.of(
                        "salesProfileId",
                        salesProfileId.toString(),
                        "businessDate",
                        date.toString()));
        return result;
    }

    private WorkDayTrackView buildTrack(UUID tenantId, UUID salesProfileId, LocalDate date) {
        WorkDaySnapshot workDay =
                repository
                        .findWorkDay(tenantId, salesProfileId, date)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        var pointRows = repository.findLocationPoints(tenantId, workDay.id());
        var policy = contextService.resolvePolicyVersion(tenantId, workDay.fieldPolicyVersionId());
        var metrics = SalesTrackMetrics.calculate(pointRows, policy.locationIntervalMinutes());
        List<TrackPointView> points =
                pointRows.stream()
                        .map(
                                row ->
                                        new TrackPointView(
                                                row.longitude(),
                                                row.latitude(),
                                                row.accuracyMeters(),
                                                row.clientOccurredAt(),
                                                row.serverReceivedAt(),
                                                row.source(),
                                                row.qualityStatus()))
                        .toList();
        List<TrackPunchView> punches =
                repository.findPunchEvents(tenantId, workDay.id()).stream()
                        .map(
                                row ->
                                        new TrackPunchView(
                                                row.eventType(),
                                                row.clientOccurredAt(),
                                                row.serverReceivedAt(),
                                                row.longitude(),
                                                row.latitude(),
                                                row.accuracyMeters(),
                                                row.evidenceStatus(), row.resumeReason()))
                        .toList();
        List<VisitSnapshot> visitRows =
                visitRepository.findVisitsByWorkDay(tenantId, salesProfileId, workDay.id()).stream()
                        .filter(visit -> !"CANCELLED".equals(visit.status()))
                        .sorted(java.util.Comparator.comparing(VisitSnapshot::checkedInAt).thenComparing(VisitSnapshot::id))
                        .toList();
        Map<UUID, VisitTargetSnapshot> targets = new java.util.HashMap<>();
        for (VisitTargetSnapshot target :
                visitRepository.findTargetSnapshots(
                        tenantId, visitRows.stream().map(VisitSnapshot::id).toList())) {
            targets.put(target.visitId(), target);
        }
        var revisited = visitRows.isEmpty() ? java.util.Set.<UUID>of()
                : visitRepository.findRevisitedVisitIds(tenantId, salesProfileId, workDay.id());
        List<TrackVisitView> visits = new ArrayList<>();
        for (int index = 0; index < visitRows.size(); index++) {
            VisitSnapshot visit = visitRows.get(index);
            VisitTargetSnapshot target = targets.get(visit.id());
            if (target == null) continue;
            int dwellMinutes =
                    (int)
                            Math.max(
                                    0,
                                    Duration.between(
                                                    visit.checkedInAt(),
                                                    visit.checkedOutAt() == null
                                                            ? clock.instant()
                                                            : visit.checkedOutAt())
                                            .toMinutes());
            String visitType = revisited.contains(visit.id()) ? "REVISIT" : "FIRST_VISIT";
            visits.add(
                    new TrackVisitView(
                            visit.id(),
                            index + 1,
                            visit.storeId(),
                            target.storeName(),
                            target.longitude(),
                            target.latitude(),
                            visit.status(),
                            visit.checkedInAt(),
                            visit.checkedOutAt(),
                            dwellMinutes,
                            visitType,
                            reviewStatus(visit), target.storeAddress()));
        }
        List<TrackTravelSegmentView> segments =
                travelSegments(visitRows, targets, pointRows, policy.locationIntervalMinutes());
        return new WorkDayTrackView(
                workDay.id(),
                workDay.businessDate(),
                workDay.status(),
                metrics.distanceMeters(),
                metrics.trackedMinutes(),
                points,
                punches,
                visits,
                segments, metrics.available(), metrics.rejected(), metrics.legs(), metrics.gaps());
    }

    private static List<TrackTravelSegmentView> travelSegments(
            List<VisitSnapshot> visits,
            Map<UUID, VisitTargetSnapshot> targets,
            List<SalesWorkAttendanceRepository.LocationPointRow> points, int expectedIntervalMinutes) {
        List<TrackTravelSegmentView> segments = new ArrayList<>();
        for (int index = 1; index < visits.size(); index++) {
            VisitSnapshot fromVisit = visits.get(index - 1);
            VisitSnapshot toVisit = visits.get(index);
            VisitTargetSnapshot fromTarget = targets.get(fromVisit.id());
            VisitTargetSnapshot toTarget = targets.get(toVisit.id());
            if (fromTarget == null
                    || toTarget == null
                    || fromVisit.checkedOutAt() == null
                    || toVisit.checkedInAt() == null) continue;
            Instant from = fromVisit.checkedOutAt();
            Instant to = toVisit.checkedInAt();
            List<SalesWorkAttendanceRepository.LocationPointRow> segmentPoints =
                    points.stream()
                            .filter(
                                    point ->
                                            !pointTime(point).isBefore(from)
                                                    && !pointTime(point).isAfter(to))
                            .toList();
            var measured = SalesTrackMetrics.calculate(segmentPoints, expectedIntervalMinutes);
            segments.add(
                    new TrackTravelSegmentView(
                            fromVisit.id(),
                            toVisit.id(),
                            index,
                            index + 1,
                            fromTarget.storeName(),
                            toTarget.storeName(),
                            measured.distanceMeters(),
                            (int) Math.max(0, Duration.between(from, to).toMinutes()),
                            measured.available() ? "OBSERVED_ESTIMATE" : "UNAVAILABLE"));
        }
        return List.copyOf(segments);
    }

    private static Instant pointTime(SalesWorkAttendanceRepository.LocationPointRow point) {
        return point.clientOccurredAt() == null
                ? point.serverReceivedAt()
                : point.clientOccurredAt();
    }

    private static String reviewStatus(VisitSnapshot visit) {
        if (visit.checkedOutAt() == null) return "IN_PROGRESS";
        if (visit.finalizedAt() == null) return "PENDING_REVIEW";
        return "EFFECTIVE".equals(visit.finalReasonCode()) ? "EFFECTIVE" : "INEFFECTIVE";
    }

    private static double distanceMeters(
            BigDecimal longitude1,
            BigDecimal latitude1,
            BigDecimal longitude2,
            BigDecimal latitude2) {
        if (longitude1 == null || latitude1 == null || longitude2 == null || latitude2 == null)
            return 0;
        double lat1 = Math.toRadians(latitude1.doubleValue());
        double lat2 = Math.toRadians(latitude2.doubleValue());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2.doubleValue() - longitude1.doubleValue());
        double haversine =
                Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                        + Math.cos(lat1)
                                * Math.cos(lat2)
                                * Math.sin(deltaLon / 2)
                                * Math.sin(deltaLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    private record Coordinate(BigDecimal longitude, BigDecimal latitude) {}

    @Transactional
    public WorkDayView resumeWork(UUID workDayId, ResumeWorkCommand command) {
        var caller = requireCaller("sales:work-day:write");
        if (workDayId == null || command == null || !StringUtils.hasText(command.reason()) || command.reason().trim().length() > 500)
            throw badRequest("请填写恢复作业原因（最多500字）");
        validateCheckInCommand(new CheckInCommand(command.idempotencyKey(), command.clientInstanceId(),
                command.clientOccurredAt(), command.location(), command.deviceIdHash(), command.networkType()));
        Instant receivedAt = clock.instant();
        var identity = contextService.resolveIdentity(caller, receivedAt);
        var finished = repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        var pinned = contextService.resolvePolicyVersion(caller.tenantId(), finished.fieldPolicyVersionId());
        String key = key(command.idempotencyKey());
        var reservation = reserve(caller, "RESUME_WORK", key, command);
        if (reservation.status() == Status.COMPLETED) return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);
        ensureCurrentWorkDay(finished, pinned, receivedAt);
        if (!pinned.allowResume()) throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE, "当前规则不允许签退后恢复作业", List.of());
        validateLocation(command.location(), true);
        if (!"FINISHED".equals(finished.status())) {
            throw new BusinessException(
                    ErrorCode.SALES_WORK_DAY_INVALID_STATE, "当前状态不能恢复作业", List.of());
        }
        UUID sessionId = null;
        if (pinned.locationEnabled()) {
            sessionId = UUID.randomUUID();
        }
        if (repository.reopenWorkDay(caller.tenantId(), finished.id()) != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        repository.insertPunchEvent(
                UUID.randomUUID(),
                caller.tenantId(),
                finished.id(),
                "CHECK_IN",
                deviceEventKey("resume", key),
                command.clientOccurredAt(),
                receivedAt,
                longitude(command.location()),
                latitude(command.location()),
                accuracy(command.location()),
                bounded(command.deviceIdHash(), 128),
                bounded(command.networkType(), 32),
                finished.fieldPolicyVersionId());
        repository.setResumeReason(caller.tenantId(), finished.id(), deviceEventKey("resume", key), command.reason().trim());
        if (sessionId != null) {
            repository.insertLocationSession(
                    sessionId,
                    caller.tenantId(),
                    finished.id(),
                    receivedAt,
                    pinned.locationIntervalMinutes());
        }
        appendOutbox(
                caller,
                finished.id(),
                "SalesWorkDayReopened",
                Map.of(
                        "workDayId", finished.id().toString(),
                        identity.profile().hrEmployeeId() == null ? "employeeId" : "hrEmployeeId", identity.employeeReference(),
                        "salesProfileId", identity.profile().id().toString(),
                        "businessDate", finished.businessDate().toString(),
                        "reopenedAt", receivedAt.toString()));
        appendAudit(
                caller,
                "SALES_WORK_DAY_REOPEN",
                finished.id(),
                Map.of("businessDate", finished.businessDate().toString(), "reason", command.reason().trim()));
        complete(caller, "RESUME_WORK", key, finished.id().toString());
        return view(
                repository
                        .findWorkDay(caller.tenantId(), identity.profile().id(), finished.id())
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    private CallerIdentity requireCaller(String permission) {
        CallerIdentity caller = SalesWorkContextService.requireTenantCaller();
        AuthorizationContext.requirePermission(permission);
        return caller;
    }

    private Reservation reserve(
            CallerIdentity caller, String operation, String key, Object command) {
        return idempotencyStore.reserveCommand(
                caller.tenantId(),
                caller.userId(),
                operation,
                key,
                JdbcSalesIdempotencyStore.sha256(operation + "|" + writeJson(command)),
                IDEMPOTENCY_TTL);
    }

    private void complete(CallerIdentity caller, String operation, String key, String reference) {
        idempotencyStore.complete(
                new com.rigour.shared.idempotency.IdempotencyKey(
                        caller.tenantId().toString(), operation, key),
                reference);
    }

    private void throwIfNotReserved(Reservation reservation) {
        if (reservation.status() == Status.CONFLICT) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        }
        if (reservation.status() == Status.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_IN_PROGRESS);
        }
    }

    private WorkDayView replayWorkDay(
            CallerIdentity caller, UUID profileId, Reservation reservation) {
        UUID workDayId = parseReference(reservation.reference());
        return view(
                repository
                        .findWorkDay(caller.tenantId(), profileId, workDayId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    private LocationBatchResult replayLocationBatch(Reservation reservation) {
        String[] fields =
                reservation.reference() == null
                        ? new String[0]
                        : reservation.reference().split("\\|", -1);
        if (fields.length != 5) throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        try {
            return new LocationBatchResult(
                    UUID.fromString(fields[0]),
                    Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]),
                    Integer.parseInt(fields[3]),
                    Instant.parse(fields[4]));
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        }
    }

    private static String batchReference(
            UUID workDayId, int accepted, int duplicate, int rejected, Instant receivedAt) {
        return String.join(
                "|",
                workDayId.toString(),
                Integer.toString(accepted),
                Integer.toString(duplicate),
                Integer.toString(rejected),
                receivedAt.toString());
    }

    private void appendOutbox(
            CallerIdentity caller, UUID workDayId, String eventType, Map<String, Object> payload) {
        outboxStore.append(
                new OutboxMessage(
                        UUID.randomUUID(),
                        caller.tenantId().toString(),
                        "SALES_WORK_DAY",
                        workDayId.toString(),
                        eventType,
                        1,
                        writeJson(payload),
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private void appendAudit(
            CallerIdentity caller, String action, UUID workDayId, Map<String, String> attributes) {
        auditSink.append(
                new AuditEvent(
                        caller.tenantId().toString(),
                        RequestContext.getRequestId(),
                        caller.userId().toString(),
                        action,
                        "SALES_WORK_DAY",
                        workDayId.toString(),
                        attributes,
                        OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("销售工作请求序列化失败", error);
        }
    }

    private boolean expired(WorkDaySnapshot day) {
        if (!"ACTIVE".equals(day.status())) return false;
        var policy = contextService.resolvePolicyVersion(SalesWorkContextService.requireTenantCaller().tenantId(), day.fieldPolicyVersionId());
        return !clock.instant().isBefore(day.businessDate().plusDays(1).atTime(policy.businessDayCutoff()).atZone(zone(policy)).toInstant());
    }

    private WorkDayView view(WorkDaySnapshot snapshot) {
        var policy = contextService.resolvePolicyVersion(SalesWorkContextService.requireTenantCaller().tenantId(), snapshot.fieldPolicyVersionId());
        Instant deadline = policy.adjustmentDeadlineHours() == null ? null : snapshot.businessDate().plusDays(1)
                .atTime(policy.businessDayCutoff()).atZone(zone(policy)).toInstant().plusSeconds(policy.adjustmentDeadlineHours() * 3600L);
        return new WorkDayView(
                snapshot.id(),
                snapshot.employeeId(),
                snapshot.salesProfileId(),
                snapshot.businessDate(),
                snapshot.timezoneId(),
                snapshot.fieldPolicyVersionId(),
                expired(snapshot) ? "MISSING_CHECK_OUT" : snapshot.status(),
                snapshot.checkedInAt(),
                snapshot.checkedOutAt(),
                snapshot.locationSessionId(),
                snapshot.locationPointCount(),
                snapshot.interruptionCount(),
                snapshot.verifiedWorkMinutes(),
                snapshot.evidenceQuality(),
                snapshot.hrEmployeeId() == null ? null : snapshot.hrEmployeeId().toString(),
                workPeriods(SalesWorkContextService.requireTenantCaller().tenantId(), snapshot),
                totalWorkMinutes(SalesWorkContextService.requireTenantCaller().tenantId(), snapshot, clock.instant()),
                policy.allowAdjustment() && deadline != null && !clock.instant().isAfter(deadline), deadline);
    }

    private static LocalDate businessDate(Instant instant, FieldPolicy policy) {
        ZonedDateTime local = instant.atZone(zone(policy));
        if (policy.effectiveFrom() != null) {
            LocalDate firstDate = policy.effectiveFrom().atZone(zone(policy)).toLocalDate();
            Instant firstEnd = firstDate.plusDays(1).atTime(policy.businessDayCutoff()).atZone(zone(policy)).toInstant();
            if (!instant.isBefore(policy.effectiveFrom()) && instant.isBefore(firstEnd)) return firstDate;
        }
        return local.toLocalTime().isBefore(policy.businessDayCutoff())
                ? local.toLocalDate().minusDays(1)
                : local.toLocalDate();
    }

    private static void ensureCurrentWorkDay(WorkDaySnapshot day, FieldPolicy policy, Instant now) {
        Instant end = day.businessDate().plusDays(1).atTime(policy.businessDayCutoff()).atZone(zone(policy)).toInstant();
        if (!now.isBefore(end)) throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE, "该作业日已结束，请进入今日作业", List.of());
    }

    private List<WorkPeriodView> workPeriods(UUID tenantId, WorkDaySnapshot day) {
        var periods = new ArrayList<WorkPeriodView>();
        Instant start = null; String reason = null;
        for (var event : repository.findPunchEvents(tenantId, day.id())) {
            if ("CHECK_IN".equals(event.eventType())) { start = event.serverReceivedAt(); reason = event.resumeReason(); }
            else if ("CHECK_OUT".equals(event.eventType()) && start != null) {
                periods.add(new WorkPeriodView(start, event.serverReceivedAt(), reason)); start = null; reason = null;
            }
        }
        if (start != null) periods.add(new WorkPeriodView(start, null, reason));
        return List.copyOf(periods);
    }

    private int totalWorkMinutes(UUID tenantId, WorkDaySnapshot day, Instant now) {
        var policy = contextService.resolvePolicyVersion(tenantId, day.fieldPolicyVersionId());
        Instant cutoff = day.businessDate().plusDays(1).atTime(policy.businessDayCutoff()).atZone(zone(policy)).toInstant();
        long seconds = 0;
        for (var period : workPeriods(tenantId, day)) {
            Instant end = period.endedAt();
            if (end == null && "ACTIVE".equals(day.status()) && now.isBefore(cutoff)) end = now;
            if (end != null) seconds += Math.max(0, Duration.between(period.startedAt(), end).getSeconds());
        }
        return Math.toIntExact(seconds / 60);
    }

    private static ZoneId zone(FieldPolicy policy) {
        try {
            return ZoneId.of(policy.timezoneId());
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SALES_POLICY_NOT_FOUND);
        }
    }

    private static String qualityStatus(BigDecimal accuracy, FieldPolicy policy) {
        if (accuracy == null || policy.minimumLocationAccuracyMeters() == null) return "UNKNOWN";
        return accuracy.compareTo(policy.minimumLocationAccuracyMeters()) <= 0
                ? "ACCEPTED"
                : "LOW_ACCURACY";
    }

    private static List<LocationPointCommand> uniquePoints(List<LocationPointCommand> points) {
        Map<String, LocationPointCommand> unique = new LinkedHashMap<>();
        for (LocationPointCommand point : points) {
            LocationPointCommand previous = unique.putIfAbsent(point.deviceEventId(), point);
            if (previous != null && !previous.equals(point)) {
                throw new BusinessException(
                        ErrorCode.SALES_IDEMPOTENCY_CONFLICT, "同一设备事件包含不同定位内容", List.of());
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static void validateCheckInCommand(CheckInCommand command) {
        if (command == null) throw badRequest("签到请求不能为空");
        required(command.clientInstanceId(), "clientInstanceId", 128);
        required(command.idempotencyKey(), "idempotencyKey", 128);
        validateString(command.deviceIdHash(), 128, "deviceIdHash");
        validateString(command.networkType(), 32, "networkType");
    }

    private static void validateCheckOutCommand(UUID workDayId, CheckOutCommand command) {
        if (workDayId == null || command == null) throw badRequest("签退请求不能为空");
        required(command.idempotencyKey(), "idempotencyKey", 128);
        validateString(command.deviceIdHash(), 128, "deviceIdHash");
        validateString(command.networkType(), 32, "networkType");
    }

    private static void validateLocationBatchCommand(UUID workDayId, LocationBatchCommand command) {
        if (workDayId == null || command == null) throw badRequest("定位批次请求不能为空");
        required(command.idempotencyKey(), "idempotencyKey", 128);
        if (command.points().isEmpty()) throw badRequest("定位批次不能为空");
        if (command.points().size() > MAX_LOCATION_POINTS) {
            throw new BusinessException(ErrorCode.SALES_LOCATION_BATCH_TOO_LARGE);
        }
        for (LocationPointCommand point : command.points()) validatePoint(point);
    }

    private static void validatePoint(LocationPointCommand point) {
        if (point == null) throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID);
        required(point.deviceEventId(), "deviceEventId", 128);
        validateCoordinates(point.longitude(), point.latitude(), point.accuracyMeters());
        required(point.source(), "source", 24);
        if (point.rawLongitude()!=null || point.rawLatitude()!=null || point.rawCoordinateSystem()!=null) {
            validateCoordinates(point.rawLongitude(),point.rawLatitude(),point.accuracyMeters());
            if (!Set.of("WGS84","GCJ02").contains(point.rawCoordinateSystem()==null?"":point.rawCoordinateSystem()))
                throw badRequest("原始定位坐标系无效");
        }
    }

    private static void validateInterruptionCommand(UUID workDayId, InterruptionCommand command) {
        if (workDayId == null || command == null) throw badRequest("定位中断请求不能为空");
        required(command.idempotencyKey(), "idempotencyKey", 128);
        normalizedType(command.interruptionType());
        if (command.startedAt() == null) throw badRequest("startedAt不能为空");
        if (command.endedAt() != null && command.endedAt().isBefore(command.startedAt())) {
            throw badRequest("endedAt不能早于startedAt");
        }
        if (command.durationSeconds() != null && command.durationSeconds() < 0) {
            throw badRequest("durationSeconds不能为负数");
        }
        validateString(command.clientDetail(), 512, "clientDetail");
    }

    private static void validateLocation(LocationEvidence location, boolean enabled) {
        if (!enabled && location == null) return;
        if (location == null) throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID);
        validateCoordinates(location.longitude(), location.latitude(), location.accuracyMeters());
        required(location.source(), "source", 24);
    }

    private static void validateCoordinates(
            BigDecimal longitude, BigDecimal latitude, BigDecimal accuracy) {
        if (longitude == null
                || latitude == null
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || (accuracy != null && accuracy.signum() < 0)) {
            throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID);
        }
    }

    private static String normalizedType(String value) {
        String normalized = required(value, "interruptionType", 32).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]+")) throw badRequest("interruptionType格式无效");
        return normalized;
    }

    private static String normalizeSource(String value) {
        return required(value, "source", 24).toUpperCase(Locale.ROOT);
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

    private static void validateString(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) throw badRequest(field + "长度超限");
    }

    private static String bounded(String value, int maxLength) {
        return value == null ? null : value.substring(0, Math.min(maxLength, value.length()));
    }

    private static String deviceEventKey(String prefix, String key) {
        String value = prefix + ":" + key;
        return value.substring(0, Math.min(128, value.length()));
    }

    private static BigDecimal longitude(LocationEvidence location) {
        return location == null ? null : location.longitude();
    }

    private static BigDecimal latitude(LocationEvidence location) {
        return location == null ? null : location.latitude();
    }

    private static BigDecimal accuracy(LocationEvidence location) {
        return location == null ? null : location.accuracyMeters();
    }

    private static UUID parseReference(String reference) {
        try {
            return UUID.fromString(reference);
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        }
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
