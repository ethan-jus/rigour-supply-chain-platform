package com.rigour.hr.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rigour.hr.api.v1.model.PayrollAuditView;
import com.rigour.hr.infrastructure.persistence.entity.HrPayrollAuditEntity;
import com.rigour.hr.infrastructure.persistence.mapper.HrPayrollAuditMapper;
import com.rigour.hr.infrastructure.persistence.mapper.HrPayrollMapper;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.ZoneOffset;
import java.util.List;

@Component
public class HrPayrollPersistenceSupport {
    private final HrPayrollMapper payroll;
    private final HrPayrollAuditMapper audits;
    private final ObjectMapper json;

    public HrPayrollPersistenceSupport(
            HrPayrollMapper payroll, HrPayrollAuditMapper audits, ObjectMapper json) {
        this.payroll = payroll;
        this.audits = audits;
        this.json = json;
    }

    // 与写操作处于同一事务，串行处理同租户的幂等重试及提成占用。
    public void lock(String tenant) {
        payroll.ensureGuard(tenant);
        payroll.lock(tenant);
        // 与工资写入同事务推进；确认、撤回、发放及补差失败时一起回滚。
        payroll.advanceAnalyticsVersion(tenant);
    }

    public void audit(
            String tenant, String id, String action, String actor, String reason, Object snapshot) {
        var row = new HrPayrollAuditEntity();
        row.setTenantId(tenant);
        row.setObjectId(id);
        row.setActionCode(action);
        row.setActorId(actor);
        row.setReason(reason);
        row.setSnapshotJson(json.writeValueAsString(snapshot));
        audits.insert(row);
    }

    public List<PayrollAuditView> history(String tenant, List<String> ids) {
        return audits
                .selectList(
                        new QueryWrapper<HrPayrollAuditEntity>()
                                .eq("tenant_id", tenant)
                                .in("object_id", ids)
                                .orderByDesc("id"))
                .stream()
                .map(
                        r ->
                                new PayrollAuditView(
                                        r.getActionCode(),
                                        r.getActorId(),
                                        r.getReason(),
                                        r.getCreatedTime().toInstant(ZoneOffset.UTC),
                                        r.getSnapshotJson()))
                .toList();
    }

    static void bind(QueryWrapper<?> q, String sql, List<Object> args) {
        StringBuilder bound = new StringBuilder();
        int index = 0;
        for (char c : sql.toCharArray())
            if (c == '?') bound.append('{').append(index++).append('}');
            else bound.append(c);
        if (index != args.size()) throw new IllegalArgumentException("权限条件参数不匹配");
        q.apply(bound.toString(), args.toArray());
    }

    static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    static BusinessException error(ErrorCode code, String message) {
        return new BusinessException(code, message, List.of());
    }

    static int pageSize(int size) {
        return size <= 0 ? 20 : Math.min(200, size);
    }
}
