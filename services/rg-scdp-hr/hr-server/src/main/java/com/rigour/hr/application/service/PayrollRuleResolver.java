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
        var all = store.versions(tenant);
        var issues = new ArrayList<String>();
        var date = month.atDay(1);
        var salary =
                one(all, "SALARY", null, null, employee.employeeCode(), date, issues, "员工薪资标准");
        String role = salary == null ? null : salary.definition().role();
        if (department == null) issues.add("本月任职部门缺失");
        var performance = one(all, "PERFORMANCE", role, department, null, date, issues, "绩效规则");
        // Fixed allowances are a sales-only component, not a required zero-value manager rule.
        var allowance =
                "SALES".equals(role)
                        ? one(all, "ALLOWANCE", role, department, null, date, issues, "销售补贴规则")
                        : null;
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
                        refs);
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
            if (!kind.equals(d.kind()) || d.effectiveFrom().isAfter(date)) continue;
            if (employee != null
                    ? d.employeeCodes() == null || !d.employeeCodes().contains(employee)
                    : department == null
                            || !d.departmentIds().contains(department)
                            || role == null
                            || !("ALL".equals(d.role()) || role.equals(d.role()))) continue;
            families.merge(row.ruleId(), row, (a, b) -> a.version() > b.version() ? a : b);
        }
        families.values().removeIf(row -> row.definition().effectiveTo().isBefore(date));
        if (families.size() != 1) {
            issues.add(label + (families.isEmpty() ? "未配置" : "存在多条冲突配置"));
            return null;
        }
        return families.values().iterator().next();
    }
}
