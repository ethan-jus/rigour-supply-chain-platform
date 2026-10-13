package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;
import com.rigour.sales.application.port.out.SalesPayrollSourceStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public final class SalesPayrollSourceService {
    private final SalesPayrollSourceStore store;

    public SalesPayrollSourceService(SalesPayrollSourceStore store) {
        this.store = store;
    }

    public SalesPayrollEvidenceView evidence(String employee, LocalDate from, LocalDate to) {
        var actor = AuthorizationContext.requireCurrent();
        String permission = "sales:payroll:source-read";
        if (actor.tenantId() == null || !"SERVICE".equals(actor.principalScope()))
            throw new AuthorizationDeniedException(permission);
        AuthorizationContext.requirePermission(permission);
        if (employee == null
                || employee.isBlank()
                || employee.length() > 128
                || from == null
                || to == null
                || to.isBefore(from)
                || ChronoUnit.DAYS.between(from, to) > 31)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "员工和核算期间无效", List.of());
        return store.evidence(actor.tenantId().toString(), employee, from, to);
    }
}
