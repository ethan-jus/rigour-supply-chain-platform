package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPayrollComponentApi;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrPayrollComponentService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HrPayrollComponentController implements HrPayrollComponentApi {
    private final HrPayrollComponentService service;

    public HrPayrollComponentController(HrPayrollComponentService service) {
        this.service = service;
    }

    public ApiResponse<List<PayrollComponentVersionView>> list(String kind, String employeeCode) {
        return ApiResponse.success(service.list(kind, employeeCode));
    }

    public ApiResponse<PayrollComponentVersionView> save(PayrollComponentSaveCommand c) {
        return ApiResponse.success(service.save(c));
    }
}
