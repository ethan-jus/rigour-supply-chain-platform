package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPayrollSchemeApi;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;


@RestController
public class HrPayrollSchemeController implements HrPayrollSchemeApi {
    private final HrPayrollSchemeService service;

    public HrPayrollSchemeController(HrPayrollSchemeService service) {
        this.service = service;
    }

    public ApiResponse<PayrollSchemeFactsView> facts(String month, String employeeCode) {
        return ApiResponse.success(service.facts(month, employeeCode));
    }

    public ApiResponse<PayrollSchemeFactsView> saveFacts(
            String month, String employeeCode, PayrollSchemeFactsCommand c) {
        return ApiResponse.success(service.saveFacts(month, employeeCode, c));
    }
}
