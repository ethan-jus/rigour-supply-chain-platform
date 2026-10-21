package com.rigour.hr.infrastructure.persistence.repository;

import static com.rigour.hr.infrastructure.persistence.repository.HrPayrollPersistenceSupport.error;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollSchemeStore;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.core.api.ErrorCode;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Repository
public class MybatisPlusHrPayrollSchemeRepository implements HrPayrollSchemeStore {
    private final HrPayrollSchemeMapper mapper;
    private final HrPayrollSchemeInputMapper inputs;
    private final HrPayrollPersistenceSupport locks;
    private final ObjectMapper json;

    public MybatisPlusHrPayrollSchemeRepository(
            HrPayrollSchemeMapper mapper,
            HrPayrollSchemeInputMapper inputs,
            HrPayrollPersistenceSupport locks,
            ObjectMapper json) {
        this.mapper = mapper;
        this.inputs = inputs;
        this.locks = locks;
        this.json = json;
    }

    @Override
    public List<PayrollSchemeVersionView> versions(String tenant) {
        var rows =
                mapper.selectList(
                        new QueryWrapper<HrPayrollSchemeEntity>()
                                .eq("tenant_id", tenant)
                                .orderByDesc("updated_time")
                                .orderByAsc("id")
                                .last("LIMIT 10001"));
        if (rows.size() > 10000) throw error(ErrorCode.BAD_REQUEST, "薪酬方案版本超过查询上限，请联系管理员归档");
        return rows.stream().map(this::view).toList();
    }

