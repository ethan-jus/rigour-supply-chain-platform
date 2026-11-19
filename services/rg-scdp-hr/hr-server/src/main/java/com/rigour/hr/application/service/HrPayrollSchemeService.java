package com.rigour.hr.application.service;

import static com.rigour.hr.application.service.HrPayrollService.actor;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.port.out.HrPayrollSchemeStore;
import com.rigour.hr.application.port.out.HrPayrollStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.*;

@Service
public class HrPayrollSchemeService {
    private final HrPayrollSchemeStore store;
    private final HrPayrollStore payroll;

    public HrPayrollSchemeService(HrPayrollSchemeStore store, HrPayrollStore payroll) {
        this.store = store;
        this.payroll = payroll;
    }

    public PayrollSchemeFactsView facts(String month, String employee) {
        var a = actor("hr:payroll:read");
        period(month);
        payroll.employee(a.tenantId().toString(), employee, "hr:payroll:read");
        return store.facts(a.tenantId().toString(), employee, month);
    }

    @Transactional
    public PayrollSchemeFactsView saveFacts(
            String month, String employee, PayrollSchemeFactsCommand command) {
        var a = actor("hr:payroll:calculate");
        String tenant = a.tenantId().toString();
        period(month);
        payroll.employee(tenant, employee, "hr:payroll:calculate");
        payroll.lock(tenant);
        if (payroll.byMonth(tenant, employee, month)
                .map(r -> Set.of("CONFIRMED", "PAID").contains(r.statusCode()))
                .orElse(false)) throw bad("已确认月份不能改指标，请走工资更正");
        if (command == null) throw bad("核定内容不能为空");
        reason(command.reason());
        if (command.performanceBase() != null && command.performanceBase().signum() < 0)
            throw bad("绩效基数不能为负数");
        if (command.performanceCompletionPercent() != null
                && (command.performanceCompletionPercent().signum() < 0
                        || command.performanceCompletionPercent().precision() > 12
                        || command.performanceCompletionPercent().scale() > 4))
            throw bad("绩效完成率须为非负百分数，最多保留4位小数");
        if (command.commissionAdjustment() != null
                && (command.commissionAdjustment().scale() > 2
                        || command.commissionAdjustment().precision() > 14))
            throw bad("提成调整金额最多保留2位小数，且不能超出金额范围");
        if (command.metrics() == null) throw bad("指标列表不能为空");
        Set<String> codes = new HashSet<>();
        for (var actual : command.metrics()) {
            if (actual == null
                    || actual.code() == null
                    || actual.code().isBlank()
                    || !codes.add(actual.code())
                    || actual.value() == null
                    || actual.value().signum() < 0
                    || actual.evidence() == null
                    || actual.evidence().isBlank()) throw bad("指标须有唯一编码、非负实际值及核定依据");
        }
        var saved = store.saveFacts(tenant, employee, month, command, a.principalId().toString());
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info(
                        "Payroll monthly facts saved tenant={} employee={} month={} revision={}"
                            + " actor={}",
                        tenant,
                        employee,
                        month,
                        saved.revision(),
                        a.principalId());
        return saved;
    }

    static YearMonth period(String value) {
        try {
            var month = YearMonth.parse(value);
            if (month.getYear() < 2000 || month.getYear() > 2200) throw bad("月份超出范围");
            return month;
        } catch (java.time.DateTimeException | NullPointerException e) {
            throw bad("月份格式应为yyyy-MM");
        }
    }

    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) throw bad("请填写1至1000字的变更原因");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
