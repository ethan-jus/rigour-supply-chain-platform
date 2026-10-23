package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.CostDashboardDetail;
import com.rigour.analytics.api.v1.model.CostDashboardExportCommand;
import com.rigour.analytics.api.v1.model.CostDashboardExportView;
import com.rigour.analytics.api.v1.model.CostDashboardQuery;
import com.rigour.analytics.api.v1.model.CostDashboardRecords;
import com.rigour.analytics.api.v1.model.CostDashboardReport;
import com.rigour.analytics.api.v1.model.CostDashboardWorkspace;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 沿用Gateway现有analytics路由；业务身份、租户及授权不接受请求参数覆盖。 */
public interface CostDashboardApi {
    String ROOT = "/api/v1/analytics/supply/cost-dashboard";

    @GetMapping(ROOT + "/overview")
    ApiResponse<CostDashboardReport> overview(@ModelAttribute CostDashboardQuery query);

    @GetMapping(ROOT + "/records")
    ApiResponse<CostDashboardRecords> records(@ModelAttribute CostDashboardQuery query);

    @GetMapping(ROOT + "/workspace")
    ApiResponse<CostDashboardWorkspace> workspace(@ModelAttribute CostDashboardQuery query);

    @GetMapping(ROOT + "/records/{source}/{id}")
    ApiResponse<CostDashboardDetail> detail(
            @ModelAttribute CostDashboardQuery query,
            @PathVariable String source,
            @PathVariable String id);

    @PostMapping(ROOT + "/exports")
    ApiResponse<CostDashboardExportView> export(@RequestBody CostDashboardExportCommand command);

    @GetMapping(ROOT + "/exports/{id}")
    ApiResponse<CostDashboardExportView> exportStatus(@PathVariable String id);

    @DeleteMapping(ROOT + "/exports/{id}")
    ApiResponse<Void> cancelExport(@PathVariable String id);

    @GetMapping(ROOT + "/exports/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable String id);
}
