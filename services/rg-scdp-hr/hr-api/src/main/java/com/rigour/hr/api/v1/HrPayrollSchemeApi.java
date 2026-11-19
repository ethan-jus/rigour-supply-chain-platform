package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;


public interface HrPayrollSchemeApi {
    String PATH = "/api/v1/hr/payroll/schemes";

    @GetMapping(PATH + "/monthly-facts/{month}/{employeeCode}")
    ApiResponse<PayrollSchemeFactsView> facts(
            @PathVariable String month, @PathVariable String employeeCode);

    @PutMapping(PATH + "/monthly-facts/{month}/{employeeCode}")
    ApiResponse<PayrollSchemeFactsView> saveFacts(
            @PathVariable String month,
            @PathVariable String employeeCode,
            @RequestBody PayrollSchemeFactsCommand command);
}
