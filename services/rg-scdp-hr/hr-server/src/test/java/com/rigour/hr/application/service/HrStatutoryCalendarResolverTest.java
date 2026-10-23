package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.hr.domain.payroll.SalarySchemeCalculator;
import com.rigour.settings.api.v1.model.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class HrStatutoryCalendarResolverTest {
    static StatutoryCalendarView reference(int year, String version) {
        var days = new ArrayList<StatutoryCalendarDayView>();
        for (var d = LocalDate.of(year, 1, 1); d.getYear() == year; d = d.plusDays(1)) {
            boolean work = d.getDayOfWeek().getValue() <= 5;
            if (d.getMonthValue() == 10 && d.getDayOfMonth() <= 7) work = false;
            if (d.equals(LocalDate.of(year, 10, 10))) work = true;
            days.add(new StatutoryCalendarDayView(d, "测试参考日历", work, work ? "WORKDAY" : "HOLIDAY"));
        }
        return new StatutoryCalendarView(
                year,
                "AVAILABLE",
                version,
                Instant.EPOCH,
                List.of("https://www.gov.cn/test"),
                days,
                false,
                null);
    }

    static PayrollWorkCalendar automatic() {
        return new PayrollWorkCalendar(
                List.of(1, 2, 3, 4, 5),
                List.of(),
                List.of(),
                List.of(),
                new PayrollWorkWindow(LocalTime.of(9, 0), LocalTime.of(6, 0), true, "IGNORE"),
                List.of(),
                null,
                "STATUTORY",
                null);
    }

    @Test
    void annualReferencesDriveWorkDatesAndHrOverridesTakePrecedence() {
        var source = mock(HrPayrollSource.class);
        when(source.statutoryCalendar("tenant", 2026)).thenReturn(reference(2026, "v1"));
        var original = automatic();
        var rule =
                new PayrollWorkCalendar(
                        original.weekdays(),
                        List.of(LocalDate.of(2026, 10, 1)),
                        List.of(LocalDate.of(2026, 10, 10)),
                        List.of(),
                        original.window(),
                        List.of(),
                        null,
                        "STATUTORY",
                        null);
        var resolved =
                new HrStatutoryCalendarResolver(source)
                        .resolve("tenant", rule, YearMonth.of(2026, 10));
        var work =
                SalarySchemeCalculator.workDates(
                        YearMonth.of(2026, 10),
                        resolved.weekdays(),
                        resolved.workDays(),
                        resolved.restDays());
        assertThat(work)
                .contains(LocalDate.of(2026, 10, 1))
                .doesNotContain(LocalDate.of(2026, 10, 10));
        assertThat(resolved.source()).isEqualTo("STATUTORY_SNAPSHOT");
        assertThat(resolved.referenceVersion()).isEqualTo("v1");
        assertThat(rule.source()).isEqualTo("STATUTORY");
    }

    @Test
    void missingFutureYearsCannotBeTreatedAsAllWorkingDays() {
        var source = mock(HrPayrollSource.class);
        when(source.statutoryCalendar("tenant", 2027))
                .thenReturn(
                        new StatutoryCalendarView(
                                2027,
                                "NOT_PUBLISHED",
                                null,
                                null,
                                List.of(),
                                List.of(),
                                false,
                                "等待发布"));
        assertThatThrownBy(
                        () ->
                                new HrStatutoryCalendarResolver(source)
                                        .resolve("tenant", automatic(), YearMonth.of(2027, 1)))
                .hasMessageContaining("2027年");
    }

    @Test
    void historicalManualCalendarsRemainUnchangedAndDoNotCallTheSource() {
        var source = mock(HrPayrollSource.class);
        var c = automatic();
        var manual =
                new PayrollWorkCalendar(
                        List.of(1, 2, 3, 4, 5, 6, 7),
                        List.of(),
                        List.of(),
                        List.of(),
                        c.window(),
                        List.of(),
                        null);
        assertThat(
                        new HrStatutoryCalendarResolver(source)
                                .resolve("tenant", manual, YearMonth.of(2025, 1)))
                .isSameAs(manual);
        verifyNoInteractions(source);
    }
}
