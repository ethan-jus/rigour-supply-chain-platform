package com.rigour.settings.application.service;

import com.rigour.settings.api.v1.model.*;
import com.rigour.settings.application.port.out.*;
import com.rigour.settings.domain.calendar.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class StatutoryCalendarService {
    private static final Logger log = LoggerFactory.getLogger(StatutoryCalendarService.class);
    private final StatutoryCalendarStore store;
    private final StatutoryCalendarSource source;
    private final Clock clock;
    private final ObjectMapper json;

    public StatutoryCalendarService(
            StatutoryCalendarStore store,
            StatutoryCalendarSource source,
            Clock clock,
            ObjectMapper json) {
        this.store = store;
        this.source = source;
        this.clock = clock;
        this.json = json;
    }

    private int currentYear() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))).getYear();
    }

    private void check(int year) {
        if (year < 2007 || year > currentYear() + 1)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日历年份须在2007年至下一年度之间", List.of());
    }

    public void refresh(int year) {
        check(year);
        Instant now = clock.instant();
        var previous = store.find(year);
        if (previous.isPresent()
                && previous.get().attemptedAt() != null
                && previous.get().attemptedAt().isAfter(now.minusSeconds(21600))) return;
        String token = UUID.randomUUID().toString();
        if (!store.acquire(year, token, now)) return;
        try {
            var document = source.fetch(year);
            validate(year, document);
            if (document.days().isEmpty()) {
                store.failed(year, token, "NOT_PUBLISHED", now);
                log.info("Statutory calendar not published year={}", year);
                return;
            }
            var canonical =
                    new StatutorySourceYear(
                            year,
                            document.papers().stream().distinct().sorted().toList(),
                            document.days().stream()
                                    .sorted(Comparator.comparing(StatutorySourceDay::date))
                                    .toList(),
                            document.announcementText());
            String hash = hash(json.writeValueAsString(canonical));
            store.complete(year, token, canonical, hash, now);
            log.info(
                    "Statutory calendar synchronized year={} days={} version={}",
                    year,
                    document.days().size(),
                    hash);
        } catch (RuntimeException failure) {
            store.failed(year, token, "UNAVAILABLE", now);
            log.warn(
                    "Statutory calendar synchronization failed year={}; cached snapshot retained",
                    year,
                    failure);
        }
    }

    public StatutoryCalendarView year(int year) {
        check(year);
        refresh(year);
        var primary = store.find(year).orElse(null);
        if (primary == null || primary.content() == null)
            return new StatutoryCalendarView(
                    year,
                    primary == null ? "UNAVAILABLE" : primary.status(),
                    null,
                    null,
                    List.of(),
                    List.of(),
                    false,
                    primary != null && "NOT_PUBLISHED".equals(primary.status())
                            ? "该年度法定节假日安排尚未同步到发布数据，系统将定时重试"
                            : "法定节假日日历暂不可用，请稍后重试");
        if (year <= currentYear()) refresh(year + 1);
        var overrides = new TreeMap<LocalDate, StatutorySourceDay>();
        var papers = new TreeSet<>(primary.content().papers());
        boolean stale = !"AVAILABLE".equals(primary.status());
        Instant fetchedAt = primary.fetchedAt();
        for (var day : primary.content().days())
            if (day.date().getYear() == year) overrides.put(day.date(), day);
        var following = store.find(year + 1).orElse(null);
        if (following != null && following.content() != null) {
            for (var day : following.content().days())
                if (day.date().getYear() == year) {
                    overrides.put(day.date(), day);
                    papers.addAll(following.content().papers());
                    stale |= !"AVAILABLE".equals(following.status());
                    if (following.fetchedAt() != null
                            && (fetchedAt == null || following.fetchedAt().isBefore(fetchedAt)))
                        fetchedAt = following.fetchedAt();
                }
        }
        var days = new ArrayList<StatutoryCalendarDayView>();
        for (var day = LocalDate.of(year, 1, 1); day.getYear() == year; day = day.plusDays(1)) {
            var override = overrides.get(day);
            boolean work =
                    override == null ? day.getDayOfWeek().getValue() <= 5 : !override.isOffDay();
            days.add(
                    new StatutoryCalendarDayView(
                            day,
                            override == null ? (work ? "工作日" : "周末休息") : override.name(),
                            work,
                            override == null
                                    ? (work ? "WORKDAY" : "WEEKEND")
                                    : (work ? "MAKEUP" : "HOLIDAY")));
        }
        return new StatutoryCalendarView(
                year,
                "AVAILABLE",
                hash(json.writeValueAsString(days)),
                fetchedAt,
                List.copyOf(papers),
                List.copyOf(days),
                stale,
                stale ? "同步暂不可用，当前使用最近成功保存的年度日历" : null);
    }

    public void refreshTrackedYears() {
        var years = new TreeSet<>(store.years());
        for (int year = currentYear() - 1; year <= currentYear() + 1; year++) years.add(year);
        for (int year : years) {
            try {
                refresh(year);
            } catch (RuntimeException failure) {
                log.warn("Statutory calendar refresh failed year={}", year, failure);
            }
        }
    }

    public static void validate(int year, StatutorySourceYear value) {
        StatutoryCalendarValidation.validate(year, value);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception invalid) {
            throw new IllegalStateException(invalid);
        }
    }
}
