package com.rigour.hr.application.service;

import com.rigour.hr.application.port.out.HrAttendanceStore;
import com.rigour.hr.application.port.out.HrPayrollSchemeStore;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.domain.payroll.SalarySchemeCalculator;
import com.rigour.sales.api.v1.model.SalesAttendanceModels.Evidence;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/** Attendance uses any submitted, undeleted punch; performance review remains independent. */
@Service
public class PayrollAttendanceEvidenceService {
    private final HrPayrollSchemeStore schemes;
    private final HrPayrollSource source;

    private HrPayrollComponentService components;

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
                continue;
            }
            // A missing source must not silently fall back to calendar dates under a configured
            // window.
            result.remove(employee.code());
            try {
                var evidence =
                        source.performanceEvidence(
                                tenant, employee.code(), month.atDay(1), through.plusDays(1));
                var window =
                        independent.isPresent()
                                ? independent.get().definition().calendar().window()
                                : scheme.get().definition().calendar().window();
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
                org.slf4j.LoggerFactory.getLogger(PayrollAttendanceEvidenceService.class)
                        .warn(
                                "Payroll attendance source unavailable for employee {}",
                                employee.code(),
                                unavailable);
            }
        }
        return result;
    }
}
