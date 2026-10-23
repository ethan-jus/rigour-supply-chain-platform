package com.rigour.settings.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.settings.application.model.StatutoryCalendarSnapshot;
import com.rigour.settings.application.port.out.StatutoryCalendarStore;
import com.rigour.settings.domain.calendar.StatutorySourceYear;
import com.rigour.settings.infrastructure.persistence.entity.*;
import com.rigour.settings.infrastructure.persistence.mapper.*;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Repository
public class MybatisPlusStatutoryCalendarRepository implements StatutoryCalendarStore {
    private final StatutoryCalendarYearMapper years;
    private final StatutoryCalendarHistoryMapper history;
    private final ObjectMapper json;

    public MybatisPlusStatutoryCalendarRepository(
            StatutoryCalendarYearMapper years,
            StatutoryCalendarHistoryMapper history,
            ObjectMapper json) {
        this.years = years;
        this.history = history;
        this.json = json;
    }

    private static String id(int year) {
        return "GLOBAL:" + year;
    }

    private static LocalDateTime local(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    public Optional<StatutoryCalendarSnapshot> find(int year) {
        return Optional.ofNullable(years.selectById(id(year)))
                .map(
                        r ->
                                new StatutoryCalendarSnapshot(
                                        year,
                                        r.status,
                                        r.contentJson == null
                                                ? null
                                                : json.readValue(
                                                        r.contentJson, StatutorySourceYear.class),
                                        r.contentVersion,
                                        r.fetchedAt == null
                                                ? null
                                                : r.fetchedAt.toInstant(ZoneOffset.UTC),
                                        r.attemptedAt == null
                                                ? null
                                                : r.attemptedAt.toInstant(ZoneOffset.UTC)));
    }

    public Set<Integer> years() {
        var result = new HashSet<Integer>();
        for (var row :
                years.selectList(
                        new QueryWrapper<StatutoryCalendarYearEntity>().eq("tenant_id", "GLOBAL")))
            result.add(row.calendarYear);
        return Set.copyOf(result);
    }

    public boolean acquire(int year, String token, Instant now) {
        if (years.selectById(id(year)) == null) {
            var row = new StatutoryCalendarYearEntity();
            row.id = id(year);
            row.tenantId = "GLOBAL";
            row.calendarYear = year;
            row.status = "PENDING";
            try {
                years.insert(row);
            } catch (DuplicateKeyException raced) {
                /* Another instance created the same public reference row. */
            }
        }
        return years.update(
                        null,
                        new UpdateWrapper<StatutoryCalendarYearEntity>()
                                .eq("id", id(year))
                                .eq("tenant_id", "GLOBAL")
                                .and(
                                        q ->
                                                q.isNull("lease_until")
                                                        .or()
                                                        .le("lease_until", local(now)))
                                .and(
                                        q ->
                                                q.isNull("attempted_at")
                                                        .or()
                                                        .le(
                                                                "attempted_at",
                                                                local(now.minusSeconds(21600))))
                                .set("lease_token", token)
                                .set("lease_until", local(now.plusSeconds(60)))
                                .set("attempted_at", local(now)))
                == 1;
    }

    @Transactional
    public void complete(
            int year, String token, StatutorySourceYear content, String version, Instant now) {
        var old = years.selectById(id(year));
        String data = json.writeValueAsString(content);
        int updated =
                years.update(
                        null,
                        new UpdateWrapper<StatutoryCalendarYearEntity>()
                                .eq("id", id(year))
                                .eq("tenant_id", "GLOBAL")
                                .eq("lease_token", token)
                                .set("status", "AVAILABLE")
                                .set("content_json", data)
                                .set("content_version", version)
                                .set("fetched_at", local(now))
                                .set("lease_until", null)
                                .set("lease_token", null));
        if (updated == 1 && !Objects.equals(old.contentVersion, version)) {
            var row = new StatutoryCalendarHistoryEntity();
            row.id = UUID.randomUUID().toString();
            row.tenantId = "GLOBAL";
            row.calendarYear = year;
            row.contentJson = data;
            row.contentVersion = version;
            row.fetchedAt = local(now);
            history.insert(row);
        }
    }

    public void failed(int year, String token, String status, Instant now) {
        years.update(
                null,
                new UpdateWrapper<StatutoryCalendarYearEntity>()
                        .eq("id", id(year))
                        .eq("tenant_id", "GLOBAL")
                        .eq("lease_token", token)
                        .set("status", status)
                        .set("lease_until", null)
                        .set("lease_token", null));
    }
}
