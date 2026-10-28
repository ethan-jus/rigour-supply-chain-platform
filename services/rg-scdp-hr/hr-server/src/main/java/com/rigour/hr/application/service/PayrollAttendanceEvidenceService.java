package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrAttendanceStore;
import com.rigour.hr.application.port.out.HrPayrollSchemeStore;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.domain.payroll.SalarySchemeCalculator;
import com.rigour.hr.domain.payroll.SalesPerformanceCalculator;
import com.rigour.sales.api.v1.model.SalesAttendanceModels.Evidence;
import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/** Attendance uses any submitted, undeleted punch; performance review remains independent. */
@Service
public class PayrollAttendanceEvidenceService {
    private final HrPayrollSchemeStore schemes;
    private final HrPayrollSource source;

    private HrPayrollComponentService components;
    private PayrollRuleResolver rules;

    @org.springframework.beans.factory.annotation.Autowired
    void setRules(PayrollRuleResolver rules) {
        this.rules = rules;
    }

    public record Batch(
            Map<String, Evidence> evidence, Map<String, AttendanceAssessmentView> assessments) {}

    @org.springframework.beans.factory.annotation.Autowired
    void setComponents(HrPayrollComponentService components) {
        this.components = components;
    }

    public PayrollAttendanceEvidenceService(HrPayrollSchemeStore schemes, HrPayrollSource source) {
        this.schemes = schemes;
        this.source = source;
    }

    public boolean stopped(String tenant, Long department, YearMonth month) {
        return components != null && components.calendarStopped(tenant, department, month.atDay(1));
    }

    public Set<Long> stoppedDepartments(String tenant, YearMonth month) {
        return components == null
                ? Set.of()
                : components.stoppedCalendarDepartments(tenant, month.atDay(1));
    }

    public Optional<Integer> expectedDays(String tenant, Long department, YearMonth month) {
        if (components == null || department == null) return Optional.empty();
        return components
                .calendar(tenant, department, month.atDay(1))
                .map(
                        rule -> {
                            var calendar =
                                    new HrStatutoryCalendarResolver(source)
                                            .resolve(tenant, rule.definition().calendar(), month);
                            return SalarySchemeCalculator.workDates(
                                            month,
                                            calendar.weekdays(),
                                            calendar.workDays(),
                                            calendar.restDays(),
                                            calendar.alternateWeekdays() == null
                                                    ? List.of()
                                                    : calendar.alternateWeekdays(),
                                            calendar.alternateAnchor())
                                    .size();
                        });
    }

    public Map<String, Evidence> apply(
            String tenant,
            YearMonth month,
            LocalDate through,
            List<HrAttendanceStore.Employee> employees,
            Map<String, Evidence> existing) {
        return applyDetailed(tenant, month, through, through, employees, existing).evidence();
    }

    public Batch applyDetailed(
            String tenant,
            YearMonth month,
            LocalDate through,
            LocalDate assessmentThrough,
            List<HrAttendanceStore.Employee> employees,
            Map<String, Evidence> existing) {
        Map<String, AttendanceAssessmentView> assessments = new HashMap<>();
        Map<String, Evidence> result = new HashMap<>(existing);
        var versions = schemes.versions(tenant);
        for (var employee : employees) {
            var scheme =
                    PayrollSchemeSelector.select(
                            versions, employee.code(), employee.departmentId(), month.atDay(1));
            var independent =
                    components == null
                            ? Optional
                                    .<com.rigour.hr.api.v1.model.PayrollComponentVersionView>empty()
                            : components.calendar(tenant, employee.departmentId(), month.atDay(1));
            if (independent.isEmpty() && (components != null || scheme.isEmpty())) {
                if (components != null) result.remove(employee.code());
                assessments.put(employee.code(), unavailable(assessmentThrough, "未匹配到生效考勤规则"));
                continue;
            }
            // A missing source must not silently fall back to calendar dates under a configured
            // window.
            result.remove(employee.code());
            try {
                var evidence =
                        source.performanceEvidence(
                                tenant, employee.code(), month.atDay(1), through.plusDays(1));
                var calendar =
                        independent.isPresent()
                                ? independent.get().definition().calendar()
                                : scheme.get().definition().calendar();
                var window = calendar.window();
                assessments.put(
                        employee.code(),
                        assess(tenant, month, assessmentThrough, employee, calendar, evidence));
                List<LocalDate> dates = new ArrayList<>();
                for (var visit : evidence.visits()) {
                    if (visit.submittedAt() == null) throw new IllegalStateException("打卡原始时间缺失");
                    var local =
                            visit.submittedAt()
                                    .atOffset(ZoneOffset.UTC)
                                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                                    .toLocalDateTime();
                    var date =
                            SalarySchemeCalculator.businessDate(
                                    local,
                                    window.start(),
                                    window.end(),
                                    window.nextDay(),
                                    window.outside());
                    if (date != null && !date.isBefore(month.atDay(1)) && !date.isAfter(through))
                        dates.add(date);
                }
                result.put(
                        employee.code(),
                        new Evidence(
                                employee.code(),
                                evidence.employeeLinked(),
                                dates.stream().distinct().sorted().toList()));
            } catch (RuntimeException unavailable) {
                assessments.put(employee.code(), unavailable(assessmentThrough, "打卡拜访来源或考核规则暂不可用"));
                org.slf4j.LoggerFactory.getLogger(PayrollAttendanceEvidenceService.class)
                        .warn(
                                "Payroll attendance source unavailable for employee {}",
                                employee.code(),
                                unavailable);
            }
        }
        return new Batch(Map.copyOf(result), Map.copyOf(assessments));
    }

