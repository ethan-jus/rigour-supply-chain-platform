package com.rigour.analytics.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.analytics.application.port.out.CostDashboardStore;
import com.rigour.analytics.domain.cost.CostFact;
import com.rigour.analytics.infrastructure.persistence.entity.*;
import com.rigour.analytics.infrastructure.persistence.mapper.*;

import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

/** 已发布批次不可变；小型有界缓存只保存租户原始事实，任何响应都必须重新执行当前授权。 */
@Repository
public class MybatisPlusCostDashboardRepository implements CostDashboardStore {
    private final CostSnapshotMapper snapshots;
    private final CostFactMapper facts;
    private final CostRefreshStateMapper states;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, Snapshot> cache =
            Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true));

    public MybatisPlusCostDashboardRepository(
            CostSnapshotMapper snapshots,
            CostFactMapper facts,
            CostRefreshStateMapper states,
            ObjectMapper json,
            Clock analyticsClock) {
        this.snapshots = snapshots;
        this.facts = facts;
        this.states = states;
        this.json = json;
        this.clock = analyticsClock;
    }

    public void register(String tenant) {
        states.initialize(tenant);
    }

    public List<String> tenants() {
        return states
                .selectList(new QueryWrapper<CostRefreshStateEntity>().first("/* supply-scoped */"))
                .stream()
                .map(CostRefreshStateEntity::getTenantId)
                .toList();
    }

    public State state(String tenant) {
        var s =
                states.selectOne(
                        new QueryWrapper<CostRefreshStateEntity>()
                                .first("/* supply-scoped */")
                                .eq("tenant_id", tenant));
        return s == null
                ? null
                : new State(
                        s.getSnapshotId(),
                        instant(s.getAttemptedAt()),
                        instant(s.getSucceededAt()),
                        s.getStatus());
    }

    public Snapshot snapshot(String tenant, String id) {
        var s =
                snapshots.selectOne(
                        new QueryWrapper<CostSnapshotEntity>()
                                .first("/* supply-scoped */")
                                .eq("tenant_id", tenant)
                                .eq("id", id));
        if (s == null
                || instant(s.getGeneratedAt())
                        .isBefore(clock.instant().minus(Duration.ofHours(24))))
            throw com.rigour.analytics.application.service.CostDashboardFailure.expired();
        String key = tenant + ":" + id;
        var cached = cache.get(key);
        if (cached != null) return cached;
        var rows =
                facts.selectList(
                        new QueryWrapper<CostFactEntity>()
                                .first("/* supply-scoped */")
                                .eq("tenant_id", tenant)
                                .eq("snapshot_id", id)
                                .orderByAsc("source_service", "source_id"));
        var records =
                rows.stream().map(r -> json.readValue(r.getRecordJson(), CostFact.class)).toList();
        if (records.size() != s.getRowCount()) throw new IllegalStateException("成本批次记录不完整");
        Map<String, String> versions =
                json.readValue(
                        s.getVersionsJson(),
                        json.getTypeFactory()
                                .constructMapType(LinkedHashMap.class, String.class, String.class));
        List<City> cities =
                json.readValue(
                        s.getCitiesJson(),
                        json.getTypeFactory().constructCollectionType(List.class, City.class));
        Snapshot result =
                new Snapshot(
                        id,
                        tenant,
                        instant(s.getGeneratedAt()),
                        s.getEffectiveCutoff(),
                        versions,
                        cities,
                        records);
        synchronized (cache) {
            cache.put(key, result);
            while (cache.size() > 4) cache.remove(cache.keySet().iterator().next());
        }
        return result;
    }

    private static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }
}
