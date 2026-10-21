package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.TargetSettingsModels.*;
import com.rigour.hr.application.port.out.TargetSettingsStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class TargetSettingsService {
    public static final String READ = "hr:targets:read";
    public static final String WRITE = "hr:targets:write";
    public static final Set<String> METRICS =
            Set.of(
                    "CONTACTED_STORE_COUNT",
                    "ORDERED_STORE_COUNT",
                    "YANGZHANGGUI_SALES_AMOUNT",
                    "SALES_AMOUNT",
                    "RECEIPT_AMOUNT");
    private final TargetSettingsStore store;
    private final Clock clock;
    private com.rigour.hr.application.port.out.HrPayrollComponentStore components;
    private com.rigour.hr.application.port.out.HrPayrollSchemeStore schemes;
    private com.rigour.hr.application.port.out.HrPayrollStore payroll;

    @org.springframework.beans.factory.annotation.Autowired
    void setPayrollRules(
            com.rigour.hr.application.port.out.HrPayrollComponentStore components,
            com.rigour.hr.application.port.out.HrPayrollSchemeStore schemes,
            com.rigour.hr.application.port.out.HrPayrollStore payroll) {
        this.components = components;
        this.schemes = schemes;
        this.payroll = payroll;
    }

    public List<com.rigour.hr.api.v1.model.PayrollTargetMetricView> payrollMetrics() {
        var a = actor(READ);
        return payrollMetrics(a.tenantId().toString());
    }

    private List<com.rigour.hr.api.v1.model.PayrollTargetMetricView> payrollMetrics(String tenant) {
        var result =
                new LinkedHashMap<String, com.rigour.hr.api.v1.model.PayrollTargetMetricView>();
        result.put(
                "FULLY_PAID_RECEIPT_BASIS",
                new com.rigour.hr.api.v1.model.PayrollTargetMetricView(
                        "FULLY_PAID_RECEIPT_BASIS",
                        "整单付清回款基数",
                        "元",
                        "薪酬绩效专用；销售未单独核定时采用入职阶段目标，首月按规则免考"));
        if (components != null)
            for (var rule : components.versions(tenant)) {
                var p = rule.definition().performance();
                if (p == null || p.manager() == null || p.manager().metrics() == null) continue;
                for (var metric : p.manager().metrics())
                    if (!METRICS.contains(metric.code()))
                        result.putIfAbsent(
                                metric.code(),
                                new com.rigour.hr.api.v1.model.PayrollTargetMetricView(
                                        metric.code(),
                                        metric.name(),
                                        metric.code().endsWith("_COUNT") ? "家" : "指标值",
                                        "城市总薪酬目标按本人管辖范围合计核定；实际值来源：" + metric.source()));
            }
        return List.copyOf(result.values());
    }

    private Set<String> editableMetrics(String tenant) {
        var metrics = new LinkedHashSet<>(METRICS);
        payrollMetrics(tenant).forEach(m -> metrics.add(m.code()));
        return metrics;
    }

    private PayrollRuleResolver ruleResolver;

    @org.springframework.beans.factory.annotation.Autowired
    void setRuleResolver(PayrollRuleResolver rules) {
        this.ruleResolver = rules;
    }

    private BigDecimal salaryDefault(String tenant, String month, Subject subject, String metric) {
        if (!"FULLY_PAID_RECEIPT_BASIS".equals(metric)
                || !"SALES_OWNER".equals(subject.dimensionType())
                || payroll == null
                || schemes == null) return BigDecimal.ZERO;
        var e = payroll.employee(tenant, subject.code(), READ);
        var period = YearMonth.parse(month);
        if (ruleResolver != null
                && components != null
                && components.versions(tenant).stream()
                        .anyMatch(v -> !v.definition().effectiveFrom().isAfter(period.atDay(1)))) {
            var configured =
                    ruleResolver.performance(
                            tenant,
                            e.employeeCode(),
                            payroll.departmentAt(tenant, e.employeeCode(), period.atEndOfMonth()),
                            period.atDay(1));
            if (configured.isEmpty()
                    || e.entryDate() == null
                    || !"SALES".equals(configured.get().definition().performance().mode()))
                return BigDecimal.ZERO;
            var policy = configured.get().definition().performance().sales();
            long stage =
                    java.time.temporal.ChronoUnit.MONTHS.between(
                                    YearMonth.from(e.entryDate()), period)
                            + 1;
            if (stage == 1 && policy.firstMonthExempt()) return BigDecimal.ZERO;
            return policy.targets().stream()
                    .filter(
                            v ->
                                    stage >= v.fromMonth()
                                            && (v.toMonth() == null || stage <= v.toMonth()))
                    .map(com.rigour.hr.api.v1.model.PayrollTargetStage::target)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        }
        var scheme =
                PayrollSchemeSelector.select(
                        schemes.versions(tenant),
                        e.employeeCode(),
                        payroll.departmentAt(tenant, e.employeeCode(), period.atEndOfMonth()),
                        period.atDay(1));
        if (scheme.isEmpty()
                || e.entryDate() == null
                || !"SALES".equals(scheme.get().definition().performanceMode()))
            return BigDecimal.ZERO;
        long stage =
                java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(e.entryDate()), period)
                        + 1;
        var policy = scheme.get().definition().sales();
        if (stage == 1 && policy.firstMonthExempt()) return BigDecimal.ZERO;
        return policy.targets().stream()
                .filter(
                        v ->
                                stage >= v.fromMonth()
                                        && (v.toMonth() == null || stage <= v.toMonth()))
                .map(com.rigour.hr.api.v1.model.PayrollTargetStage::target)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    public TargetSettingsService(TargetSettingsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings settings(String month) {
        var actor = actor(READ);
        month(month);
        var subjects = visibleSubjects(actor.tenantId().toString());
        return new Settings(
                month,
                subjects,
                effective(
                        actor.tenantId().toString(),
                        month,
                        month,
                        subjects,
                        editableMetrics(actor.tenantId().toString()),
                        true));
    }

    /**
     * Internal read contract: BI receives effective values, never owns target defaults or
     * persistence.
     */
    @Transactional(readOnly = true)
    public List<Target> values(String from, String to) {
        var actor = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(actor.principalScope()) || actor.tenantId() == null)
            throw new AuthorizationDeniedException("service-target-reader");
        AuthorizationContext.requirePermission("hr:targets:service-read");
        var start = month(from);
        var end = month(to);
        if (end.isBefore(start) || java.time.temporal.ChronoUnit.MONTHS.between(start, end) > 23)
            throw invalid("指标查询范围应为1至24个月");
        return effective(
                actor.tenantId().toString(),
                from,
                to,
                store.subjects(actor.tenantId().toString()),
                METRICS,
                false);
    }

    private List<Target> effective(
            String tenant,
            String from,
            String to,
            List<Subject> subjects,
            Set<String> metrics,
            boolean salaryDefaults) {
        var saved = new HashMap<String, Target>();
        store.targets(tenant, from, to)
                .forEach(
                        t -> saved.put(key(t.month(), t.dimensionType(), t.code(), t.metric()), t));
        var result = new ArrayList<Target>();
        for (var period = month(from); !period.isAfter(month(to)); period = period.plusMonths(1)) {
            for (var subject : subjects)
                for (var metric : metrics) {
                    var existing =
                            saved.get(
                                    key(
                                            period.toString(),
                                            subject.dimensionType(),
                                            subject.code(),
                                            metric));
                    result.add(
                            new Target(
                                    period.toString(),
                                    subject.dimensionType(),
                                    subject.code(),
                                    subject.name(),
                                    metric,
                                    existing == null
                                            ? (METRICS.contains(metric)
                                                    ? initialValue(subject.dimensionType(), metric)
                                                    : salaryDefaults
                                                            ? salaryDefault(
                                                                    tenant,
                                                                    period.toString(),
                                                                    subject,
                                                                    metric)
                                                            : BigDecimal.ZERO)
                                            : existing.value(),
                                    existing == null ? 0 : existing.revision()));
                }
        }
        return result;
    }

    private static String key(String month, String type, String code, String metric) {
        return month + ":" + type + ":" + code + ":" + metric;
    }

    private static BigDecimal initialValue(String type, String metric) {
        return BigDecimal.valueOf(
                switch (metric) {
                    case "SALES_AMOUNT" -> "CITY".equals(type) ? 100000 : 40000;
                    case "RECEIPT_AMOUNT" -> "CITY".equals(type) ? 100000 : 20000;
                    case "CONTACTED_STORE_COUNT",
                                    "ORDERED_STORE_COUNT",
                                    "YANGZHANGGUI_SALES_AMOUNT" ->
                            0;
                    default -> throw invalid("未知指标");
                });
    }

    private List<Subject> visibleSubjects(String tenant) {
        var readable = store.permittedSubjects(tenant, READ);
        Set<String> writable = Set.of();
        if (AuthorizationContext.hasPermission(WRITE))
            writable = store.permittedSubjects(tenant, WRITE);
        var writeKeys = writable;
        return store.subjects(tenant).stream()
                .filter(s -> readable.contains(s.dimensionType() + ":" + s.code()))
                .map(
                        s ->
                                new Subject(
                                        s.dimensionType(),
                                        s.code(),
                                        s.name(),
                                        s.cityCode(),
                                        s.cityName(),
                                        s.departmentName(),
                                        s.employmentStatus(),
                                        writeKeys.contains(s.dimensionType() + ":" + s.code())))
                .toList();
    }

    private void checkScope(Subject subject, Set<String> permitted) {
        if (!permitted.contains(subject.dimensionType() + ":" + subject.code()))
            throw new AuthorizationDeniedException("hr-target-scope");
    }

    @Transactional
    public void save(Batch batch) {
        var actor = actor(WRITE);
        if (batch == null
                || batch.changes() == null
                || batch.changes().isEmpty()
                || batch.changes().size() > 800) throw invalid("请选择1至800项指标变更");
        var period = month(batch.month());
        String reason =
                reason(
                        batch.reason(),
                        !period.isAfter(YearMonth.now(clock.withZone(ZoneId.of("Asia/Shanghai")))));
        var subjects = new HashMap<String, Subject>();
        store.subjects(actor.tenantId().toString())
                .forEach(s -> subjects.put(s.dimensionType() + ":" + s.code(), s));
        var permitted = store.permittedSubjects(actor.tenantId().toString(), WRITE);
        var seen = new HashSet<String>();
        for (var c : batch.changes()) {
            if (c == null) throw invalid("指标不能为空");
            validate(
                    c.metric(),
                    c.value(),
                    c.expectedRevision(),
                    editableMetrics(actor.tenantId().toString()));
            if (!METRICS.contains(c.metric()) && !"SALES_OWNER".equals(c.dimensionType()))
                throw invalid("薪酬目标请在人员维度核定，不能将部门编号当成任职城市编号");
            if (!METRICS.contains(c.metric())
                    && (batch.reason() == null || batch.reason().isBlank()))
                throw invalid("调整薪酬目标须填写核定依据");
            if (!seen.add(c.dimensionType() + ":" + c.code() + ":" + c.metric()))
                throw invalid("重复的目标指标");
            var s = subjects.get(c.dimensionType() + ":" + c.code());
            if (s == null) throw invalid("城市或销售已不存在，请刷新列表");
            checkScope(s, permitted);
        }
        for (var c : batch.changes())
            store.save(
                    actor.tenantId().toString(),
                    actor.userId().toString(),
                    batch.month(),
                    subjects.get(c.dimensionType() + ":" + c.code()),
                    c,
                    reason,
                    clock.instant());
    }

    @Transactional(readOnly = true)
    public List<History> history(String month, String type, String code) {
        var actor = actor(READ);
        month(month);
        var subject =
                store.subjects(actor.tenantId().toString()).stream()
                        .filter(s -> s.dimensionType().equals(type) && s.code().equals(code))
                        .findFirst()
                        .orElseThrow(() -> invalid("目标对象不存在"));
        checkScope(subject, store.permittedSubjects(actor.tenantId().toString(), READ));
        return store.history(actor.tenantId().toString(), month, type, code);
    }

    private static CallerIdentity actor(String action) {
        var actor = AuthorizationContext.requireCurrent();
        if (!"TENANT".equals(actor.principalScope())
                || actor.tenantId() == null
                || actor.userId() == null) throw new AuthorizationDeniedException("tenant-user");
        AuthorizationContext.requirePermission(READ);
        AuthorizationContext.requirePermission(action);
        return actor;
    }

    private static YearMonth month(String month) {
        try {
            var value = YearMonth.parse(month);
            if (value.getYear() < 2000 || value.getYear() > 2100) throw invalid("月份超出范围");
            return value;
        } catch (java.time.format.DateTimeParseException | NullPointerException e) {
            throw invalid("月份格式应为YYYY-MM");
        }
    }

    private static String reason(String value, boolean required) {
        String text = value == null ? "" : value.strip();
        if ((required && text.isEmpty()) || text.length() > 1000) throw invalid("请填写修改原因，最多1000字");
        return text;
    }

    private static void validate(
            String metric, BigDecimal value, int revision, Set<String> metrics) {
        if (metric == null || !metrics.contains(metric) || revision < 0) throw invalid("指标或版本无效");
        if (value == null) throw invalid("指标不能为空");
        if (value.signum() < 0
                || value.compareTo(new BigDecimal("999999999999.99")) > 0
                || value.stripTrailingZeros().scale() > (metric.endsWith("_COUNT") ? 0 : 2))
            throw invalid("金额须为非负数且最多两位小数，门店数须为非负整数");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