    private AttendanceAssessmentView assess(
            String tenant,
            YearMonth month,
            LocalDate through,
            HrAttendanceStore.Employee employee,
            PayrollWorkCalendar calendar,
            SalesPayrollEvidenceView evidence) {
        var performance =
                rules == null
                        ? Optional.<PayrollComponentVersionView>empty()
                        : rules.performance(
                                tenant, employee.code(), employee.departmentId(), month.atDay(1));
        if (performance.isEmpty()) return unavailable(through, "未匹配到销售绩效规则，暂不能计算缺卡和缺拜访次数");
        var standard = performance.get().definition().performance();
        if (!"SALES".equals(standard.mode()))
            return new AttendanceAssessmentView(
                    "NOT_APPLICABLE", through, null, null, List.of(), "当前员工不适用销售过程考核");
        if (employee.entry() == null) return unavailable(through, "入职日期缺失，暂不能确定考核期间");
        calendar = new HrStatutoryCalendarResolver(source).resolve(tenant, calendar, month);
        var policy = standard.sales();
        var personal = rules.personalPolicy(tenant, employee.code(), month.atDay(1));
        var dates =
                PayrollProcessAssessment.dates(month, calendar, personal, policy, employee.entry());
        dates.removeIf(
                date ->
                        date.isAfter(through)
                                || date.isBefore(employee.entry())
                                || employee.leave() != null && date.isAfter(employee.leave()));
        if (policy.processPercent().signum() == 0
                || policy.firstMonthExempt()
                        && policy.processProtectionDays() == null
                        && YearMonth.from(employee.entry()).equals(month)) dates.clear();
        var fractions =
                PayrollProcessAssessment.fractions(
                        calendar.window(), dates, employee.record().exceptions());
        dates.removeIf(date -> fractions.get(date).signum() == 0);
        if (!dates.isEmpty() && !evidence.employeeLinked())
            return new AttendanceAssessmentView(
                    "UNLINKED", through, null, null, List.of(), "销售打卡账号未关联员工，缺失次数待核对");
        var days =
                SalesPerformanceCalculator.processDays(
                        PayrollProcessAssessment.policy(policy),
                        month,
                        employee.entry(),
                        employee.leave(),
                        evidence.employeeLinked(),
                        PayrollProcessAssessment.visits(evidence, calendar.window()),
                        dates,
                        policy.checkinCountMode(),
                        fractions);
        return new AttendanceAssessmentView(
                "AVAILABLE",
                through,
                days.stream().mapToInt(d -> d.missingCheckins()).sum(),
                days.stream().mapToInt(d -> d.missingVisits()).sum(),
                days.stream()
                        .map(
                                d ->
                                        new PayrollPerformanceDayView(
                                                d.date(),
                                                d.checkins(),
                                                d.effectiveVisits(),
                                                d.missingCheckins(),
                                                d.missingVisits(),
                                                d.deduction(),
                                                d.evidenceComplete()))
                        .toList(),
                "按已结束的考核日统计；请假时段按工作时间占比免考核，剩余次数向上取整。");
    }

    private static AttendanceAssessmentView unavailable(LocalDate through, String message) {
        return new AttendanceAssessmentView("UNAVAILABLE", through, null, null, List.of(), message);
    }
}
