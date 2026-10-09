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
            Set.of("SALES_AMOUNT", "RECEIPT_AMOUNT", "NEW_CUSTOMER", "REPEAT_CUSTOMER");
    private final TargetSettingsStore store;
    private final Clock clock;

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
                month, subjects, effective(actor.tenantId().toString(), month, month, subjects));
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
                actor.tenantId().toString(), from, to, store.subjects(actor.tenantId().toString()));
    }

    private List<Target> effective(String tenant, String from, String to, List<Subject> subjects) {
        var saved = new HashMap<String, Target>();
        store.targets(tenant, from, to)
                .forEach(
                        t -> saved.put(key(t.month(), t.dimensionType(), t.code(), t.metric()), t));
        var result = new ArrayList<Target>();
        for (var period = month(from); !period.isAfter(month(to)); period = period.plusMonths(1)) {
            for (var subject : subjects)
                for (var metric : METRICS) {
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
                                            ? initialValue(subject.dimensionType(), metric)
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
                    case "NEW_CUSTOMER" -> 200;
                    case "REPEAT_CUSTOMER" -> 100;
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
            validate(c.metric(), c.value(), c.expectedRevision());
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

    private static void validate(String metric, BigDecimal value, int revision) {
        if (metric == null || !METRICS.contains(metric) || revision < 0) throw invalid("指标或版本无效");
        if (value == null) throw invalid("指标不能为空");
        if (value.signum() < 0
                || value.compareTo(new BigDecimal("999999999999.99")) > 0
                || value.stripTrailingZeros().scale() > (metric.endsWith("CUSTOMER") ? 0 : 2))
            throw invalid("金额须为非负数且最多两位小数，客户数须为非负整数");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
