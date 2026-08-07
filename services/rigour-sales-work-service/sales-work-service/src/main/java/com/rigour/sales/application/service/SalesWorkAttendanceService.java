package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckInCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.InterruptionCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationBatchResult;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationEvidence;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.WorkDayView;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository;
import com.rigour.sales.application.port.out.SalesWorkAttendanceRepository.WorkDaySnapshot;
import com.rigour.sales.application.port.out.SalesWorkQueryRepository.FieldPolicy;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.LocationPointCommand;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 阶段 2 外勤考勤用例；以 Sales Work 事实、规则版本和事务边界为准。 */
@Service
public class SalesWorkAttendanceService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(48);
    private static final int MAX_LOCATION_POINTS = 100;

    private final SalesWorkContextService contextService;
    private final SalesWorkAttendanceRepository repository;
    private final JdbcSalesIdempotencyStore idempotencyStore;
    private final OutboxStore outboxStore;
    private final AuditSink auditSink;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SalesWorkAttendanceService(SalesWorkContextService contextService,
                                      SalesWorkAttendanceRepository repository,
                                      JdbcSalesIdempotencyStore idempotencyStore,
                                      OutboxStore outboxStore,
                                      AuditSink auditSink,
                                      ObjectMapper objectMapper,
                                      Clock clock) {
        this.contextService = contextService;
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
        this.outboxStore = outboxStore;
        this.auditSink = auditSink;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public WorkDayView checkIn(CheckInCommand command) {
        CallerIdentity caller = requireCaller("sales:work-day:write");
        validateCheckInCommand(command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        FieldPolicy policy = contextService.resolvePolicy(caller.tenantId(), identity.profile(), receivedAt);
        validateLocation(command.location(), policy.locationEnabled());
        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "CHECK_IN", key, command);
        if (reservation.status() == Status.COMPLETED) return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);

        ensureWindow(policy, receivedAt, true);
        LocalDate businessDate = businessDate(receivedAt, policy);
        var existing = repository.findWorkDay(caller.tenantId(), identity.profile().id(), businessDate);
        if (existing.isPresent()) {
            if ("ACTIVE".equals(existing.get().status())) {
                throw new BusinessException(ErrorCode.SALES_WORK_DAY_ALREADY_ACTIVE);
            }
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE,
                    "当前业务日已经结束，不能重复签到", List.of());
        }

        UUID workDayId = UUID.randomUUID();
        UUID sessionId = policy.locationEnabled() ? UUID.randomUUID() : null;
        try {
            repository.insertWorkDay(workDayId, caller.tenantId(), identity.projection().employeeId(),
                    identity.profile().id(), businessDate, policy.timezoneId(), policy.id(), receivedAt);
            repository.insertPunchEvent(UUID.randomUUID(), caller.tenantId(), workDayId, "CHECK_IN",
                    deviceEventKey("check-in", key), command.clientOccurredAt(), receivedAt,
                    longitude(command.location()), latitude(command.location()), accuracy(command.location()),
                    bounded(command.deviceIdHash(), 128), bounded(command.networkType(), 32), policy.id());
            if (sessionId != null) {
                repository.insertLocationSession(sessionId, caller.tenantId(), workDayId, receivedAt,
                        policy.locationIntervalMinutes());
            }
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_ALREADY_ACTIVE);
        }

        appendOutbox(caller, workDayId, "SalesWorkDayCheckedIn", Map.of(
                "workDayId", workDayId.toString(),
                "employeeId", identity.projection().employeeId().toString(),
                "salesProfileId", identity.profile().id().toString(),
                "businessDate", businessDate.toString(),
                "fieldPolicyVersionId", policy.id().toString(),
                "checkedInAt", receivedAt.toString()));
        appendAudit(caller, "SALES_WORK_DAY_CHECK_IN", workDayId, Map.of(
                "businessDate", businessDate.toString(), "fieldPolicyVersionId", policy.id().toString()));
        complete(caller, "CHECK_IN", key, workDayId.toString());
        return view(repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    @Transactional
    public LocationBatchResult uploadLocationPoints(UUID workDayId, LocationBatchCommand command) {
        CallerIdentity caller = requireCaller("sales:location:write");
        validateLocationBatchCommand(workDayId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay = repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        FieldPolicy policy = contextService.resolvePolicyVersion(caller.tenantId(), workDay.fieldPolicyVersionId());
        if (!policy.locationEnabled() || workDay.locationSessionId() == null) {
            throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID, "当前工作日未启用定位会话", List.of());
        }

        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "LOCATION_BATCH", key, command);
        if (reservation.status() == Status.COMPLETED) return replayLocationBatch(reservation);
        throwIfNotReserved(reservation);
        if (!"ACTIVE".equals(workDay.status())) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }

        List<LocationPointCommand> uniquePoints = uniquePoints(command.points());
        Set<String> existingIds = new HashSet<>(repository.findExistingLocationEventIds(caller.tenantId(),
                uniquePoints.stream().map(LocationPointCommand::deviceEventId).toList()));
        int duplicateCount = command.points().size() - uniquePoints.size();
        int acceptedCount = 0;
        for (LocationPointCommand point : uniquePoints) {
            if (existingIds.contains(point.deviceEventId())) {
                duplicateCount++;
                continue;
            }
            repository.insertLocationPoint(UUID.randomUUID(), caller.tenantId(), workDay.locationSessionId(),
                    workDay.id(), point.deviceEventId(), point.longitude(), point.latitude(),
                    point.accuracyMeters(), point.clientOccurredAt(), receivedAt,
                    normalizeSource(point.source()), qualityStatus(point.accuracyMeters(), policy));
            acceptedCount++;
        }
        if (acceptedCount > 0) {
            if (repository.incrementLocationPointCount(caller.tenantId(), workDay.id(),
                    workDay.locationSessionId(), acceptedCount) != 1) {
                throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
            }
            repository.incrementWorkDayVersion(caller.tenantId(), workDay.id());
            appendOutbox(caller, workDay.id(), "SalesLocationPointsReceived", Map.of(
                    "workDayId", workDay.id().toString(), "acceptedCount", acceptedCount,
                    "duplicateCount", duplicateCount, "receivedAt", receivedAt.toString()));
            appendAudit(caller, "SALES_LOCATION_POINTS_RECEIVED", workDay.id(), Map.of(
                    "acceptedCount", Integer.toString(acceptedCount),
                    "duplicateCount", Integer.toString(duplicateCount)));
        }
        String reference = batchReference(workDay.id(), acceptedCount, duplicateCount, 0, receivedAt);
        complete(caller, "LOCATION_BATCH", key, reference);
        return new LocationBatchResult(workDay.id(), acceptedCount, duplicateCount, 0, receivedAt);
    }

    @Transactional
    public WorkDayView checkOut(UUID workDayId, CheckOutCommand command) {
        CallerIdentity caller = requireCaller("sales:work-day:write");
        validateCheckOutCommand(workDayId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay = repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        FieldPolicy policy = contextService.resolvePolicyVersion(caller.tenantId(), workDay.fieldPolicyVersionId());
        validateLocation(command.location(), policy.locationEnabled());
        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "CHECK_OUT", key, command);
        if (reservation.status() == Status.COMPLETED) return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);
        if (!"ACTIVE".equals(workDay.status())) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        ensureWindow(policy, receivedAt, false);
        int verifiedMinutes = elapsedMinutes(workDay.checkedInAt(), receivedAt);
        repository.insertPunchEvent(UUID.randomUUID(), caller.tenantId(), workDay.id(), "CHECK_OUT",
                deviceEventKey("check-out", key), command.clientOccurredAt(), receivedAt,
                longitude(command.location()), latitude(command.location()), accuracy(command.location()),
                bounded(command.deviceIdHash(), 128), bounded(command.networkType(), 32), policy.id());
        if (repository.finishWorkDay(caller.tenantId(), workDay.id(), receivedAt, verifiedMinutes) != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        if (workDay.locationSessionId() != null
                && repository.closeLocationSession(caller.tenantId(), workDay.id(), workDay.locationSessionId(), receivedAt) != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        repository.insertWorkDaySummary(UUID.randomUUID(), caller.tenantId(), workDay.id(),
                workDay.checkedInAt(), receivedAt, verifiedMinutes, workDay.locationPointCount(),
                workDay.interruptionCount(), "PENDING", receivedAt);

        appendOutbox(caller, workDay.id(), "SalesWorkDayFinalized", Map.of(
                "workDayId", workDay.id().toString(),
                "summaryStatus", "PENDING_REVIEW",
                "verifiedWorkMinutes", verifiedMinutes,
                "locationPointCount", workDay.locationPointCount(),
                "interruptionCount", workDay.interruptionCount(),
                "fieldPolicyVersionId", policy.id().toString(),
                "finalizedAt", receivedAt.toString()));
        appendAudit(caller, "SALES_WORK_DAY_CHECK_OUT", workDay.id(), Map.of(
                "verifiedWorkMinutes", Integer.toString(verifiedMinutes),
                "fieldPolicyVersionId", policy.id().toString()));
        complete(caller, "CHECK_OUT", key, workDay.id().toString());
        return view(repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDay.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    @Transactional
    public WorkDayView reportInterruption(UUID workDayId, InterruptionCommand command) {
        CallerIdentity caller = requireCaller("sales:location:write");
        validateInterruptionCommand(workDayId, command);
        Instant receivedAt = clock.instant();
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, receivedAt);
        WorkDaySnapshot workDay = repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDayId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND));
        if (workDay.locationSessionId() == null) {
            throw new BusinessException(ErrorCode.SALES_LOCATION_INVALID, "当前工作日没有定位会话", List.of());
        }
        String key = key(command.idempotencyKey());
        Reservation reservation = reserve(caller, "LOCATION_INTERRUPTION", key, command);
        if (reservation.status() == Status.COMPLETED) return replayWorkDay(caller, identity.profile().id(), reservation);
        throwIfNotReserved(reservation);
        if (!"ACTIVE".equals(workDay.status())) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        Integer duration = command.durationSeconds();
        if (command.endedAt() != null) {
            duration = Math.toIntExact(Duration.between(command.startedAt(), command.endedAt()).getSeconds());
        }
        repository.insertInterruption(UUID.randomUUID(), caller.tenantId(), workDay.id(),
                normalizedType(command.interruptionType()), command.startedAt(), command.endedAt(), duration,
                bounded(command.clientDetail(), 512));
        if (repository.incrementInterruptionCount(caller.tenantId(), workDay.id(),
                workDay.locationSessionId()) != 1) {
            throw new BusinessException(ErrorCode.SALES_WORK_DAY_INVALID_STATE);
        }
        repository.incrementWorkDayVersion(caller.tenantId(), workDay.id());
        appendOutbox(caller, workDay.id(), "SalesLocationInterrupted", Map.of(
                "workDayId", workDay.id().toString(),
                "interruptionType", normalizedType(command.interruptionType()),
                "startedAt", command.startedAt().toString(),
                "endedAt", command.endedAt() == null ? "" : command.endedAt().toString()));
        appendAudit(caller, "SALES_LOCATION_INTERRUPTED", workDay.id(), Map.of(
                "interruptionType", normalizedType(command.interruptionType())));
        complete(caller, "LOCATION_INTERRUPTION", key, workDay.id().toString());
        return view(repository.findWorkDay(caller.tenantId(), identity.profile().id(), workDay.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    public WorkDayView workDay(LocalDate date) {
        CallerIdentity caller = requireCaller("sales:context:read");
        if (date == null) throw badRequest("业务日期不能为空");
        SalesWorkContextService.SalesIdentity identity = contextService.resolveIdentity(caller, clock.instant());
        return view(repository.findWorkDay(caller.tenantId(), identity.profile().id(), date)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
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

    private WorkDayView replayWorkDay(CallerIdentity caller, UUID profileId, Reservation reservation) {
        UUID workDayId = parseReference(reservation.reference());
        return view(repository.findWorkDay(caller.tenantId(), profileId, workDayId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SALES_WORK_DAY_NOT_FOUND)));
    }

    private LocationBatchResult replayLocationBatch(Reservation reservation) {
        String[] fields = reservation.reference() == null ? new String[0] : reservation.reference().split("\\|", -1);
        if (fields.length != 5) throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        try {
            return new LocationBatchResult(UUID.fromString(fields[0]), Integer.parseInt(fields[1]),
                    Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), Instant.parse(fields[4]));
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT);
        }
    }

    private static String batchReference(UUID workDayId, int accepted, int duplicate, int rejected,
                                         Instant receivedAt) {
        return String.join("|", workDayId.toString(), Integer.toString(accepted), Integer.toString(duplicate),
                Integer.toString(rejected), receivedAt.toString());
    }

    private void appendOutbox(CallerIdentity caller, UUID workDayId, String eventType,
                              Map<String, Object> payload) {
        outboxStore.append(new OutboxMessage(UUID.randomUUID(), caller.tenantId().toString(),
                "SALES_WORK_DAY", workDayId.toString(), eventType, 1, writeJson(payload),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private void appendAudit(CallerIdentity caller, String action, UUID workDayId,
                             Map<String, String> attributes) {
        auditSink.append(new AuditEvent(caller.tenantId().toString(), RequestContext.getRequestId(),
                caller.userId().toString(), action, "SALES_WORK_DAY", workDayId.toString(), attributes,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException error) {
            throw new IllegalStateException("销售工作请求序列化失败", error);
        }
    }

    private static WorkDayView view(WorkDaySnapshot snapshot) {
        return new WorkDayView(snapshot.id(), snapshot.employeeId(), snapshot.salesProfileId(),
                snapshot.businessDate(), snapshot.timezoneId(), snapshot.fieldPolicyVersionId(),
                snapshot.status(), snapshot.checkedInAt(), snapshot.checkedOutAt(),
                snapshot.locationSessionId(), snapshot.locationPointCount(), snapshot.interruptionCount(),
                snapshot.verifiedWorkMinutes(), snapshot.evidenceQuality());
    }

    private static LocalDate businessDate(Instant instant, FieldPolicy policy) {
        ZonedDateTime local = instant.atZone(zone(policy));
        return local.toLocalTime().isBefore(policy.businessDayCutoff())
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    private static void ensureWindow(FieldPolicy policy, Instant instant, boolean checkIn) {
        LocalTime time = instant.atZone(zone(policy)).toLocalTime();
        LocalTime start = checkIn ? policy.checkInWindowStart() : policy.checkOutWindowStart();
        LocalTime end = checkIn ? policy.checkInWindowEnd() : policy.checkOutWindowEnd();
        if (start != null && end != null && !withinWindow(time, start, end)) {
            throw new BusinessException(checkIn ? ErrorCode.SALES_CHECK_IN_OUTSIDE_WINDOW
                    : ErrorCode.SALES_CHECK_OUT_OUTSIDE_WINDOW);
        }
    }

    private static boolean withinWindow(LocalTime time, LocalTime start, LocalTime end) {
        return start.compareTo(end) <= 0
                ? !time.isBefore(start) && !time.isAfter(end)
                : !time.isBefore(start) || !time.isAfter(end);
    }

    private static ZoneId zone(FieldPolicy policy) {
        try {
            return ZoneId.of(policy.timezoneId());
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.SALES_POLICY_NOT_FOUND);
        }
    }

    private static int elapsedMinutes(Instant start, Instant end) {
        if (start == null || end.isBefore(start)) return 0;
        long minutes = Duration.between(start, end).toMinutes();
        return (int) Math.min(Integer.MAX_VALUE, minutes);
    }

    private static String qualityStatus(BigDecimal accuracy, FieldPolicy policy) {
        if (accuracy == null || policy.minimumLocationAccuracyMeters() == null) return "UNKNOWN";
        return accuracy.compareTo(policy.minimumLocationAccuracyMeters()) <= 0 ? "ACCEPTED" : "LOW_ACCURACY";
    }

    private static List<LocationPointCommand> uniquePoints(List<LocationPointCommand> points) {
        Map<String, LocationPointCommand> unique = new LinkedHashMap<>();
        for (LocationPointCommand point : points) {
            LocationPointCommand previous = unique.putIfAbsent(point.deviceEventId(), point);
            if (previous != null && !previous.equals(point)) {
                throw new BusinessException(ErrorCode.SALES_IDEMPOTENCY_CONFLICT,
                        "同一设备事件包含不同定位内容", List.of());
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

    private static void validateCoordinates(BigDecimal longitude, BigDecimal latitude, BigDecimal accuracy) {
        if (longitude == null || latitude == null
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
