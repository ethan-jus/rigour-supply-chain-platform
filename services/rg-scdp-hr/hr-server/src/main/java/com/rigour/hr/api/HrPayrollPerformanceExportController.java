package com.rigour.hr.api;

import com.rigour.hr.api.v1.HrPayrollPerformanceExportApi;
import com.rigour.hr.api.v1.model.PayrollPerformanceExportView;
import com.rigour.hr.api.v1.model.PayrollQuery;
import com.rigour.hr.application.service.HrPayrollPerformanceExportService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public final class HrPayrollPerformanceExportController implements HrPayrollPerformanceExportApi {
    private final HrPayrollPerformanceExportService service;
    private final com.rigour.hr.application.service.HrPayrollCommissionReviewService review;
    public HrPayrollPerformanceExportController(HrPayrollPerformanceExportService service,
            com.rigour.hr.application.service.HrPayrollCommissionReviewService review) {
        this.service = service;
        this.review = review;
    }
    @Override
    public ApiResponse<List<PayrollPerformanceExportView>> performanceExport(PayrollQuery query) {
        return ApiResponse.success(service.export(query));
    }
    @Override
    public ApiResponse<List<com.rigour.hr.api.v1.model.PayrollCommissionReviewView>> commissionReview(PayrollQuery query) {
        return ApiResponse.success(review.preview(query));
    }
}
