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
    public PayrollSchemeFactsView facts(String tenant, String employee, String month) {
        var r = inputs.selectOne(inputQuery(tenant, employee, month));
        if (r == null) return new PayrollSchemeFactsView(0, null, List.of(), "");
        var c = json.readValue(r.getDataJson(), PayrollSchemeFactsCommand.class);
        return new PayrollSchemeFactsView(
                r.getRevision(),
                c.performanceBase(),
                c.metrics(),
                c.reason(),
                c.performanceCompletionPercent(),
                c.commissionAdjustment());
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
