package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.HrEmployeeCityScopeCommand;
import com.rigour.hr.api.v1.model.HrEmployeeView;
import com.rigour.hr.api.v1.model.PayrollRuleCommand;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 复用既有城市管辖记录；由员工保存事务统一提交，任何一项失败均回滚。 */
@Service
public class HrEmployeeCityScopeService {
    private final HrPayrollService payroll;
    private final HrPayrollStore store;

    public HrEmployeeCityScopeService(HrPayrollService payroll, HrPayrollStore store) {
        this.payroll = payroll;
        this.store = store;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(HrEmployeeView employee, List<HrEmployeeCityScopeCommand> commands) {
        if (commands == null || commands.isEmpty()) return;
        var actor = HrPayrollService.actor("hr:payroll-rule:write");
        if (!Set.of("城市总", "见习城市总").contains(Objects.toString(employee.positionName(), "").strip()))
            throw bad("仅城市总和见习城市总可配置管辖城市");
        if (commands.size() > 100) throw bad("每次最多维护100条管辖记录");
        String tenant = actor.tenantId().toString();
        store.lock(tenant);
        var existing = store.rules(tenant).stream().collect(Collectors.toMap(r -> r.id(), Function.identity()));
        var keys = new HashSet<String>();
        for (var c : commands) {
            if (c == null || c.cityCode() == null || c.cityName() == null
                    || c.cityCode().isBlank() || c.cityName().isBlank()
                    || c.effectiveFrom() == null) throw bad("请完整填写管辖城市及任期开始日期");
            var previous = c.id() == null ? null : existing.get(c.id());
            if (c.id() != null && (previous == null || !"CITY_MANAGER".equals(previous.data().kindCode())
                    || !employee.employeeCode().equals(previous.data().employeeCode())))
                throw bad("管辖记录不存在或不属于该员工");
            if (c.id() == null && (c.requestId() == null || c.requestId().isBlank() || c.requestId().length() > 64))
                throw bad("新增管辖记录请求标识无效");
            if (!keys.add(c.id() == null ? "new:" + c.requestId() : "id:" + c.id()))
                throw bad("管辖记录不能重复提交");
            var old = previous == null ? null : previous.data();
            String requestId = old == null
                    ? UUID.nameUUIDFromBytes((tenant + ":" + employee.employeeCode() + ":" + c.requestId()).getBytes(StandardCharsets.UTF_8)).toString()
                    : old.requestId();
            String name = c.cityName() + " · " + employee.employeeName() + " · " + c.effectiveFrom();
            var command = new PayrollRuleCommand(
                    requestId, c.revision(),
                    name.substring(0, Math.min(120, name.length())),
                    "CITY_MANAGER", employee.employeeCode(), List.of(), c.cityCode(), c.cityName(),
                    old == null ? true : old.includeOwnSalesCommission(),
                    old == null ? null : old.productId(), old == null ? null : old.productVariantId(),
                    old == null ? null : old.productName(), old == null ? null : old.unitCode(),
                    old == null ? null : old.groupCode(), old == null ? "CITY_MANAGER" : old.recipientRoleCode(),
                    old == null ? null : old.methodCode(), old == null ? null : old.rate(),
                    old == null ? null : old.amount(), c.effectiveFrom(),
                    c.effectiveTo() == null ? LocalDate.of(9999, 12, 31) : c.effectiveTo(), c.enabled(), c.remark(),
                    old == null ? null : old.performancePolicy(), old == null ? null : old.allowanceTypeCode(),
                    old == null ? null : old.fixedAllowance(), old == null ? null : old.categoryId(),
                    old == null ? null : old.categoryName(), old == null ? null : old.cityCommission());
            payroll.saveRule(c.id(), command);
        }
    }

    private BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