    private HrPayrollSchemeEntity row(String tenant, String id) {
        var row =
                mapper.selectOne(
                        new QueryWrapper<HrPayrollSchemeEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id));
        if (row == null) throw error(ErrorCode.NOT_FOUND, "薪酬方案不存在");
        return row;
    }

    private PayrollSchemeVersionView view(HrPayrollSchemeEntity r) {
        return new PayrollSchemeVersionView(
                r.getId(),
                r.getSchemeId(),
                r.getVersion(),
                r.getRevision(),
                r.getStatus(),
                json.readValue(r.getDataJson(), PayrollSchemeDefinition.class),
                r.getReason(),
                r.getCreatedBy(),
                r.getPublishedBy(),
                r.getUpdatedTime().toInstant(ZoneOffset.UTC),
                r.getPublishedTime() == null
                        ? null
                        : r.getPublishedTime().toInstant(ZoneOffset.UTC),
                r.getStopFrom());
    }

    @Override
    @Transactional
    public PayrollSchemeVersionView save(
            String tenant, String id, PayrollSchemeSaveCommand c, String actor) {
        locks.lock(tenant);
        String body = json.writeValueAsString(c.definition());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if (id == null) {
            var duplicate =
                    mapper.selectOne(
                            new QueryWrapper<HrPayrollSchemeEntity>()
                                    .eq("tenant_id", tenant)
                                    .eq("request_id", c.requestId()));
            if (duplicate != null) {
                if (!Objects.equals(
                                json.readValue(
                                        duplicate.getDataJson(), PayrollSchemeDefinition.class),
                                c.definition())
                        || c.schemeId() != null && !c.schemeId().equals(duplicate.getSchemeId()))
                    throw error(ErrorCode.CONFLICT, "请求编号已使用，请刷新后重试");
                return view(duplicate);
            }
            var r = new HrPayrollSchemeEntity();
            r.setId(UUID.randomUUID().toString());
            r.setTenantId(tenant);
            r.setRequestId(c.requestId());
            r.setSchemeId(c.schemeId() == null ? r.getId() : c.schemeId());
            int next =
                    versions(tenant).stream()
                                    .filter(v -> r.getSchemeId().equals(v.schemeId()))
                                    .mapToInt(PayrollSchemeVersionView::version)
                                    .max()
                                    .orElse(0)
                            + 1;
            r.setVersion(next);
            r.setRevision(0);
            r.setStatus("DRAFT");
            r.setDataJson(body);
            r.setReason(c.reason());
            r.setCreatedBy(actor);
            r.setUpdatedTime(now);
            mapper.insert(r);
            event(tenant, r.getId(), "CREATE_DRAFT", c, c.reason(), actor);
            return view(r);
        }
        var previous = row(tenant, id);
        if (!"DRAFT".equals(previous.getStatus()))
            throw error(ErrorCode.CONFLICT, "已发布规则不可修改，请创建新版本");
        if (!Objects.equals(previous.getRevision(), c.revision()))
            throw error(ErrorCode.CONFLICT, "方案已更新，请刷新");
        int changed =
                mapper.update(
                        null,
                        new UpdateWrapper<HrPayrollSchemeEntity>()
                                .eq("tenant_id", tenant)
                                .eq("id", id)
                                .eq("revision", c.revision())
                                .eq("status", "DRAFT")
                                .set("data_json", body)
                                .set("reason", c.reason())
                                .set("updated_time", now)
                                .set("revision", c.revision() + 1));
        if (changed != 1) throw error(ErrorCode.CONFLICT, "方案已更新，请刷新");
        event(
                tenant,
                id,
                "EDIT_DRAFT",
                Map.of("before", view(previous), "after", c),
                c.reason(),
                actor);
        return view(row(tenant, id));
    }

    @Override
    @Transactional
    public PayrollSchemeVersionView publish(
            String tenant, String id, PayrollSchemePublishCommand c, String actor) {
        locks.lock(tenant);
        var old = row(tenant, id);
        if ("PUBLISHED".equals(old.getStatus()) && old.getRevision() == c.revision() + 1)
            return view(old);
        if (!"DRAFT".equals(old.getStatus()) || old.getRevision() != c.revision())
            throw error(ErrorCode.CONFLICT, "方案状态已改变，请刷新");
        var now = LocalDateTime.now(ZoneOffset.UTC);
        mapper.update(
                null,
                new UpdateWrapper<HrPayrollSchemeEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", id)
                        .eq("revision", c.revision())
                        .set("status", "PUBLISHED")
                        .set("revision", c.revision() + 1)
                        .set("published_by", actor)
                        .set("published_time", now)
                        .set("updated_time", now)
                        .set("reason", c.reason()));
        event(tenant, id, "PUBLISH", view(row(tenant, id)), c.reason(), actor);
        return view(row(tenant, id));
    }

    @Override
    @Transactional
    public PayrollSchemeVersionView stop(
            String tenant, String id, PayrollSchemeStopCommand c, String actor) {
        locks.lock(tenant);
        var old = row(tenant, id);
        if (Objects.equals(old.getStopFrom(), c.stopFrom())
                && old.getRevision() == c.revision() + 1) return view(old);
        if (!"PUBLISHED".equals(old.getStatus())
                || old.getRevision() != c.revision()
                || old.getStopFrom() != null) throw error(ErrorCode.CONFLICT, "版本已改变或已登记停用，请刷新");
        mapper.update(
                null,
                new UpdateWrapper<HrPayrollSchemeEntity>()
                        .eq("tenant_id", tenant)
                        .eq("id", id)
                        .eq("revision", c.revision())
                        .set("stop_from", c.stopFrom())
                        .set("revision", c.revision() + 1)
                        .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
        event(
                tenant,
                id,
                "STOP",
                Map.of("before", view(old), "after", view(row(tenant, id))),
                c.reason(),
                actor);
        return view(row(tenant, id));
    }

    @Override
    public PayrollSchemeFactsView facts(String tenant, String employee, String month) {
        var r = inputs.selectOne(inputQuery(tenant, employee, month));
        if (r == null) return new PayrollSchemeFactsView(0, null, List.of(), "");
        var c = json.readValue(r.getDataJson(), PayrollSchemeFactsCommand.class);
        return new PayrollSchemeFactsView(
                r.getRevision(), c.performanceBase(), c.metrics(), c.reason());
    }

    private QueryWrapper<HrPayrollSchemeInputEntity> inputQuery(
            String tenant, String employee, String month) {
        return new QueryWrapper<HrPayrollSchemeInputEntity>()
                .eq("tenant_id", tenant)
                .eq("employee_code", employee)
                .eq("payroll_month", month);
    }

    @Override
    @Transactional
    public PayrollSchemeFactsView saveFacts(
            String tenant,
            String employee,
            String month,
            PayrollSchemeFactsCommand c,
            String actor) {
        locks.lock(tenant);
        var old = inputs.selectOne(inputQuery(tenant, employee, month));
        if (c.revision() == null || c.revision() != (old == null ? 0 : old.getRevision()))
            throw error(ErrorCode.CONFLICT, "月度指标已更新，请刷新");
        var before = facts(tenant, employee, month);
        if (old == null) {
            old = new HrPayrollSchemeInputEntity();
            old.setId(UUID.randomUUID().toString());
            old.setTenantId(tenant);
            old.setEmployeeCode(employee);
            old.setPayrollMonth(month);
            old.setRevision(1);
            old.setDataJson(json.writeValueAsString(c));
            old.setUpdatedBy(actor);
            old.setUpdatedTime(LocalDateTime.now(ZoneOffset.UTC));
            inputs.insert(old);
        } else
            inputs.update(
                    null,
                    new UpdateWrapper<HrPayrollSchemeInputEntity>()
                            .eq("tenant_id", tenant)
                            .eq("id", old.getId())
                            .eq("revision", c.revision())
                            .set("data_json", json.writeValueAsString(c))
                            .set("revision", c.revision() + 1)
                            .set("updated_by", actor)
                            .set("updated_time", LocalDateTime.now(ZoneOffset.UTC)));
        event(
                tenant,
                employee + ":" + month,
                "MONTHLY_FACTS",
                Map.of("before", before, "after", c),
                c.reason(),
                actor);
        return facts(tenant, employee, month);
    }

    private void event(
            String tenant, String subject, String type, Object data, String reason, String actor) {
        mapper.event(
                UUID.randomUUID().toString(),
                tenant,
                subject,
                type,
                json.writeValueAsString(data),
                reason,
                actor);
    }
}
