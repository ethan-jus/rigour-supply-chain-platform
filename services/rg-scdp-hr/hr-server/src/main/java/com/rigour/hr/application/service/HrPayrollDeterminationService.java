package com.rigour.hr.application.service;

import com.rigour.hr.api.v1.model.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Commits both existing audited inputs together; calculation remains a separate retryable step. */
@Service
public class HrPayrollDeterminationService {
    private final HrPayrollService payroll;
    private final HrPayrollSchemeService schemes;

    public HrPayrollDeterminationService(HrPayrollService payroll, HrPayrollSchemeService schemes) {
        this.payroll = payroll;
        this.schemes = schemes;
    }

    @Transactional
    public PayrollDeterminationView save(
            String month, String employee, PayrollDeterminationCommand command) {
        if (command == null || command.input() == null)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写个人月度薪酬核定内容", List.of());
        var input = payroll.saveInput(month, employee, command.input());
        PayrollSchemeFactsView facts = null;
        if (command.facts() != null) {
            var f = command.facts();
            facts =
                    schemes.saveFacts(
                            month,
                            employee,
                            new PayrollSchemeFactsCommand(
                                    f.revision(),
                                    f.performanceBase(),
                                    f.metrics(),
                                    command.input().reason(),
                                    f.performanceCompletionPercent(),
                                    f.commissionAdjustment()));
        }
        return new PayrollDeterminationView(input, facts);
    }
}
