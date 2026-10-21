package com.rigour.hr.infrastructure.persistence.repository;

import static com.rigour.hr.infrastructure.persistence.repository.HrPayrollPersistenceSupport.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.core.api.ErrorCode;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 工资规则及月度核算仓储；所有更新均带租户和版本条件。 */
@Repository
public class MybatisPlusHrPayrollRepository implements HrPayrollStore {
    private final HrPayrollMapper mapper;
    private final HrPayrollRuleMapper ruleMapper;
    private final HrPayrollReferenceMapper references;
    private final HrPayrollPersistenceSupport support;
    private final ObjectMapper json;
    private final HrDataScope scope;
    private final HrPayrollPaymentMapper paymentMapper;
    private final HrPayrollCorrectionMapper correctionMapper;
    private final HrPayrollCommissionReleaseMapper releaseMapper;

    public MybatisPlusHrPayrollRepository(
            HrPayrollMapper mapper,
            HrPayrollRuleMapper ruleMapper,
            HrPayrollReferenceMapper references,
            HrPayrollPersistenceSupport support,
            ObjectMapper json,
            HrDataScope scope,
            HrPayrollPaymentMapper paymentMapper,
            HrPayrollCorrectionMapper correctionMapper,
            HrPayrollCommissionReleaseMapper releaseMapper) {
        this.mapper = mapper;
        this.ruleMapper = ruleMapper;
        this.references = references;
        this.support = support;
        this.json = json;
        this.scope = scope;
        this.paymentMapper = paymentMapper;
        this.correctionMapper = correctionMapper;
        this.releaseMapper = releaseMapper;
    }

    @Override
    public void lock(String tenant) {
        support.lock(tenant);
    }

    private QueryWrapper<HrEmployeeEntity> employeeQuery(String tenant, String action) {
        var q = new QueryWrapper<HrEmployeeEntity>().eq("e.tenant_id", tenant).eq("e.deleted", 0);
        var p = scope.employeePredicate(action, "e.");
        bind(q, p.sql(), p.args());
        return q;
    }

    @Override
    public List<PayrollEmployeeView> employees(String tenant, String action) {
        var rows =
                references.employees(
                        employeeQuery(tenant, action)
                                .orderByAsc("e.employee_code")
                                .last("LIMIT 20001"));
        if (rows.size() > 20000) throw error(ErrorCode.BAD_REQUEST, "员工数量超过上限，请缩小权限范围");
        return rows;
    }

