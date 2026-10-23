package com.rigour.settings.domain.calendar;

import java.net.URI;
import java.time.LocalDate;
import java.util.*;

public final class StatutoryCalendarValidation {
    private StatutoryCalendarValidation() {}

    public static void validate(int year, StatutorySourceYear value) {
        if (value == null || value.year() != year || value.papers() == null || value.days() == null)
            throw new IllegalArgumentException("节假日日历年份或结构无效");
        if (value.papers().isEmpty() && value.days().isEmpty()) return;
        if (value.papers().isEmpty() || value.days().size() < 15 || value.days().size() > 80)
            throw new IllegalArgumentException("节假日日历未完整发布");
        for (String paper : value.papers()) {
            var uri = URI.create(paper);
            String host = uri.getHost();
            if (!Set.of("https", "http").contains(uri.getScheme())
                    || host == null
                    || !(host.equals("gov.cn") || host.endsWith(".gov.cn"))
                    || uri.getUserInfo() != null) throw new IllegalArgumentException("节假日缺少可信公告来源");
        }
        var dates = new HashSet<LocalDate>();
        for (var day : value.days())
            if (day.date() == null
                    || day.name() == null
                    || day.name().isBlank()
                    || day.name().length() > 100
                    || day.isOffDay() == null
                    || !dates.add(day.date())
                    || !(day.date().getYear() == year
                            || day.date().getYear() == year - 1
                                    && day.date().getMonthValue() == 12))
                throw new IllegalArgumentException("节假日日期重复或超出年度范围");
    }
}
