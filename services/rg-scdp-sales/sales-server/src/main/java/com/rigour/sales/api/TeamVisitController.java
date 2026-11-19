package com.rigour.sales.api;

import com.rigour.sales.api.v1.model.TeamVisitPage;
import com.rigour.sales.infrastructure.persistence.TeamVisitQuery;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ApiResponse;
import com.rigour.shared.core.exception.RequestValidationException;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
public class TeamVisitController {
    private final TeamVisitQuery visits;
    private final com.rigour.sales.application.service.SalesFieldworkService fieldwork;
    private final com.rigour.sales.application.port.out.SalesManagementDirectory directory;

    public TeamVisitController(
            TeamVisitQuery visits,
            com.rigour.sales.application.service.SalesFieldworkService fieldwork,
            com.rigour.sales.application.port.out.SalesManagementDirectory directory) {
        this.visits = visits;
        this.fieldwork = fieldwork;
        this.directory = directory;
    }

    @GetMapping("/api/v1/sales/management/visits/records")
    public ApiResponse<TeamVisitPage> records(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int size) {
        var caller = AuthorizationContext.requireCurrent();
        String action = "sales:dashboard:read";
        if (!"TENANT".equals(caller.principalScope()))
            throw new AuthorizationDeniedException(action);
        AuthorizationContext.requirePermission(action);
        if (from.isAfter(to)
                || from.plusYears(1).isBefore(to)
                || size < 1
                || size > 100
                || page < 1
                || page > 100000
                || keyword.length() > 80) throw new RequestValidationException("请输入有效的拜访日期和分页条件");
        var scope = fieldwork.access(action);
        var result =
                visits.find(
                        caller.tenantId(),
                        scope,
                        from,
                        to,
                        keyword,
                        fieldwork.matchingEmployeeCodes(caller, scope, keyword),
                        page,
                        size);
        var codes =
                result.items().stream()
                        .map(TeamVisitPage.Row::employee)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        var names = new java.util.HashMap<String, String>();
        if (!codes.isEmpty())
            directory
                    .identities(caller, codes)
                    .forEach(e -> names.put(e.employeeCode(), e.employeeName()));
        return ApiResponse.success(
                new TeamVisitPage(
                        result.items().stream()
                                .map(
                                        row ->
                                                row.displayName(
                                                        names.getOrDefault(
                                                                row.employee(), row.employee())))
                                .toList(),
                        result.total()));
    }
}
