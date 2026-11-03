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
    public HrPayrollPerformanceExportController(HrPayrollPerformanceExportService service) {
        this.service = service;
    }
    @Override
    public ApiResponse<List<PayrollPerformanceExportView>> performanceExport(PayrollQuery query) {
        return ApiResponse.success(service.export(query));
    }
}
