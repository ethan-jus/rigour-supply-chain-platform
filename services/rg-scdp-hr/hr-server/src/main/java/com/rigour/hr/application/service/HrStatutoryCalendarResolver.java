package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.PayrollWorkCalendar;
import com.rigour.hr.application.port.out.HrPayrollSource;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import java.time.*;
import java.util.*;

/** Resolve the annual reference once into a frozen, reproducible payroll calendar. */
public final class HrStatutoryCalendarResolver {
    private final HrPayrollSource source;

    public HrStatutoryCalendarResolver(HrPayrollSource source) {
        this.source = source;
    }

    public PayrollWorkCalendar resolve(
            String tenant, PayrollWorkCalendar calendar, YearMonth month) {
        if (!"STATUTORY".equals(calendar.source())) return calendar;
        try {
            var reference = source.statutoryCalendar(tenant, month.getYear());
            if (reference == null
                    || reference.year() != month.getYear()
                    || !"AVAILABLE".equals(reference.status())
                    || reference.days() == null
                    || reference.days().size() != month.atDay(1).lengthOfYear()
                    || reference.version() == null) throw new IllegalStateException("年度法定日历尚未就绪");
            var rest = new TreeSet<LocalDate>();
            var work = new TreeSet<LocalDate>();
            var seen = new HashSet<LocalDate>();
            for (var day : reference.days()) {
                if (day.date() == null
                        || day.date().getYear() != month.getYear()
                        || !seen.add(day.date())) throw new IllegalStateException("年度法定日历日期异常");
                (day.workday() ? work : rest).add(day.date());
            }
            // HR overrides take precedence; restoring one date removes the opposite override.
            rest.removeAll(calendar.workDays());
            work.removeAll(calendar.restDays());
            rest.addAll(calendar.restDays());
            work.addAll(calendar.workDays());
            work.removeAll(rest);
            return new PayrollWorkCalendar(
                    List.of(1, 2, 3, 4, 5),
                    List.copyOf(work),
                    List.copyOf(rest),
                    calendar.exemptDays(),
                    calendar.window(),
                    List.of(),
                    null,
                    "STATUTORY_SNAPSHOT",
                    reference.version());
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(HrStatutoryCalendarResolver.class)
                    .warn(
                            "Statutory payroll calendar unavailable tenant={} year={}",
                            tenant,
                            month.getYear(),
                            unavailable);
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE,
                    month.getYear() + "年法定节假日日历尚不可用，暂不能计算应出勤和薪酬",
                    List.of());
        }
    }
}
