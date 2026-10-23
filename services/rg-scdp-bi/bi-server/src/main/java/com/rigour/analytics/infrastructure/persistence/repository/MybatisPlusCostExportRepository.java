package com.rigour.analytics.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.analytics.application.port.out.CostExportStore;
import com.rigour.analytics.infrastructure.persistence.entity.CostExportEntity;
import com.rigour.analytics.infrastructure.persistence.mapper.CostExportMapper;

import org.springframework.stereotype.Repository;

import java.time.*;

@Repository
public class MybatisPlusCostExportRepository implements CostExportStore {
    private final CostExportMapper mapper;
    private final Clock clock;

    public MybatisPlusCostExportRepository(CostExportMapper mapper, Clock analyticsClock) {
        this.mapper = mapper;
        this.clock = analyticsClock;
    }

    public void create(Task t) {
        var e = new CostExportEntity();
        e.setId(t.id());
        e.setTenantId(t.tenant());
        e.setActorId(t.actor());
        e.setSnapshotId(t.snapshot());
        e.setAuthorizationHash(t.authorizationHash());
        e.setFilterJson(t.filterJson());
        e.setStatus("PENDING");
        e.setCreatedAt(LocalDateTime.ofInstant(t.createdAt(), ZoneOffset.UTC));
        mapper.insert(e);
    }

    public Task find(String tenant, String actor, String id) {
        var e =
                mapper.selectOne(
                        new QueryWrapper<CostExportEntity>()
                                .first("/* supply-scoped */")
                                .eq("tenant_id", tenant)
                                .eq("actor_id", actor)
                                .eq("id", id));
        return e == null
                ? null
                : new Task(
                        e.getId(),
                        e.getTenantId(),
                        e.getActorId(),
                        e.getSnapshotId(),
                        e.getAuthorizationHash(),
                        e.getFilterJson(),
                        e.getStatus(),
                        e.getErrorCode(),
                        e.getContent(),
                        e.getRowCount(),
                        e.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    public boolean start(String tenant, String id) {
        return mapper.update(
                        null,
                        new UpdateWrapper<CostExportEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("status", "PENDING")
                                .set("status", "RUNNING"))
                == 1;
    }

    public void finish(String tenant, String id, String content, long count) {
        mapper.update(
                null,
                new UpdateWrapper<CostExportEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", id)
                        .eq("status", "RUNNING")
                        .set("status", "READY")
                        .set("content", content)
                        .set("row_count", count));
    }

    public void fail(String tenant, String id, String error) {
        mapper.update(
                null,
                new UpdateWrapper<CostExportEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", id)
                        .in("status", "PENDING", "RUNNING")
                        .set("status", "FAILED")
                        .set("error_code", error)
                        .set("content", null));
    }

    public void cancel(String tenant, String actor, String id) {
        mapper.update(
                null,
                new UpdateWrapper<CostExportEntity>()
                        .eq("tenant_id", tenant)
                        .eq("actor_id", actor)
                        .eq("id", id)
                        .in("status", "PENDING", "RUNNING", "READY")
                        .set("status", "CANCELLED")
                        .set("content", null));
    }

    public void expire() {
        mapper.delete(
                new QueryWrapper<CostExportEntity>()
                        .lt(
                                "created_at",
                                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                                        .minusHours(24)));
        mapper.update(
                null,
                new UpdateWrapper<CostExportEntity>()
                        .in("status", "PENDING", "RUNNING")
                        .lt(
                                "created_at",
                                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                                        .minusMinutes(5))
                        .set("status", "FAILED")
                        .set("error_code", "EXPORT_TIMEOUT")
                        .set("content", null));
    }
}
