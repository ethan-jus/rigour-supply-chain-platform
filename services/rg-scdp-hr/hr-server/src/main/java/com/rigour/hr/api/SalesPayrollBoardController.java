package com.rigour.hr.api;

import com.rigour.hr.api.v1.SalesPayrollBoardApi;
import com.rigour.hr.api.v1.model.SalesPayrollBoardView;
import com.rigour.hr.application.service.SalesPayrollBoardService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class SalesPayrollBoardController implements SalesPayrollBoardApi {
    private final SalesPayrollBoardService service;

    public SalesPayrollBoardController(SalesPayrollBoardService service) {
        this.service = service;
    }

    public ApiResponse<SalesPayrollBoardView> team(String month) {
        return ApiResponse.success(service.team(month));
    }

    public ApiResponse<java.util.List<com.rigour.hr.api.v1.model.SalesPerformanceRow>> performance(
            String month) {
        return ApiResponse.success(service.performance(month));
    }

    public ApiResponse<Void> publish(String id, int revision) {
        service.publish(id, revision);
        return ApiResponse.success(null);
    }

    public ApiResponse<SalesPayrollBoardView> personal(String month) {
        return ApiResponse.success(service.personal(month));
    }
}
