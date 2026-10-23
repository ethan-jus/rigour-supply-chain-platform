package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPayrollComponentApi;
import com.rigour.hr.api.v1.model.*;
import com.rigour.hr.application.service.HrPayrollComponentService;
import com.rigour.hr.application.service.HrPayrollRuleReviewService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class HrPayrollComponentController implements HrPayrollComponentApi {
    private final HrPayrollComponentService service;
    private final HrPayrollRuleReviewService reviews;

    public HrPayrollComponentController(
            HrPayrollComponentService service, HrPayrollRuleReviewService reviews) {
        this.service = service;
        this.reviews = reviews;
    }

    public ApiResponse<List<PayrollComponentVersionView>> list(String kind, String employeeCode) {
        return ApiResponse.success(service.list(kind, employeeCode));
    }

    public ApiResponse<PayrollComponentVersionView> save(PayrollComponentSaveCommand c) {
        return ApiResponse.success(service.save(c));
    }

    public ApiResponse<PayrollComponentActionResult> act(
            String id, PayrollComponentActionCommand command) {
        return ApiResponse.success(service.act(id, command));
    }

    public ApiResponse<List<PayrollComponentOperationView>> operations(String id) {
        return ApiResponse.success(service.operations(id));
    }

    public ApiResponse<List<PayrollRuleReviewView>> reviews(String subject, String month) {
        return ApiResponse.success(reviews.list(subject, month));
    }

    public ApiResponse<Void> resolveReview(String id, PayrollRuleReviewCommand command) {
        reviews.resolve(id, command);
        return ApiResponse.success(null);
    }
}
