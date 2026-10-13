package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesPayrollSourceApi;
import com.rigour.sales.api.v1.model.SalesPayrollEvidenceView;
import com.rigour.sales.application.service.SalesPayrollSourceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
public final class SalesPayrollSourceController implements SalesPayrollSourceApi {
    private final SalesPayrollSourceService service;

    public SalesPayrollSourceController(SalesPayrollSourceService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<SalesPayrollEvidenceView> evidence(
            String employeeCode, LocalDate from, LocalDate to) {
        return ApiResponse.success(service.evidence(employeeCode, from, to));
    }
}
