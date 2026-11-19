package com.rigour.hr.api.v1;

import com.rigour.hr.api.v1.model.SalesPayrollBoardView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

public interface SalesPayrollBoardApi {
    @GetMapping(HrPayrollApi.BASE_PATH + "/sales-board")
    ApiResponse<SalesPayrollBoardView> team(@RequestParam String month);

    @GetMapping(HrPayrollApi.BASE_PATH + "/sales-performance")
    ApiResponse<java.util.List<com.rigour.hr.api.v1.model.SalesPerformanceRow>> performance(
            @RequestParam String month);

    @org.springframework.web.bind.annotation.PutMapping(
            HrPayrollApi.BASE_PATH + "/{id}/publication")
    ApiResponse<Void> publish(
            @org.springframework.web.bind.annotation.PathVariable String id,
            @RequestParam int revision);

    @GetMapping(HrPayrollApi.BASE_PATH + "/my-statement")
    ApiResponse<SalesPayrollBoardView> personal(@RequestParam String month);
}
