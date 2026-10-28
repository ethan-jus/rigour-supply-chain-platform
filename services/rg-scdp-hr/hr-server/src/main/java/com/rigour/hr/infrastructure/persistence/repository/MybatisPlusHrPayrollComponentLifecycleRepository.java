package com.rigour.hr.infrastructure.persistence.repository;

import static com.rigour.hr.infrastructure.persistence.repository.HrPayrollPersistenceSupport.error;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollComponentLifecycleStore;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.core.api.ErrorCode;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Repository
public class MybatisPlusHrPayrollComponentLifecycleRepository
        implements HrPayrollComponentLifecycleStore {
    private final HrPayrollComponentMapper rules;
    private final HrPayrollComponentOperationMapper operations;
    private final HrPayrollComponentReviewMapper reviews;
    private final HrPayrollMapper payroll;
    private final HrPayrollSchemeMapper schemes;
    private final HrAttendanceNoteMapper attendance;
    private final HrPayrollPersistenceSupport locks;
    private final ObjectMapper json;

    public MybatisPlusHrPayrollComponentLifecycleRepository(
            HrPayrollComponentMapper rules,
            HrPayrollComponentOperationMapper operations,
            HrPayrollComponentReviewMapper reviews,
            HrPayrollMapper payroll,
            HrPayrollSchemeMapper schemes,
            HrAttendanceNoteMapper attendance,
            HrPayrollPersistenceSupport locks,
            ObjectMapper json) {
        this.rules = rules;
        this.operations = operations;
        this.reviews = reviews;
        this.payroll = payroll;
        this.schemes = schemes;
        this.attendance = attendance;
        this.locks = locks;
        this.json = json;
    }

    public Optional<PayrollComponentActionResult> byRequest(String tenant, String requestId) {
        return Optional.ofNullable(
                        operations.selectOne(
                                new QueryWrapper<HrPayrollComponentOperationEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("request_id", requestId)))
                .map(row -> json.readValue(row.responseJson, PayrollComponentActionResult.class));
    }

    public void lock(String tenant) {
        locks.lock(tenant);
    }

    public Map<String, List<PayrollComponentStateChange>> states(String tenant) {
        var result = new HashMap<String, List<PayrollComponentStateChange>>();
        var rows =
                operations.selectList(
                        new QueryWrapper<HrPayrollComponentOperationEntity>()
                                .eq("tenant_id", tenant)
                                .isNotNull("state_revision")
                                .orderByAsc("id")
                                .last("LIMIT 10001"));
        if (rows.size() > 10000) throw error(ErrorCode.BAD_REQUEST, "规则状态记录超过查询上限");
        for (var row : rows) {
            if ("RETIRE".equals(row.actionCode)) {
                var snapshot = json.readValue(row.responseJson, PayrollComponentActionResult.class);
                var change =
                        snapshot.rule().stateChanges().stream()
                                .filter(c -> c.revision() == row.stateRevision)
                                .findFirst()
                                .orElseThrow();
                result.computeIfAbsent(row.ruleId, key -> new ArrayList<>()).add(change);
                continue;
            }
            result.computeIfAbsent(row.ruleId, key -> new ArrayList<>())
                    .add(
                            new PayrollComponentStateChange(
                                    row.stateRevision,
                                    row.effectiveMonth,
                                    "ENABLE".equals(row.actionCode),
                                    row.reason,
                                    row.actorId,
                                    row.createdTime.toInstant(ZoneOffset.UTC)));
        }
        return result;
    }

    public boolean inUse(String tenant, String id) {
        return payroll.selectCount(
                                new QueryWrapper<HrPayrollEntity>()
                                        .eq("tenant_id", tenant)
                                        .in("status_code", "CONFIRMED", "PAID")
                                        .apply(
                                                "JSON_SEARCH(calculation_json, 'one', {0}) IS NOT"
                                                        + " NULL",
                                                id))
                        > 0
                || schemes.selectCount(
                                new QueryWrapper<HrPayrollSchemeEntity>()
                                        .eq("tenant_id", tenant)
                                        .ne("status", "DRAFT")
                                        .apply(
                                                "JSON_SEARCH(data_json, 'one', {0}) IS NOT NULL",
                                                id))
                        > 0;
    }

    @Transactional
    public PayrollComponentActionResult act(
            String tenant,
            PayrollComponentVersionView rule,
            PayrollComponentActionCommand command,
            String actor,
            LocalDate today) {
        locks.lock(tenant);
        var retry = byRequest(tenant, command.requestId());
        if (retry.isPresent()) return retry.get();
        if (rules.selectCount(
                        new QueryWrapper<HrPayrollComponentEntity>()
                                .eq("tenant_id", tenant)
                                .eq("request_id", command.requestId()))
                > 0) throw error(ErrorCode.CONFLICT, "请求编号已使用，请刷新重试");
        var entity =
                rules.selectOne(
                        new QueryWrapper<HrPayrollComponentEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", rule.id()));
        if (entity == null
                || Boolean.TRUE.equals(entity.getDeleted())
                || !Objects.equals(entity.getRevision(), command.revision()))
            throw error(ErrorCode.CONFLICT, "规则已变更，请刷新后重试");
        var changes = states(tenant).getOrDefault(rule.ruleId(), List.of());
        int stateRevision =
                changes.stream().mapToInt(PayrollComponentStateChange::revision).max().orElse(0);
        if (stateRevision != command.stateRevision())
            throw error(ErrorCode.CONFLICT, "规则状态已变更，请刷新后重试");
        boolean delete = "DELETE".equals(command.action());
        if (delete
                && rules.update(
                                null,
                                new UpdateWrapper<HrPayrollComponentEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("id", rule.id())
                                        .eq("revision", command.revision())
                                        .eq("deleted", false)
                                        .set("deleted", true)
                                        .setSql("revision=revision+1"))
                        != 1) throw error(ErrorCode.CONFLICT, "规则已变更，请刷新后重试");
        var operation =
                operation(
                        tenant,
                        rule,
                        command.requestId(),
                        command.action(),
                        command.reason(),
                        actor);
        if (!delete) {
            operation.effectiveMonth = YearMonth.from(today).toString();
            operation.stateRevision = stateRevision + 1;
        }
        operation.responseJson = "{}";
        operations.insert(operation);
        var nextChanges = new ArrayList<>(changes);
        if (!delete)
            nextChanges.add(
                    new PayrollComponentStateChange(
                            operation.stateRevision,
                            operation.effectiveMonth,
                            "ENABLE".equals(command.action()),
                            command.reason(),
                            actor,
                            operation.createdTime.toInstant(ZoneOffset.UTC)));
        var updated =
                new PayrollComponentVersionView(
                        rule.id(),
                        rule.ruleId(),
                        rule.version(),
                        rule.definition(),
                        rule.reason(),
                        rule.createdBy(),
                        rule.createdTime(),
                        rule.ruleCode(),
                        rule.revision() + (delete ? 1 : 0),
                        delete,
                        nextChanges);
        int[] counts = delete ? new int[] {0, 0} : markReviews(tenant, updated, operation);
        var result =
                new PayrollComponentActionResult(
                        updated, counts[0], counts[1], command.action(), command.reason());
        operations.update(
                null,
                new UpdateWrapper<HrPayrollComponentOperationEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", operation.id)
                        .set("response_json", json.writeValueAsString(result)));
        locks.audit(tenant, rule.id(), "RULE_" + command.action(), actor, command.reason(), result);
        return result;
    }

    @Transactional
    public void retire(
            String tenant,
            PayrollComponentVersionView previous,
            PayrollComponentVersionView replacement,
            String actor) {
        locks.lock(tenant);
        var request =
                "retire:"
                        + UUID.nameUUIDFromBytes(
                                (replacement.id() + ":" + previous.id())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (byRequest(tenant, request).isPresent()) return;
        var changes = new ArrayList<>(states(tenant).getOrDefault(previous.ruleId(), List.of()));
        int revision =
                changes.stream().mapToInt(PayrollComponentStateChange::revision).max().orElse(0)
                        + 1;
        var departments =
                previous.definition().departmentIds().stream()
                        .filter(replacement.definition().departmentIds()::contains)
                        .toList();
        var from =
                replacement
                                .definition()
                                .effectiveFrom()
                                .isAfter(previous.definition().effectiveFrom())
                        ? replacement.definition().effectiveFrom()
                        : previous.definition().effectiveFrom();
        String reason = "由新规则 " + replacement.ruleCode() + " 替代，变更说明见新规则";
        var operation = operation(tenant, previous, request, "RETIRE", reason, actor);
        operation.stateRevision = revision;
        operation.effectiveMonth = YearMonth.from(from).toString();
        changes.add(
                new PayrollComponentStateChange(
                        revision,
                        operation.effectiveMonth,
                        false,
                        reason,
                        actor,
                        operation.createdTime.toInstant(ZoneOffset.UTC),
                        true,
                        departments));
        var updated =
                new PayrollComponentVersionView(
                        previous.id(),
                        previous.ruleId(),
                        previous.version(),
                        previous.definition(),
                        previous.reason(),
                        previous.createdBy(),
                        previous.createdTime(),
                        previous.ruleCode(),
                        previous.revision(),
                        previous.deleted(),
                        changes);
        operation.responseJson = "{}";
        operations.insert(operation);
        var counts = markReviews(tenant, updated, operation);
        var response =
                new PayrollComponentActionResult(updated, counts[0], counts[1], "RETIRE", reason);
        operations.update(
                null,
                new UpdateWrapper<HrPayrollComponentOperationEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", operation.id)
                        .set("response_json", json.writeValueAsString(response)));
        locks.audit(tenant, previous.id(), "RULE_RETIRED", actor, reason, response);
    }

    public void recordModification(
            String tenant,
            PayrollComponentSaveCommand command,
            PayrollComponentVersionView result,
            String actor) {
        var operation =
                operation(tenant, result, command.requestId(), "MODIFY", command.reason(), actor);
        operation.responseJson =
                json.writeValueAsString(
                        new PayrollComponentActionResult(result, 0, 0, "MODIFY", command.reason()));
        operations.insert(operation);
    }

    private HrPayrollComponentOperationEntity operation(
            String tenant,
            PayrollComponentVersionView rule,
            String request,
            String action,
            String reason,
            String actor) {
        var row = new HrPayrollComponentOperationEntity();
        row.tenantId = tenant;
        row.requestId = request;
        row.ruleId = rule.ruleId();
        row.versionId = rule.id();
        row.actionCode = action;
        row.reason = reason;
        row.actorId = actor;
        row.createdTime = LocalDateTime.now(ZoneOffset.UTC);
        return row;
    }

    private int[] markReviews(
            String tenant,
            PayrollComponentVersionView rule,
            HrPayrollComponentOperationEntity operation) {
        var d = rule.definition();
        var departments =
                "RETIRE".equals(operation.actionCode)
                        ? rule.stateChanges().stream()
                                .filter(c -> c.revision() == operation.stateRevision)
                                .findFirst()
                                .orElseThrow()
                                .replacedDepartmentIds()
                        : d.departmentIds();
        String from = operation.effectiveMonth;
        String to = d.effectiveTo().toString().substring(0, 7);
        int attendanceCount = 0;
        if ("ATTENDANCE".equals(d.kind())) {
            var rows =
                    attendance.selectList(
                            new QueryWrapper<HrAttendanceNoteEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("attendance_status", "CONFIRMED")
                                    .ge("attendance_month", from)
                                    .le("attendance_month", to)
                                    .in("confirmed_department_id", departments)
                                    .last("LIMIT 10001"));
            if (rows.size() > 10000) throw error(ErrorCode.BAD_REQUEST, "受影响考勤超过处理上限，请分批处理");
            for (var row : rows) {
                insertReview(
                        tenant,
                        rule,
                        operation,
                        "ATTENDANCE",
                        row.attendanceMonth + ":" + row.employeeCode,
                        row.employeeCode,
                        row.attendanceMonth);
                attendanceCount++;
            }
        }
        var q =
                new QueryWrapper<HrPayrollEntity>()
                        .eq("tenant_id", tenant)
                        .in("status_code", "CONFIRMED", "PAID")
                        .ge("payroll_month", from)
                        .le("payroll_month", to);
        if ("SALARY".equals(d.kind())) q.in("employee_code", d.employeeCodes());
        else q.in("department_id", departments);
        var payrollRows = payroll.selectList(q.last("LIMIT 10001"));
        if (payrollRows.size() > 10000) throw error(ErrorCode.BAD_REQUEST, "受影响薪酬超过处理上限，请分批处理");
        int payrollCount = 0;
        for (var row : payrollRows) {
            if (!"ALL".equals(d.role())) {
                var calculation =
                        json.readValue(row.getCalculationJson(), PayrollCalculationView.class);
                String role =
                        calculation.scheme() != null
                                ? calculation.scheme().scheme().definition().role()
                                : calculation.salarySource() == null
                                        ? null
                                        : calculation.salarySource().payrollCategory();
                // Legacy records without a role snapshot remain explicitly pending review.
                if (role != null && !d.role().equals(role)) continue;
            }
            insertReview(
                    tenant,
                    rule,
                    operation,
                    "PAYROLL",
                    row.getId(),
                    row.getEmployeeCode(),
                    row.getPayrollMonth());
            payrollCount++;
        }
        return new int[] {attendanceCount, payrollCount};
    }

    private void insertReview(
            String tenant,
            PayrollComponentVersionView rule,
            HrPayrollComponentOperationEntity operation,
            String subject,
            String subjectId,
            String employee,
            String month) {
        var row = new HrPayrollComponentReviewEntity();
        row.id = UUID.randomUUID().toString();
        row.tenantId = tenant;
        row.operationId = operation.id;
        row.ruleId = rule.ruleId();
        row.ruleName = rule.definition().name();
        row.actionCode = operation.actionCode;
        row.subject = subject;
        row.subjectId = subjectId;
        row.employeeCode = employee;
        row.reviewMonth = month;
        row.reason = operation.reason;
        row.state = "PENDING";
        row.createdTime = operation.createdTime;
        reviews.insert(row);
    }

    public List<PayrollComponentOperationView> operations(String tenant, String ruleId) {
        return operations
                .selectList(
                        new QueryWrapper<HrPayrollComponentOperationEntity>()
                                .eq("tenant_id", tenant)
                                .eq("rule_id", ruleId)
                                .orderByDesc("id")
                                .last("LIMIT 1000"))
                .stream()
                .map(
                        row ->
                                new PayrollComponentOperationView(
                                        row.actionCode,
                                        row.reason,
                                        row.actorId,
                                        row.createdTime.toInstant(ZoneOffset.UTC)))
                .toList();
    }

    public List<PayrollRuleReviewView> reviews(String tenant, String subject, String month) {
        var rows =
                reviews.selectList(
                        new QueryWrapper<HrPayrollComponentReviewEntity>()
                                .eq("tenant_id", tenant)
                                .eq("subject", subject)
                                .eq("review_month", month)
                                .eq("state", "PENDING")
                                .orderByDesc("created_time")
                                .last("LIMIT 10001"));
        if (rows.size() > 10000) throw error(ErrorCode.BAD_REQUEST, "待复核记录超过查询上限");
        return rows.stream().map(this::reviewView).toList();
    }

    public Optional<PayrollRuleReviewView> review(String tenant, String id) {
        return Optional.ofNullable(
                        reviews.selectOne(
                                new QueryWrapper<HrPayrollComponentReviewEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("id", id)
                                        .eq("state", "PENDING")))
                .map(this::reviewView);
    }

    private PayrollRuleReviewView reviewView(HrPayrollComponentReviewEntity row) {
        return new PayrollRuleReviewView(
                row.id,
                row.subject,
                row.subjectId,
                row.employeeCode,
                null,
                row.reviewMonth,
                row.ruleName,
                row.actionCode,
                row.reason,
                row.createdTime.toInstant(ZoneOffset.UTC));
    }

    @Transactional
    public void resolve(String tenant, String id, String reason, String actor) {
        if (reviews.update(
                        null,
                        new UpdateWrapper<HrPayrollComponentReviewEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("state", "PENDING")
                                .set("state", "RESOLVED")
                                .set("resolved_by", actor)
                                .set("resolved_time", LocalDateTime.now(ZoneOffset.UTC))
                                .set("resolution_reason", reason))
                != 1) throw error(ErrorCode.CONFLICT, "复核记录已处理，请刷新");
        locks.audit(tenant, id, "RULE_REVIEW_RESOLVED", actor, reason, Map.of("reviewId", id));
    }

    public void resolveSubject(
            String tenant, String subject, String subjectId, String reason, String actor) {
        var rows =
                reviews.selectList(
                        new QueryWrapper<HrPayrollComponentReviewEntity>()
                                .eq("tenant_id", tenant)
                                .eq("subject", subject)
                                .eq("subject_id", subjectId)
                                .eq("state", "PENDING")
                                .last("LIMIT 1001"));
        if (rows.size() > 1000) throw error(ErrorCode.BAD_REQUEST, "单条记录的待复核任务超过处理上限");
        for (var row : rows) resolve(tenant, row.id, reason, actor);
    }
}
