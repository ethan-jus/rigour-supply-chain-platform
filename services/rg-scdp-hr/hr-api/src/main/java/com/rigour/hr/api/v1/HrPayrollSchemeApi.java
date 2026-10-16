package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.api.v1.model.PayrollCalculationView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

public interface HrPayrollSchemeApi {
    String PATH = "/api/v1/hr/payroll/schemes";

    @GetMapping(PATH)
    ApiResponse<List<PayrollSchemeVersionView>> list();

    @PostMapping(PATH)
    ApiResponse<PayrollSchemeVersionView> create(@RequestBody PayrollSchemeSaveCommand command);

    @PutMapping(PATH + "/{id}")
    ApiResponse<PayrollSchemeVersionView> update(
            @PathVariable String id, @RequestBody PayrollSchemeSaveCommand command);

    @PostMapping(PATH + "/validate")
    ApiResponse<PayrollSchemeCheckView> validate(@RequestBody PayrollSchemeDefinition definition);

    @PostMapping(PATH + "/{id}/publish")
    ApiResponse<PayrollSchemeVersionView> publish(
            @PathVariable String id, @RequestBody PayrollSchemePublishCommand command);

    @PostMapping(PATH + "/{id}/stop")
    ApiResponse<PayrollSchemeVersionView> stop(
            @PathVariable String id, @RequestBody PayrollSchemeStopCommand command);

    @PostMapping(PATH + "/{id}/preview")
    ApiResponse<PayrollCalculationView> preview(
            @PathVariable String id, @RequestParam String month, @RequestParam String employeeCode);

    @GetMapping(PATH + "/monthly-facts/{month}/{employeeCode}")
    ApiResponse<PayrollSchemeFactsView> facts(
            @PathVariable String month, @PathVariable String employeeCode);

    @PutMapping(PATH + "/monthly-facts/{month}/{employeeCode}")
    ApiResponse<PayrollSchemeFactsView> saveFacts(
            @PathVariable String month,
            @PathVariable String employeeCode,
            @RequestBody PayrollSchemeFactsCommand command);
}
