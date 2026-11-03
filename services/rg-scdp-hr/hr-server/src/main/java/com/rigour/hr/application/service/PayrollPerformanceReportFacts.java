package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 报表复用工资实际采用的绩效规则和考勤确认，不用当前规则悄悄改写已保存工资。 */
final class PayrollPerformanceReportFacts {
    private PayrollPerformanceReportFacts() {}

    static PayrollPerformanceExportView enrich(PayrollDetailView row, PayrollPerformanceExportView report,
            PayrollEmployeeView employee, AttendanceRow attendance,
            List<TargetSettingsModels.Target> targets, List<TargetSettingsModels.Subject> subjects) {
        var snapshot = row.calculation().scheme();
        var definition = snapshot == null ? null : snapshot.scheme().definition();
        var role = definition == null ? employee.payrollCategory() : definition.role();
        var issues = new LinkedHashSet<>(report.issues());
        var subject = subjects.stream().filter(s -> "SALES_OWNER".equals(s.dimensionType()) && row.employeeCode().equals(s.code())).findFirst().orElse(null);
        String cityCode = subject != null && Objects.equals(row.cityName(), subject.cityName()) ? subject.cityCode() : null;
        var metrics = new ArrayList<PayrollPerformanceExportView.Metric>();
        for (var item : ManagerCityTargetResolver.METRICS.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            if (Set.of("CONTACTED_STORE_COUNT", "ORDERED_STORE_COUNT").contains(item.getKey())) continue;
            var configured = definition == null || definition.manager() == null ? null : definition.manager().metrics().stream()
                    .filter(m -> item.getKey().equals(ManagerCityTargetResolver.metric(m))).findFirst().orElse(null);
            var saved = snapshot == null ? null : snapshot.metrics().stream()
                    .filter(m -> item.getKey().equals(m.code()) || configured != null && configured.code().equals(m.code())).findFirst().orElse(null);
            var personal = targets.stream().filter(t -> row.month().equals(t.month()) && "SALES_OWNER".equals(t.dimensionType())
                    && row.employeeCode().equals(t.code()) && item.getKey().equals(t.metric())).findFirst().orElse(null);
            var target = saved != null ? saved.target() : personal != null ? personal.value() : null;
            // 城市总固定目标可来自旧绩效规则；关联城市指标时必须采用实际城市月目标，不能拿默认值补缺。
            if (target == null && configured != null && configured.cityMetric() == null) target = configured.target();
            var actual = saved != null ? saved.actual() : null;
            if ("SALES_AMOUNT".equals(item.getKey())) actual = report.salesAmount();
            String evidence = saved != null ? saved.evidence() : "";
            metrics.add(new PayrollPerformanceExportView.Metric(item.getKey(), item.getValue(), actual, target,
                    configured == null ? null : configured.weight(), evidence));
        }
        var performance = row.calculation().performanceDetail();
        var days = performance == null ? List.<PayrollPerformanceDayView>of() : performance.days();
        boolean complete = performance != null && days.stream().allMatch(PayrollPerformanceDayView::evidenceComplete)
                && (!days.isEmpty() || snapshot != null && snapshot.assessedDays() == 0 && performance.issues().isEmpty());
        var confirmed = attendance != null && "CONFIRMED".equals(attendance.attendanceStatus());
        var override = confirmed && attendance.assessmentOverride() != null ? attendance.assessmentOverride()
                : performance == null ? null : performance.assessmentOverride();
        Integer actualCheckins = complete ? days.stream().mapToInt(PayrollPerformanceDayView::checkins).sum() : null;
        Integer actualVisits = complete ? days.stream().mapToInt(PayrollPerformanceDayView::effectiveVisits).sum() : null;
        Integer expectedCheckins = complete ? days.stream().mapToInt(d -> d.checkins() + d.missingCheckins()).sum() : null;
        Integer expectedVisits = complete ? days.stream().mapToInt(d -> d.effectiveVisits() + d.missingVisits()).sum() : null;
        if (definition != null && definition.sales() != null && definition.calendar() != null && !days.isEmpty()) {
            var dates = days.stream().map(PayrollPerformanceDayView::date).collect(java.util.stream.Collectors.toSet());
            var fractions = PayrollProcessAssessment.fractions(definition.calendar().window(), dates,
                    confirmed ? attendance.exceptions() : List.of());
            expectedCheckins = fractions.values().stream().mapToInt(f -> f.multiply(BigDecimal.valueOf(definition.sales().dailyCheckins())).setScale(0, java.math.RoundingMode.CEILING).intValueExact()).sum();
            expectedVisits = fractions.values().stream().mapToInt(f -> f.multiply(BigDecimal.valueOf(definition.sales().dailyVisits())).setScale(0, java.math.RoundingMode.CEILING).intValueExact()).sum();
        }
        if (override != null) {
            if (override.expectedCheckins() != null) expectedCheckins = override.expectedCheckins();
            if (override.expectedVisits() != null) expectedVisits = override.expectedVisits();
            if (override.actualCheckins() != null) actualCheckins = override.actualCheckins();
            if (override.actualVisits() != null) actualVisits = override.actualVisits();
            if (override.actualCheckins() != null || override.actualVisits() != null) issues.add("打卡/拜访采用人工核定实绩");
            else if (actualCheckins == null || actualVisits == null) issues.add("已有缺失次数核定，实际总次数待录入，不能由缺失次数反推");
        }
        BigDecimal target = report.collectionTarget();
        String basis = target == null ? "回款指标待匹配" : "工资快照的当月回款考核目标";
        var manualTarget = targets.stream().filter(t -> row.month().equals(t.month()) && "SALES_OWNER".equals(t.dimensionType())
                && row.employeeCode().equals(t.code()) && "FULLY_PAID_RECEIPT_BASIS".equals(t.metric())).findFirst().orElse(null);
        if (manualTarget != null && target != null && manualTarget.value().compareTo(target) == 0)
            basis = "工资采用的本月人工核定回款目标";
        else if (manualTarget != null && target != null) issues.add("本月核定目标已变更，报表保留工资原目标，需重新核算核对影响");
        if (definition != null && "SALES".equals(definition.performanceMode()) && employee.entryDate() != null) {
            long stage = ChronoUnit.MONTHS.between(YearMonth.from(employee.entryDate()), YearMonth.parse(row.month())) + 1;
            if (stage == 1 && definition.sales().firstMonthExempt()) {
                target = BigDecimal.ZERO;
                basis = "入职首个自然月，回款免考核";
                issues.remove("回款指标未配置");
            } else if (stage > 0) {
                if (target == null) {
                    var stages = definition.sales().targets().stream().filter(s -> stage >= s.fromMonth() && (s.toMonth() == null || stage <= s.toMonth())).toList();
                    if (stages.size() == 1 && stages.getFirst().target() != null) {
                        target = stages.getFirst().target();
                        basis = "采用工资保存的绩效规则阶段回款目标";
                        issues.remove("回款指标未配置");
                    }
                }
                basis = "入职第" + stage + "个自然月；" + basis;
            }
        }
        if (!confirmed) issues.add("考勤尚未确认，应/实出勤待核定");
        var summary = new PayrollPerformanceExportView.Summary(confirmed && attendance.expectedDays() != null ? BigDecimal.valueOf(attendance.expectedDays()) : null,
                confirmed ? attendance.actualDays() : null, expectedCheckins, actualCheckins, expectedVisits, actualVisits,
                performance == null ? null : performance.processDeduction());
        return new PayrollPerformanceExportView(report.payrollId(), report.payrollRevision(), report.month(), report.employeeCode(), report.employeeName(), report.cityName(),
                report.categories(), report.clothSales(), report.salesAmount(), report.collectionAmount(), report.refundAmount(), target, report.salesTarget(), report.commission(),
                List.copyOf(issues), cityCode, role, employee.entryDate(), employee.leaveDate(), summary, List.copyOf(metrics), basis);
    }
}
