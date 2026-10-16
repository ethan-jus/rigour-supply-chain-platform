package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPayrollSchemeApi;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HrPayrollSchemeController implements HrPayrollSchemeApi {
    private final HrPayrollSchemeService service;
    private final ConfiguredPayrollService calculator;

    public HrPayrollSchemeController(
            HrPayrollSchemeService service, ConfiguredPayrollService calculator) {
        this.service = service;
        this.calculator = calculator;
    }

    public ApiResponse<List<PayrollSchemeVersionView>> list() {
        return ApiResponse.success(service.list());
    }

    public ApiResponse<PayrollSchemeVersionView> create(PayrollSchemeSaveCommand c) {
        return ApiResponse.success(service.save(null, c));
    }

    public ApiResponse<PayrollSchemeVersionView> update(String id, PayrollSchemeSaveCommand c) {
        return ApiResponse.success(service.save(id, c));
    }

    public ApiResponse<PayrollSchemeCheckView> validate(PayrollSchemeDefinition d) {
        return ApiResponse.success(service.validate(d));
    }

    public ApiResponse<PayrollSchemeVersionView> publish(String id, PayrollSchemePublishCommand c) {
        return ApiResponse.success(service.publish(id, c));
    }

    public ApiResponse<PayrollSchemeVersionView> stop(String id, PayrollSchemeStopCommand c) {
        return ApiResponse.success(service.stop(id, c));
    }

    public ApiResponse<PayrollCalculationView> preview(
            String id, String month, String employeeCode) {
        return ApiResponse.success(calculator.preview(id, month, employeeCode));
    }

    public ApiResponse<PayrollSchemeFactsView> facts(String month, String employeeCode) {
        return ApiResponse.success(service.facts(month, employeeCode));
    }

    public ApiResponse<PayrollSchemeFactsView> saveFacts(
            String month, String employeeCode, PayrollSchemeFactsCommand c) {
        return ApiResponse.success(service.saveFacts(month, employeeCode, c));
    }
}