    @Override
    public PayrollEmployeeView employee(String tenant, String code, String action) {
        return references
                .employees(employeeQuery(tenant, action).eq("e.employee_code", code))
                .stream()
                .findFirst()
                .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "员工不存在或无权限"));
    }

    @Override
    public com.rigour.hr.api.v1.model.PayrollSalarySourceView salarySource(
            String tenant, String employee) {
        // 调用前已通过 employee(tenant, employee, action) 校验该员工的数据范围。
        return references.salarySource(tenant, employee);
    }

    @Override
    public Long departmentAt(String tenant, String employee, LocalDate date) {
        var rows =
                references.departmentsAt(
                        tenant,
                        employee,
                        date.atStartOfDay().minusHours(8),
                        date.plusDays(1).atStartOfDay().minusHours(8));
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    @Override
    public List<PayrollDepartmentView> departments(String tenant, String action) {
        return references.departments(tenant).stream()
                .filter(
                        d -> {
                            try {
                                scope.requireDepartment(tenant, d.id(), action);
                                return true;
                            } catch (com.rigour.shared.context.AuthorizationDeniedException ex) {
                                return false;
                            }
                        })
                .toList();
    }

    @Override
    public List<PayrollCommissionPolicyView> policies() {
        return references.policies();
    }

    @Override
    public List<PayrollRuleView> rules(String tenant) {
        return ruleMapper
                .selectList(
                        new QueryWrapper<HrPayrollRuleEntity>()
                                .eq("tenant_id", tenant)
                                .orderByDesc("effective_from")
                                .orderByAsc("id"))
                .stream()
                .map(this::rule)
                .toList();
    }

    private PayrollRuleView rule(HrPayrollRuleEntity r) {
        return new PayrollRuleView(
                r.getId(),
                r.getRevision(),
                json.readValue(r.getDataJson(), PayrollRuleCommand.class),
                r.getUpdatedTime().toInstant(ZoneOffset.UTC));
    }

    @Override
    public void requireRuleScope(String tenant, PayrollRuleCommand c, String action) {
        if (Set.of("BASE", "CITY_MANAGER").contains(c.kindCode()))
            employee(tenant, c.employeeCode(), action);
        if (!"BASE".equals(c.kindCode()))
            for (var department : c.departmentIds())
                scope.requireDepartment(tenant, department, action);
    }

    @Override
    @Transactional
    public PayrollRuleView saveRule(String tenant, String id, PayrollRuleCommand c, String actor) {
        lock(tenant);
        String body = json.writeValueAsString(c);
        if (id == null) {
            var old =
                    ruleMapper.selectOne(
                            new QueryWrapper<HrPayrollRuleEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("request_id", c.requestId()));
            if (old != null) {
                var previous = rule(old);
                requireRuleScope(tenant, previous.data(), "hr:payroll-rule:write");
                if (!json.writeValueAsString(previous.data()).equals(body))
                    throw error(ErrorCode.CONFLICT, "请求编号已使用");
                return previous;
            }
            var row = new HrPayrollRuleEntity();
            row.setId(UUID.randomUUID().toString());
            row.setTenantId(tenant);
            row.setRequestId(c.requestId());
            row.setKindCode(c.kindCode());
            row.setName(c.name());
            row.setEmployeeCode(c.employeeCode());
            row.setEffectiveFrom(c.effectiveFrom());
            row.setEffectiveTo(c.effectiveTo());
            row.setEnabled(c.enabled());
            row.setDataJson(body);
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            ruleMapper.insert(row);
            id = row.getId();
        } else {
            var old =
                    ruleMapper.selectOne(
                            new QueryWrapper<HrPayrollRuleEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("id", id));
            if (old == null) throw error(ErrorCode.NOT_FOUND, "规则不存在");
            var previous = rule(old);
            requireRuleScope(tenant, previous.data(), "hr:payroll-rule:write");
            int changed =
                    ruleMapper.update(
                            null,
                            new UpdateWrapper<HrPayrollRuleEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("id", id)
                                    .eq("revision", c.revision())
                                    .eq("kind_code", c.kindCode())
                                    .set("name", c.name())
                                    .set("employee_code", c.employeeCode())
                                    .set("effective_from", c.effectiveFrom())
                                    .set("effective_to", c.effectiveTo())
                                    .set("enabled", c.enabled())
                                    .set("data_json", body)
                                    .set("updated_by", actor)
                                    .set("updated_time", LocalDateTime.now(ZoneOffset.UTC))
                                    .setSql("revision=revision+1"));
            if (changed != 1) throw error(ErrorCode.CONFLICT, "规则已变更或类型不一致");
            support.audit(tenant, id, "BEFORE_RULE_UPDATE", actor, c.remark(), previous);
        }
        ruleMapper.clearDepartments(tenant, id);
        for (Long department : c.departmentIds()) ruleMapper.addDepartment(tenant, id, department);
        var saved =
                rule(
                        ruleMapper.selectOne(
                                new QueryWrapper<HrPayrollRuleEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("id", id)));
        support.audit(tenant, id, "RULE_SAVE", actor, c.remark(), saved);
        return saved;
    }

    @Override
    public PayrollInputCommand input(String tenant, String employee, String month) {
        var value = mapper.input(tenant, employee, month);
        return value == null ? new PayrollInputCommand(0, null, null, BigDecimal.ZERO, "") : value;
    }

    @Override
    @Transactional
    public PayrollInputCommand saveInput(
            String tenant, String employee, String month, PayrollInputCommand c, String actor) {
        lock(tenant);
        byMonth(tenant, employee, month).ifPresent(p -> unlocked(p.statusCode()));
        var before = input(tenant, employee, month);
        if (!Objects.equals(before.revision(), c.revision()))
            throw error(ErrorCode.CONFLICT, "月度输入已被修改");
        mapper.saveInput(tenant, employee, month, c, actor);
        support.audit(tenant, employee + ":" + month, "INPUT_BEFORE", actor, c.reason(), before);
        var saved = input(tenant, employee, month);
        support.audit(tenant, employee + ":" + month, "INPUT_SAVE", actor, c.reason(), saved);
        // 月度调整使旧结果失效，必须重新核算后才能确认。
        mapper.update(
                null,
                new UpdateWrapper<HrPayrollEntity>()
                        .eq("tenant_id", tenant)
                        .eq("employee_code", employee)
                        .eq("payroll_month", month)
                        .set("status_code", "REVIEW")
                        .setSql("revision=revision+1")
                        .set("updated_by", actor)
                        .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
        return saved;
    }

    @Override
    public java.util.Map<String, PayrollCommissionDetailView> confirmedSalesCommissions(
            String tenant, Set<String> employees, Set<String> lineIds, String throughMonth) {
        if (employees.isEmpty() || lineIds.isEmpty()) return java.util.Map.of();
        var result = new java.util.LinkedHashMap<String, PayrollCommissionDetailView>();
        // 已确认销售提成使用其工资快照；未全额回款的挂账明细尚未结算，不作为已结算金额。
        var snapshots =
                mapper.selectList(
                        new QueryWrapper<HrPayrollEntity>()
                                .eq("tenant_id", tenant)
                                .in("employee_code", employees)
                                .in("status_code", List.of("CONFIRMED", "PAID"))
                                .le("payroll_month", throughMonth)
                                .select("employee_code", "calculation_json")
                                .orderByAsc("payroll_month", "id"));
        for (var snapshot : snapshots) {
            var calculation =
                    json.readValue(snapshot.getCalculationJson(), PayrollCalculationView.class);
            for (var detail : calculation.details()) {
                if ("SALES".equals(detail.recipientRoleCode())
                        && lineIds.contains(detail.sourceLineId())
                        && detail.accrued() != null
                        && detail.released() != null
                        && detail.accrued().compareTo(detail.released()) == 0)
                    result.put(detail.sourceLineId(), detail);
            }
        }
        return java.util.Map.copyOf(result);
    }

    @Override
    public Set<String> released(String tenant) {
        return new HashSet<>(mapper.released(tenant));
    }

    @Override
    public Optional<PayrollDetailView> byMonth(String tenant, String employee, String month) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                new QueryWrapper<HrPayrollEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("employee_code", employee)
                                        .eq("payroll_month", month)))
                .map(this::payrollView);
    }

    @Override
    @Transactional
    public PayrollDetailView saveCalculation(
            String tenant,
            String employee,
            String month,
            PayrollEmployeeView e,
            PayrollCalculationView result,
            String actor) {
        lock(tenant);
        var old = byMonth(tenant, employee, month);
        old.ifPresent(p -> unlocked(p.statusCode()));
        String id = old.map(PayrollDetailView::id).orElseGet(() -> UUID.randomUUID().toString());
        String status = result.issues().isEmpty() ? "READY" : "REVIEW";
        String body = json.writeValueAsString(result);
        if (old.isEmpty()) {
            var row = new HrPayrollEntity();
            row.setId(id);
            row.setTenantId(tenant);
            row.setEmployeeCode(employee);
            row.setEmployeeName(e.employeeName());
            row.setDepartmentId(e.departmentId());
            row.setDepartmentName(e.departmentName());
            row.setCityName(e.cityName());
            row.setPayrollMonth(month);
            row.setStatusCode(status);
            row.setCalculationJson(body);
            row.setCreatedBy(actor);
            row.setUpdatedBy(actor);
            mapper.insert(row);
        } else {
            int changed =
                    mapper.update(
                            null,
                            new UpdateWrapper<HrPayrollEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("id", id)
                                    .eq("revision", old.get().revision())
                                    .notIn("status_code", "CONFIRMED", "PAID")
                                    .set("employee_name", e.employeeName())
                                    .set("department_id", e.departmentId())
                                    .set("department_name", e.departmentName())
                                    .set("city_name", e.cityName())
                                    .set("status_code", status)
                                    .set("calculation_json", body)
                                    .setSql("revision=revision+1")
                                    .set("updated_by", actor)
                                    .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
            if (changed != 1) throw error(ErrorCode.CONFLICT, "工资已变更，请重新核算");
            support.audit(tenant, id, "BEFORE_CALCULATE", actor, null, old.get());
        }
        var saved = byMonth(tenant, employee, month).orElseThrow();
        support.audit(tenant, id, "CALCULATE", actor, null, saved);
        return saved;
    }

    private QueryWrapper<HrPayrollEntity> payrollQuery(String tenant, String action) {
        return scope.apply(new QueryWrapper<HrPayrollEntity>().eq("tenant_id", tenant), action);
    }

    @Override
    public PayrollDetailView detail(String tenant, String id, String action) {
        var row = mapper.selectOne(payrollQuery(tenant, action).eq("id", id));
        if (row == null) throw error(ErrorCode.NOT_FOUND, "工资单不存在或无权限");
        return payrollView(row);
    }

    @Override
    public HrPageView<PayrollDetailView> payroll(String tenant, PayrollQuery c, String action) {
        var q = filteredQuery(tenant, c, action);
        long total = mapper.selectCount(q);
        int begin = Math.max(0, c.begin()), step = pageSize(c.step());
        var rows =
                mapper.selectList(
                        q.orderByDesc("payroll_month")
                                .orderByAsc("employee_code")
                                .last("LIMIT " + step + " OFFSET " + begin));
        return new HrPageView<>(total, begin, step, rows.stream().map(this::payrollView).toList());
    }

    private QueryWrapper<HrPayrollEntity> filteredQuery(
            String tenant, PayrollQuery c, String action) {
        var q =
                payrollQuery(tenant, action)
                        .eq(text(c.month()), "payroll_month", c.month())
                        .eq(c.departmentId() != null, "department_id", c.departmentId())
                        .eq(text(c.statusCode()), "status_code", c.statusCode());
        // 按工资核算快照筛选，员工后续转岗不能改变历史工资的归类。参数由 MyBatis 绑定。
        if (text(c.payrollCategory()))
            q.apply(
                    "COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(calculation_json,"
                            + " '$.scheme.scheme.definition.role')), 'null'),"
                            + " NULLIF(JSON_UNQUOTE(JSON_EXTRACT(calculation_json,"
                            + " '$.salarySource.payrollCategory')), 'null')) = {0}",
                    c.payrollCategory());
        if (text(c.keyword()))
            q.and(
                    w ->
                            w.like("employee_code", c.keyword())
                                    .or()
                                    .like("employee_name", c.keyword()));
        return q;
    }

    @Override
    public PayrollSummaryView summary(String tenant, PayrollQuery query, String action) {
        // 与列表复用租户、历史部门、员工搜索和数据权限；数据库聚合，不加载工资明细或逐人查付款。
        var allStages =
                new PayrollQuery(
                        0, 0, query.month(), query.keyword(), query.departmentId(), null,
                        query.payrollCategory());
        String complete =
                "status_code IN ('READY','CONFIRMED','PAID') AND"
                    + " JSON_TYPE(JSON_EXTRACT(calculation_json, '$.grossPay')) IN"
                    + " ('INTEGER','DOUBLE','DECIMAL') AND JSON_TYPE(JSON_EXTRACT(calculation_json,"
                    + " '$.netPay')) IN ('INTEGER','DOUBLE','DECIMAL')";
        var grouped =
                mapper.selectMaps(
                        filteredQuery(tenant, allStages, action)
                                .select(
                                        "status_code AS stage",
                                        "COUNT(*) AS total",
                                        "SUM(CASE WHEN "
                                                + complete
                                                + " THEN 1 ELSE 0 END) AS complete_count",
                                        "SUM(CASE WHEN "
                                                + complete
                                                + " THEN"
                                                + " CAST(JSON_UNQUOTE(JSON_EXTRACT(calculation_json,"
                                                + " '$.grossPay')) AS DECIMAL(20,2)) ELSE 0 END) AS"
                                                + " gross_pay",
                                        "SUM(CASE WHEN "
                                                + complete
                                                + " THEN"
                                                + " CAST(JSON_UNQUOTE(JSON_EXTRACT(calculation_json,"
                                                + " '$.netPay')) AS DECIMAL(20,2)) ELSE 0 END) AS"
                                                + " net_pay")
                                .groupBy("status_code"));
        long total = 0, count = 0;
        BigDecimal gross = BigDecimal.ZERO, net = BigDecimal.ZERO;
        var statuses = new LinkedHashMap<String, Long>();
        for (var group : grouped) {
            String stage = (String) group.get("stage");
            long stageCount = ((Number) group.get("total")).longValue();
            statuses.put(stage, stageCount);
            if (text(query.statusCode()) && !query.statusCode().equals(stage)) continue;
            total += stageCount;
            count += ((Number) group.get("complete_count")).longValue();
            gross = gross.add(new BigDecimal(group.get("gross_pay").toString()));
            net = net.add(new BigDecimal(group.get("net_pay").toString()));
        }
        return new PayrollSummaryView(
                total, count, total - count, gross, net, Map.copyOf(statuses));
    }

    @Override
    public List<PayrollDetailView> export(String tenant, PayrollQuery query, String action) {
        return mapper
                .selectList(filteredQuery(tenant, query, action).orderByAsc("employee_code"))
                .stream()
                .map(this::payrollView)
                .toList();
    }

    @Override
    @Transactional
    public PayrollDetailView transition(
            String tenant, String id, PayrollActionCommand c, String status, String actor) {
        lock(tenant);
        if (!"CONFIRMED".equals(status)) throw error(ErrorCode.BAD_REQUEST, "发放须使用结构化登记接口");
        String action = "hr:payroll:confirm";
        var old = detail(tenant, id, action);
        String expected = "PAID".equals(status) ? "CONFIRMED" : "READY";
        if (!expected.equals(old.statusCode()) || old.revision() != c.revision())
            throw error(ErrorCode.CONFLICT, "工资单状态或版本已变更");
        if ("CONFIRMED".equals(status))
            for (var d : old.calculation().details())
                if (d.released() != null && d.released().signum() > 0)
                    mapper.release(
                            tenant, d.sourceLineId(), d.recipientRoleCode(), id, d.released());
        int changed =
                mapper.update(
                        null,
                        new UpdateWrapper<HrPayrollEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", c.revision())
                                .eq("status_code", expected)
                                .set("status_code", status)
                                .setSql("revision=revision+1")
                                .set("updated_by", actor)
                                .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) throw error(ErrorCode.CONFLICT, "工资单状态或版本已变更");
        var saved = detail(tenant, id, action);
        support.audit(tenant, id, status, actor, c.reason(), saved);
        return saved;
    }

    private PayrollDetailView payrollView(HrPayrollEntity r) {
        return new PayrollDetailView(
                r.getId(),
                r.getPayrollMonth(),
                r.getEmployeeCode(),
                r.getEmployeeName(),
                r.getDepartmentId(),
                r.getDepartmentName(),
                r.getCityName(),
                r.getStatusCode(),
                r.getRevision(),
                json.readValue(r.getCalculationJson(), PayrollCalculationView.class),
                r.getUpdatedTime().toInstant(ZoneOffset.UTC),
                payments(r.getTenantId(), r.getId()),
                correctionsIssued(r.getTenantId(), r.getId()));
    }

    @Override
    public List<PayrollAuditView> history(String tenant, String id) {
        // 工资单和员工月份调整的留痕在同一时间线展示。
        var payroll =
                mapper.selectOne(
                        new QueryWrapper<HrPayrollEntity>().eq("tenant_id", tenant).eq("id", id));
        return support.history(
                tenant,
                payroll == null
                        ? List.of(id)
                        : List.of(
                                id,
                                payroll.getEmployeeCode() + ":" + payroll.getPayrollMonth(),
                                "EMPLOYEE:" + payroll.getEmployeeCode()));
    }

    @Override
    public List<PayrollDetailView> monthRecords(String tenant, String month, String action) {
        return mapper
                .selectList(
                        payrollQuery(tenant, action)
                                .eq("payroll_month", month)
                                .orderByAsc("employee_code"))
                .stream()
                .map(this::payrollView)
                .toList();
    }

    private List<PayrollPaymentView> payments(String tenant, String id) {
        return paymentMapper
                .selectList(
                        new QueryWrapper<HrPayrollPaymentEntity>()
                                .eq("tenant_id", tenant)
                                .eq("payroll_id", id)
                                .orderByAsc("created_time", "id"))
                .stream()
                .map(
                        p ->
                                new PayrollPaymentView(
                                        p.getId(),
                                        p.getRequestId(),
                                        p.getPaidDate(),
                                        p.getAmount(),
                                        p.getReferenceNo(),
                                        p.getReason(),
                                        p.getCreatedTime().toInstant(ZoneOffset.UTC)))
                .toList();
    }

    private PayrollCorrectionView correctionView(HrPayrollCorrectionEntity c) {
        return new PayrollCorrectionView(
                c.getId(),
                c.getSourcePayrollId(),
                c.getSourceMonth(),
                c.getTargetMonth(),
                c.getAmount(),
                c.getReason(),
                c.getStatusCode(),
                c.getRevision(),
                c.getCreatedTime().toInstant(ZoneOffset.UTC));
    }

    private List<PayrollCorrectionView> correctionsIssued(String tenant, String id) {
        return correctionMapper
                .selectList(
                        new QueryWrapper<HrPayrollCorrectionEntity>()
                                .eq("tenant_id", tenant)
                                .eq("source_payroll_id", id)
                                .orderByAsc("created_time", "id"))
                .stream()
                .map(this::correctionView)
                .toList();
    }

    @Override
    public List<PayrollCorrectionView> corrections(String tenant, String employee, String month) {
        return correctionMapper
                .selectList(
                        new QueryWrapper<HrPayrollCorrectionEntity>()
                                .eq("tenant_id", tenant)
                                .eq("employee_code", employee)
                                .eq("target_month", month)
                                .eq("status_code", "ACTIVE")
                                .orderByAsc("id"))
                .stream()
                .map(this::correctionView)
                .toList();
    }

    private void updateStatus(String tenant, PayrollDetailView old, String status, String actor) {
        int changed =
                mapper.update(
                        null,
                        new UpdateWrapper<HrPayrollEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", old.id())
                                .eq("revision", old.revision())
                                .eq("status_code", old.statusCode())
                                .set("status_code", status)
                                .setSql("revision=revision+1")
                                .set("updated_by", actor)
                                .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) throw error(ErrorCode.CONFLICT, "工资单已变更，请刷新");
    }

    @Override
    @Transactional
    public PayrollDetailView reopen(
            String tenant, String id, PayrollActionCommand c, String actor) {
        lock(tenant);
        var old = detail(tenant, id, "hr:payroll:confirm");
        if (!"CONFIRMED".equals(old.statusCode()) || old.revision() != c.revision())
            throw error(ErrorCode.CONFLICT, "只能撤回当前版本的已确认工资");
        if (!old.payments().isEmpty()) throw error(ErrorCode.CONFLICT, "已有发放登记，请通过跨月补差或冲回纠错");
        support.audit(tenant, id, "BEFORE_REOPEN", actor, c.reason(), old);
        releaseMapper.delete(
                new QueryWrapper<HrPayrollCommissionReleaseEntity>()
                        .eq("tenant_id", tenant)
                        .eq("payroll_id", id));
        updateStatus(tenant, old, "REVIEW", actor);
        var saved = detail(tenant, id, "hr:payroll:confirm");
        support.audit(tenant, id, "REOPEN", actor, c.reason(), saved);
        return saved;
    }

    @Override
    @Transactional
    public PayrollDetailView pay(String tenant, String id, PayrollPaymentCommand c, String actor) {
        lock(tenant);
        var old = detail(tenant, id, "hr:payroll:paid");
        var previous =
                paymentMapper.selectOne(
                        new QueryWrapper<HrPayrollPaymentEntity>()
                                .eq("tenant_id", tenant)
                                .eq("request_id", c.requestId()));
        if (previous != null) {
            if (!id.equals(previous.getPayrollId())
                    || !c.paidDate().equals(previous.getPaidDate())
                    || c.amount().compareTo(previous.getAmount()) != 0
                    || !c.referenceNo().trim().equals(previous.getReferenceNo())
                    || !c.reason().equals(previous.getReason()))
                throw error(ErrorCode.CONFLICT, "发放请求编号已使用，请刷新核对");
            return old;
        }
        if (!"CONFIRMED".equals(old.statusCode()) || old.revision() != c.revision())
            throw error(ErrorCode.CONFLICT, "请刷新当前已确认的工资单");
        BigDecimal net = old.calculation().netPay();
        if (net == null || net.signum() < 0) throw error(ErrorCode.CONFLICT, "工资金额尚未核定");
        BigDecimal remaining =
                net.subtract(
                        old.payments().stream()
                                .map(PayrollPaymentView::amount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (c.amount().compareTo(remaining) > 0
                || (c.amount().signum() == 0 && remaining.signum() != 0))
            throw error(ErrorCode.BAD_REQUEST, "登记金额须大于0且不超过待发金额；零工资可登记0元");
        if (old.payments().stream()
                .anyMatch(p -> p.referenceNo().equalsIgnoreCase(c.referenceNo().trim())))
            throw error(ErrorCode.CONFLICT, "该工资单的凭证号已登记");
        var row = new HrPayrollPaymentEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setPayrollId(id);
        row.setRequestId(c.requestId());
        row.setPaidDate(c.paidDate());
        row.setAmount(c.amount());
        row.setReferenceNo(c.referenceNo().trim());
        row.setReason(c.reason());
        row.setCreatedBy(actor);
        row.setCreatedTime(LocalDateTime.now(ZoneOffset.UTC));
        paymentMapper.insert(row);
        updateStatus(
                tenant, old, remaining.compareTo(c.amount()) == 0 ? "PAID" : "CONFIRMED", actor);
        var saved = detail(tenant, id, "hr:payroll:paid");
        support.audit(tenant, id, "PAYMENT_REGISTER", actor, c.reason(), saved);
        return saved;
    }

    private void invalidateTarget(String tenant, String employee, String month, String actor) {
        byMonth(tenant, employee, month)
                .ifPresent(
                        target -> {
                            unlocked(target.statusCode());
                            updateStatus(tenant, target, "REVIEW", actor);
                        });
    }

    @Override
    @Transactional
    public PayrollCorrectionView correct(
            String tenant, String id, PayrollCorrectionCommand c, String actor) {
        lock(tenant);
        var old = detail(tenant, id, "hr:payroll:confirm");
        var recipient = employee(tenant, old.employeeCode(), "hr:payroll:confirm");
        var previous =
                correctionMapper.selectOne(
                        new QueryWrapper<HrPayrollCorrectionEntity>()
                                .eq("tenant_id", tenant)
                                .eq("request_id", c.requestId()));
        if (previous != null) {
            if (!id.equals(previous.getSourcePayrollId())
                    || !c.targetMonth().equals(previous.getTargetMonth())
                    || c.amount().compareTo(previous.getAmount()) != 0
                    || !c.reason().equals(previous.getReason()))
                throw error(ErrorCode.CONFLICT, "补差请求编号已使用");
            return correctionView(previous);
        }
        if (!"SALES".equals(recipient.payrollCategory()))
            throw error(ErrorCode.BAD_REQUEST, "员工当前不参与销售薪酬核算，暂不能自动结算跨月补差，请由HR核对");
        if ((!"PAID".equals(old.statusCode())
                        && !("CONFIRMED".equals(old.statusCode()) && !old.payments().isEmpty()))
                || old.revision() != c.revision())
            throw error(ErrorCode.CONFLICT, "未发放工资请先撤回确认；工资单已变更时请刷新");
        if (c.targetMonth().compareTo(old.month()) <= 0)
            throw error(ErrorCode.BAD_REQUEST, "补差须计入原工资之后的月份");
        byMonth(tenant, old.employeeCode(), c.targetMonth())
                .ifPresent(p -> unlocked(p.statusCode()));
        var row = new HrPayrollCorrectionEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setSourcePayrollId(id);
        row.setSourceMonth(old.month());
        row.setEmployeeCode(old.employeeCode());
        row.setTargetMonth(c.targetMonth());
        row.setRequestId(c.requestId());
        row.setAmount(c.amount());
        row.setReason(c.reason());
        row.setStatusCode("ACTIVE");
        row.setRevision(1);
        row.setCreatedBy(actor);
        row.setCreatedTime(LocalDateTime.now(ZoneOffset.UTC));
        correctionMapper.insert(row);
        invalidateTarget(tenant, old.employeeCode(), c.targetMonth(), actor);
        var saved = correctionView(row);
        support.audit(tenant, id, "CORRECTION_CREATE", actor, c.reason(), saved);
        support.audit(
                tenant,
                old.employeeCode() + ":" + c.targetMonth(),
                "CORRECTION_RECEIVE",
                actor,
                c.reason(),
                saved);
        return saved;
    }

    @Override
    @Transactional
    public void cancelCorrection(
            String tenant, String id, String correctionId, PayrollActionCommand c, String actor) {
        lock(tenant);
        var source = detail(tenant, id, "hr:payroll:confirm");
        employee(tenant, source.employeeCode(), "hr:payroll:confirm");
        var row =
                correctionMapper.selectOne(
                        new QueryWrapper<HrPayrollCorrectionEntity>()
                                .eq("tenant_id", tenant)
                                .eq("source_payroll_id", id)
                                .eq("id", correctionId));
        if (row == null) throw error(ErrorCode.NOT_FOUND, "补差记录不存在");
        if (!"ACTIVE".equals(row.getStatusCode()) || row.getRevision() != c.revision())
            throw error(ErrorCode.CONFLICT, "补差已作废或已变更，请刷新");
        byMonth(tenant, row.getEmployeeCode(), row.getTargetMonth())
                .ifPresent(p -> unlocked(p.statusCode()));
        int changed =
                correctionMapper.update(
                        null,
                        new UpdateWrapper<HrPayrollCorrectionEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", correctionId)
                                .eq("revision", c.revision())
                                .eq("status_code", "ACTIVE")
                                .set("status_code", "VOID")
                                .setSql("revision=revision+1"));
        if (changed != 1) throw error(ErrorCode.CONFLICT, "补差已变更");
        invalidateTarget(tenant, row.getEmployeeCode(), row.getTargetMonth(), actor);
        row.setStatusCode("VOID");
        row.setRevision(row.getRevision() + 1);
        support.audit(tenant, id, "CORRECTION_VOID", actor, c.reason(), correctionView(row));
        support.audit(
                tenant,
                row.getEmployeeCode() + ":" + row.getTargetMonth(),
                "CORRECTION_VOID",
                actor,
                c.reason(),
                correctionView(row));
    }

    private static void unlocked(String status) {
        if (Set.of("CONFIRMED", "PAID").contains(status))
            throw error(ErrorCode.CONFLICT, "已确认工资保持快照，不可覆盖");
    }

    @Override
    public List<PayrollDetailView> commissionHistory(
            String tenant, String employee, String throughMonth) {
        return mapper
                .selectList(
                        new QueryWrapper<HrPayrollEntity>()
                                .eq("tenant_id", tenant)
                                .eq("employee_code", employee)
                                .le("payroll_month", throughMonth)
                                .in("status_code", List.of("CONFIRMED", "PAID")))
                .stream()
                .map(this::payrollView)
                .toList();
    }
}
