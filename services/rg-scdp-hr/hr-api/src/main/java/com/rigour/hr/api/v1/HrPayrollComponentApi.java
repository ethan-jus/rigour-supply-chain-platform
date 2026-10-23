package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.List;

public interface HrPayrollComponentApi {
    String PATH = "/api/v1/hr/payroll/components";

    @GetMapping(PATH)
    ApiResponse<List<PayrollComponentVersionView>> list(
            @RequestParam String kind, @RequestParam(required = false) String employeeCode);

    @PostMapping(PATH)
    ApiResponse<PayrollComponentVersionView> save(@RequestBody PayrollComponentSaveCommand command);

    @PostMapping(PATH + "/{id}/actions")
    ApiResponse<PayrollComponentActionResult> act(
            @PathVariable String id, @RequestBody PayrollComponentActionCommand command);

    @GetMapping(PATH + "/{id}/operations")
    ApiResponse<List<PayrollComponentOperationView>> operations(@PathVariable String id);

    @GetMapping(PATH + "/reviews")
    ApiResponse<List<PayrollRuleReviewView>> reviews(
            @RequestParam String subject, @RequestParam String month);

    @PostMapping(PATH + "/reviews/{id}/resolve")
    ApiResponse<Void> resolveReview(
            @PathVariable String id, @RequestBody PayrollRuleReviewCommand command);
}
