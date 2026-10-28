package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollComponentStore;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Matches employee amounts and departmental rules once for a given payroll month. */
@Service
public class PayrollRuleResolver {
    private final HrPayrollComponentStore store;

    public PayrollRuleResolver(HrPayrollComponentStore store) {
        this.store = store;
    }

    public Map<String, PayrollAppliedRuleView> metadata(String tenant) {
        Map<String, PayrollAppliedRuleView> result = new LinkedHashMap<>();
        for (var v : store.versions(tenant)) {
            var d = v.definition();
            result.put(
                    v.id(),
                    new PayrollAppliedRuleView(
                            d.kind(),
                            v.id(),
                            v.ruleCode(),
                            d.name(),
                            v.version(),
                            d.effectiveFrom(),
                            d.effectiveTo()));
        }
        return result;
    }

    public List<PayrollAppliedRuleView> appliedRules(String tenant, PayrollRuleReferences refs) {
        if (refs == null) return List.of();
        var metadata = metadata(tenant);
        return java.util.stream.Stream.of(
                        refs.salaryId(),
                        refs.performanceId(),
                        refs.commissionId(),
                        refs.attendanceId(),
                        refs.allowanceId())
                .filter(Objects::nonNull)
                .distinct()
                .map(metadata::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public PayrollRuleMatch resolve(
            String tenant, PayrollEmployeeView employee, Long department, YearMonth month) {
        return resolve(store.versions(tenant), employee, department, month);
    }

    /** 转岗前的订单仍可能本月付清，不能仅凭现行空提成规则跳过订单依据。 */
    public boolean hasHistoricalOrderDependency(
            String tenant, PayrollEmployeeView employee, Set<Long> departments, YearMonth month) {
        for (var version : store.versions(tenant)) {
            var d = version.definition();
            if (d.effectiveFrom().isAfter(month.atEndOfMonth())
                    || employee.entryDate() != null
                            && d.effectiveTo().isBefore(employee.entryDate())) continue;
            if ("SALARY".equals(d.kind())
                    && d.employeeCodes().contains(employee.employeeCode())
                    && !"HEADQUARTERS".equals(d.role())) return true;
            if ("COMMISSION".equals(d.kind())
                    && d.commission() != null
                    && !d.commission().rules().isEmpty()
                    && d.departmentIds().stream().anyMatch(departments::contains)) return true;
        }
        return false;
    }

    /**
     * One tenant-scoped rule snapshot per preparation request, rather than one query per person.
     */
    public Map<String, PayrollRuleMatch> prepare(
            String tenant,
            List<PayrollEmployeeView> employees,
            YearMonth month,
            java.util.function.Function<PayrollEmployeeView, Long> department) {
        var all = store.versions(tenant);
        Map<String, PayrollRuleMatch> result = new LinkedHashMap<>();
        for (var employee : employees) {
            var issues = new ArrayList<String>();
            var salary =
                    one(
                            all,
                            "SALARY",
                            null,
                            null,
                            employee.employeeCode(),
                            month.atDay(1),
                            issues,
                            "员工薪资标准");
            result.put(
                    employee.employeeCode(),
                    salary == null
                            ? new PayrollRuleMatch(null, List.copyOf(issues))
                            : resolve(all, employee, department.apply(employee), month));
        }
        return Map.copyOf(result);
    }

    private PayrollRuleMatch resolve(
            List<PayrollComponentVersionView> all,
            PayrollEmployeeView employee,
            Long department,
            YearMonth month) {
        var issues = new ArrayList<String>();
        var date = month.atDay(1);
        var salary =
                one(all, "SALARY", null, null, employee.employeeCode(), date, issues, "员工薪资标准");
        String role = salary == null ? null : salary.definition().role();
        if (department == null) issues.add("本月任职部门缺失");
        var performance = one(all, "PERFORMANCE", role, department, null, date, issues, "绩效规则");
        // Fixed allowances are a sales-only component, not a required zero-value manager rule.
        var personal = salary == null ? null : salary.definition().salary().personalPolicy();
        PayrollComponentVersionView allowance = null;
        if (personal != null
                && personal.allowanceVersionId() != null
                && !personal.allowanceVersionId().isBlank()) {
            allowance =
                    all.stream()
                            .filter(
                                    v ->
                                            !v.deleted()
                                                    && v.enabledForDepartmentAt(department, date)
                                                    && v.id().equals(personal.allowanceVersionId())
                                                    && "ALLOWANCE".equals(v.definition().kind())
                                                    && ("ALL".equals(v.definition().role())
                                                            || Objects.equals(
                                                                    role, v.definition().role()))
                                                    && v.definition()
                                                            .departmentIds()
                                                            .contains(department)
                                                    && !v.definition().effectiveFrom().isAfter(date)
                                                    && !v.definition()
                                                            .effectiveTo()
                                                            .isBefore(month.atEndOfMonth()))
                            .findFirst()
                            .orElse(null);
            if (allowance == null) issues.add("个人关联的补贴版本不适用本月、身份或部门，请调整员工薪资标准");
        } else if ("SALES".equals(role)) {
            allowance = one(all, "ALLOWANCE", role, department, null, date, issues, "销售补贴规则");
        }
        var commission = one(all, "COMMISSION", role, department, null, date, issues, "提成规则");
        var attendance = one(all, "ATTENDANCE", role, department, null, date, issues, "考勤规则");
        if (!issues.isEmpty()) return new PayrollRuleMatch(null, List.copyOf(issues));
        var selected =
                java.util.stream.Stream.of(salary, performance, allowance, commission, attendance)
                        .filter(Objects::nonNull)
                        .toList();
        for (var row : selected)
            if (row.definition().effectiveTo().isBefore(month.atEndOfMonth()))
                issues.add(row.definition().name() + "未覆盖整月");
        var amount = salary.definition().salary();
        var policy = performance.definition().performance();
        var base = amount.baseSalary();
        if ("TIERS".equals(base.mode())) {
            if (policy.grades() == null || policy.grades().isEmpty())
                issues.add("按档位发放底薪，但绩效规则未配置金银铜牌区间");
            else {
                var tiers = new ArrayList<PayrollSalaryTier>();
                for (var band : policy.grades()) {
                    var matching =
                            base.tiers().stream()
                                    .filter(t -> Objects.equals(t.code(), band.code()))
                                    .toList();
                    if (matching.size() != 1) {
                        issues.add("员工档案缺少" + band.name() + "底薪和绩效基数");
                        continue;
                    }
                    tiers.add(
                            new PayrollSalaryTier(
                                    band.code(),
                                    band.name(),
                                    band.lower(),
                                    band.upper(),
                                    band.lowerInclusive(),
                                    band.upperInclusive(),
                                    matching.getFirst().amount(),
                                    matching.getFirst().performanceBase()));
                }
                base = new PayrollSchemeBaseSalary("TIERS", null, tiers, base.firstMonthTier());
            }
        }
        if (!issues.isEmpty()) return new PayrollRuleMatch(null, List.copyOf(issues));
        var refs =
                new PayrollRuleReferences(
                        salary.id(),
                        performance.id(),
                        commission.id(),
                        attendance.id(),
                        allowance == null ? null : allowance.id());
        var definition =
                new PayrollSchemeDefinition(
                        salary.definition().name() + " · 月度规则核算",
                        role,
                        List.of(),
                        List.of(employee.employeeCode()),
                        month.atDay(1),
                        month.atEndOfMonth(),
                        base,
                        amount.performanceBase(),
                        policy.mode(),
                        policy.sales(),
                        policy.manager(),
                        attendance.definition().calendar(),
                        new PayrollProrationPolicy(
                                amount.proration().base(),
                                amount.proration().performance(),
                                allowance == null
                                        ? "NONE"
                                        : allowance.definition().allowance().proration()),
                        allowance == null ? List.of() : allowance.definition().allowance().items(),
                        commission.definition().commission(),
                        "系统自动匹配，HR无需维护方案",
                        refs,
                        amount.personalPolicy());
        issues.addAll(PayrollSchemeValidator.check(definition).errors());
        if (!issues.isEmpty()) return new PayrollRuleMatch(null, List.copyOf(issues));
        String id =
                UUID.nameUUIDFromBytes(
                                (employee.employeeCode() + ":" + month + ":" + refs)
                                        .getBytes(StandardCharsets.UTF_8))
                        .toString();
        Instant updated =
                selected.stream()
                        .map(PayrollComponentVersionView::createdTime)
                        .max(Comparator.naturalOrder())
                        .orElseThrow();
        return new PayrollRuleMatch(
                new PayrollSchemeVersionView(
                        id,
                        "AUTO:" + employee.employeeCode(),
                        1,
                        1,
                        "RESOLVED",
                        definition,
                        "系统自动匹配规则",
                        "SYSTEM",
                        null,
                        updated,
                        null),
                List.of());
    }

    public Map<String, String> salaryRoles(String tenant, List<String> employees, LocalDate date) {
        var versions = store.versions(tenant);
        Map<String, String> result = new HashMap<>();
        for (String employee : employees) {
            var salary =
                    one(
                            versions,
                            "SALARY",
                            null,
                            null,
                            employee,
                            date,
                            new ArrayList<>(),
                            "员工薪资标准");
            if (salary != null) result.put(employee, salary.definition().role());
        }
        return Map.copyOf(result);
    }

    public Optional<PayrollComponentVersionView> commission(
            String tenant, Long department, LocalDate date) {
        var issues = new ArrayList<String>();
        var row =
                one(
                        store.versions(tenant),
                        "COMMISSION",
                        "ALL",
                        department,
                        null,
                        date,
                        issues,
                        "提成规则");
        if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
        return Optional.ofNullable(row);
    }

    public PayrollPersonalPolicy personalPolicy(String tenant, String employee, LocalDate date) {
        var salary =
                one(
                        store.versions(tenant),
                        "SALARY",
                        null,
                        null,
                        employee,
                        date,
                        new ArrayList<>(),
                        "员工薪资标准");
        return salary == null ? null : salary.definition().salary().personalPolicy();
    }

    public Optional<PayrollComponentVersionView> performance(
            String tenant, String employee, Long department, LocalDate date) {
        var all = store.versions(tenant);
        var issues = new ArrayList<String>();
        var salary = one(all, "SALARY", null, null, employee, date, issues, "员工薪资标准");
        if (salary == null) return Optional.empty();
        return Optional.ofNullable(
                one(
                        all,
                        "PERFORMANCE",
                        salary.definition().role(),
                        department,
                        null,
                        date,
                        issues,
                        "绩效规则"));
    }

    private static PayrollComponentVersionView one(
            List<PayrollComponentVersionView> all,
            String kind,
            String role,
            Long department,
            String employee,
            LocalDate date,
            List<String> issues,
            String label) {
        // Choose the newest version applicable on this date within each rule family. Never
        // silently resolve conflicting families by creation order or highest database ID.
        Map<String, PayrollComponentVersionView> families = new HashMap<>();
        for (var row : all) {
            var d = row.definition();
            if (row.deleted() || !kind.equals(d.kind()) || d.effectiveFrom().isAfter(date))
                continue;
            if (employee != null
                    ? d.employeeCodes() == null || !d.employeeCodes().contains(employee)
                    : department == null
                            || !d.departmentIds().contains(department)
                            || role == null
                            || !("ALL".equals(d.role()) || role.equals(d.role()))) continue;
            families.merge(row.ruleId(), row, (a, b) -> a.version() > b.version() ? a : b);
        }
        boolean stopped =
                families.values().stream()
                        .anyMatch(
                                row ->
                                        !enabled(row, department, employee, date)
                                                && !row.definition().effectiveTo().isBefore(date));
        families.values()
                .removeIf(
                        row ->
                                !enabled(row, department, employee, date)
                                        || row.definition().effectiveTo().isBefore(date));
        if (families.size() != 1) {
            issues.add(
                    label + (families.isEmpty() ? stopped ? "已停用，请启用或配置替代规则" : "未配置" : "存在多条冲突配置"));
            return null;
        }
        return families.values().iterator().next();
    }

    private static boolean enabled(
            PayrollComponentVersionView row, Long department, String employee, LocalDate date) {
        return employee == null
                ? row.enabledForDepartmentAt(department, date)
                : row.enabledForEmployeeAt(employee, date);
    }
}
