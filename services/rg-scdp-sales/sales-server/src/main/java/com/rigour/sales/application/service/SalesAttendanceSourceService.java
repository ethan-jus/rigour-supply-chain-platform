package com.rigour.sales.application.service;

import com.rigour.sales.api.v1.model.SalesAttendanceModels.*;
import com.rigour.sales.application.port.out.SalesAttendanceSourceStore;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.List;

@Service
public class SalesAttendanceSourceService {
    private final SalesAttendanceSourceStore store;

    public SalesAttendanceSourceService(SalesAttendanceSourceStore store) {
        this.store = store;
    }

    public List<Evidence> evidence(Query query) {
        var actor = AuthorizationContext.requireCurrent();
        String action = "sales:attendance:source-read";
        if (actor.tenantId() == null || !"SERVICE".equals(actor.principalScope()))
            throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(action);
        if (query == null
                || query.employeeCodes() == null
                || query.employeeCodes().isEmpty()
                || query.employeeCodes().size() > 100
                || query.employeeCodes().stream()
                        .anyMatch(c -> c == null || c.isBlank() || c.length() > 50)
                || query.from() == null
                || query.to() == null
                || query.to().isBefore(query.from())
                || !YearMonth.from(query.from()).equals(YearMonth.from(query.to())))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择同月日期及1至100名员工", List.of());
        return store.evidence(actor.tenantId().toString(), query);
    }
}
