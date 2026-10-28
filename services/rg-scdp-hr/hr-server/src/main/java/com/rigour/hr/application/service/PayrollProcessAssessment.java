package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.domain.payroll.*;
import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;

import java.math.*;
import java.time.*;
import java.util.*;

/** The same work windows and leave exemptions apply to confirmation and payroll. */
final class PayrollProcessAssessment {
    private PayrollProcessAssessment() {}

    static Set<LocalDate> dates(
            YearMonth month,
            PayrollWorkCalendar calendar,
            PayrollPersonalPolicy personal,
            PayrollSalesPolicy policy,
            LocalDate entry) {
        var dates =
                new HashSet<>(
                        SalarySchemeCalculator.workDates(
                                month,
                                calendar.weekdays(),
                                calendar.workDays(),
                                calendar.restDays(),
                                calendar.alternateWeekdays() == null
                                        ? List.of()
                                        : calendar.alternateWeekdays(),
                                calendar.alternateAnchor()));
        dates.removeAll(calendar.exemptDays());
        if (personal != null) dates.removeAll(personal.exemptDays());
        if (entry != null && policy.processProtectionDays() != null)
            dates.removeIf(d -> d.isBefore(entry.plusDays(policy.processProtectionDays())));
        return dates;
    }

    static Map<LocalDate, BigDecimal> fractions(
            PayrollWorkWindow window,
            Set<LocalDate> dates,
            List<AttendanceExceptionPeriod> exceptions) {
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        for (var date : dates) {
            var start = date.atTime(window.start());
            var end = (window.nextDay() ? date.plusDays(1) : date).atTime(window.end());
            long total = Duration.between(start, end).toSeconds();
            if (total <= 0) throw new IllegalArgumentException("考核时间窗无效");
            // Union intervals so legacy overlapping notes cannot exempt a period twice.
            var periods =
                    exceptions.stream()
                            .filter(
                                    p ->
                                            Set.of(
                                                            "COMPENSATORY_LEAVE",
                                                            "PAID_LEAVE",
                                                            "UNPAID_LEAVE")
                                                    .contains(p.kind()))
                            .filter(
                                    p ->
                                            p.start() != null
                                                    && p.end() != null
                                                    && p.start().isBefore(end)
                                                    && p.end().isAfter(start))
                            .sorted(Comparator.comparing(AttendanceExceptionPeriod::start))
                            .toList();
            long exempt = 0;
            var cursor = start;
            for (var period : periods) {
                var from = period.start().isAfter(cursor) ? period.start() : cursor;
                var until = period.end().isBefore(end) ? period.end() : end;
                if (until.isAfter(from)) {
                    exempt += Duration.between(from, until).toSeconds();
                    cursor = until;
                }
            }
            result.put(
                    date,
                    BigDecimal.valueOf(total - exempt)
                            .divide(BigDecimal.valueOf(total), 12, RoundingMode.DOWN));
        }
        return Map.copyOf(result);
    }

    static List<SalesVisitEvidence> visits(
            SalesPayrollEvidenceView evidence, PayrollWorkWindow window) {
        var result = new ArrayList<SalesVisitEvidence>();
        for (var visit : evidence.visits()) {
            if (visit.submittedAt() == null) continue;
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
            if (date != null)
                result.add(
                        new SalesVisitEvidence(
                                visit.sourceId(),
                                date,
                                visit.customerKey(),
                                visit.reviewStatus(),
                                visit.verifiedCommunicationMillis(),
                                visit.durationComplete()));
        }
        return List.copyOf(result);
    }

    static SalesPerformancePolicy policy(PayrollSalesPolicy p) {
        return new SalesPerformancePolicy(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "ASSESS",
                100,
                p.dailyCheckins(),
                p.checkinPenalty(),
                p.dailyVisits(),
                p.minimumSeconds(),
                p.visitPenalty(),
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }
}
