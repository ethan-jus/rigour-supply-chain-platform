package com.rigour.hr.infrastructure.persistence.repository;

import static com.rigour.hr.infrastructure.persistence.repository.HrPayrollPersistenceSupport.error;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollComponentStore;
import com.rigour.hr.infrastructure.persistence.entity.*;
import com.rigour.hr.infrastructure.persistence.mapper.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.code.BusinessCodeGenerator;
import com.rigour.shared.core.code.BusinessCodeRule;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.*;

@Repository
public class MybatisPlusHrPayrollComponentRepository implements HrPayrollComponentStore {
    private final HrPayrollComponentMapper mapper;
    private final HrPayrollConfigurationMapper settings;
    private final HrPayrollPersistenceSupport locks;
    private final ObjectMapper json;

    public MybatisPlusHrPayrollComponentRepository(
            HrPayrollComponentMapper mapper,
            HrPayrollConfigurationMapper settings,
            HrPayrollPersistenceSupport locks,
            ObjectMapper json) {
        this.mapper = mapper;
        this.settings = settings;
        this.locks = locks;
        this.json = json;
    }

    public Optional<PayrollComponentVersionView> byRequest(String tenant, String requestId) {
        return Optional.ofNullable(
                        mapper.selectOne(
                                new QueryWrapper<HrPayrollComponentEntity>()
                                        .eq("tenant_id", tenant)
                                        .eq("request_id", requestId)))
                .map(this::view);
    }

    public List<PayrollComponentVersionView> versions(String tenant) {
        var rows =
                mapper.selectList(
                        new QueryWrapper<HrPayrollComponentEntity>()
                                .eq("tenant_id", tenant)
                                .orderByDesc("created_time")
                                .orderByAsc("id")
                                .last("LIMIT 10001"));
        if (rows.size() > 10000) throw error(ErrorCode.BAD_REQUEST, "规则版本超过查询上限，请联系管理员归档");
        return rows.stream().map(this::view).toList();
    }

    private PayrollComponentVersionView view(HrPayrollComponentEntity row) {
        return new PayrollComponentVersionView(
                row.getId(),
                row.getRuleId(),
                row.getVersion(),
                json.readValue(row.getDataJson(), PayrollComponentDefinition.class),
                row.getReason(),
                row.getCreatedBy(),
                row.getCreatedTime().toInstant(ZoneOffset.UTC),
                row.getRuleCode());
    }

    @Transactional
    public PayrollComponentVersionView save(
            String tenant, PayrollComponentSaveCommand c, String actor) {
        locks.lock(tenant);
        var duplicate =
                mapper.selectOne(
                        new QueryWrapper<HrPayrollComponentEntity>()
                                .eq("tenant_id", tenant)
                                .eq("request_id", c.requestId()));
        if (duplicate != null) {
            if (!Objects.equals(view(duplicate).definition(), c.definition())
                    || !Objects.equals(duplicate.getReason(), c.reason()))
                throw error(ErrorCode.CONFLICT, "请求编号已使用，请刷新重试");
            return view(duplicate);
        }
        var rows = versions(tenant);
        PayrollComponentVersionView previous =
                c.previousId() == null
                        ? null
                        : rows.stream()
                                .filter(r -> r.id().equals(c.previousId()))
                                .findFirst()
                                .orElseThrow(() -> error(ErrorCode.NOT_FOUND, "原规则不存在"));
        if (previous != null
                && rows.stream()
                        .anyMatch(
                                r ->
                                        r.ruleId().equals(previous.ruleId())
                                                && r.version() > previous.version()))
            throw error(ErrorCode.CONFLICT, "规则已有新版本，请刷新后重新编辑");
        var row = new HrPayrollComponentEntity();
        row.setId(UUID.randomUUID().toString());
        row.setTenantId(tenant);
        row.setRequestId(c.requestId());
        row.setRuleId(previous == null ? row.getId() : previous.ruleId());
        row.setRuleCode(
                previous == null
                        ? new BusinessCodeGenerator()
                                .generateUnique(
                                        BusinessCodeRule.daily("PCR", 6),
                                        code ->
                                                mapper.selectCount(
                                                                new QueryWrapper<
                                                                                HrPayrollComponentEntity>()
                                                                        .eq("tenant_id", tenant)
                                                                        .eq("rule_code", code))
                                                        == 0)
                        : previous.ruleCode());
        row.setVersion(previous == null ? 1 : previous.version() + 1);
        row.setKind(c.definition().kind());
        row.setDataJson(json.writeValueAsString(c.definition()));
        row.setReason(c.reason());
        row.setCreatedBy(actor);
        row.setCreatedTime(LocalDateTime.now(ZoneOffset.UTC));
        mapper.insert(row);
        locks.audit(tenant, row.getId(), "RULE_VERSION_CREATED", actor, c.reason(), view(row));
        return view(row);
    }

    public String unifiedFrom(String tenant) {
        var row = settings.selectById(tenant);
        return row == null ? null : row.getUnifiedFrom();
    }
}
