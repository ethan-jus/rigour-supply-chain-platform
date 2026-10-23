package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.CostDashboardApi;
import com.rigour.analytics.api.v1.model.CostDashboardDetail;
import com.rigour.analytics.api.v1.model.CostDashboardExportCommand;
import com.rigour.analytics.api.v1.model.CostDashboardExportView;
import com.rigour.analytics.api.v1.model.CostDashboardQuery;
import com.rigour.analytics.api.v1.model.CostDashboardRecords;
import com.rigour.analytics.api.v1.model.CostDashboardReport;
import com.rigour.analytics.api.v1.model.CostDashboardWorkspace;
import com.rigour.analytics.application.service.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CostDashboardController implements CostDashboardApi {
    private final CostDashboardService service;
    private final CostDashboardExportService exports;

    public CostDashboardController(
            CostDashboardService service, CostDashboardExportService exports) {
        this.service = service;
        this.exports = exports;
    }

    public ApiResponse<CostDashboardReport> overview(CostDashboardQuery q) {
        return ApiResponse.success(service.overview(q));
    }

    public ApiResponse<CostDashboardRecords> records(CostDashboardQuery q) {
        return ApiResponse.success(service.records(q));
    }

    public ApiResponse<CostDashboardWorkspace> workspace(CostDashboardQuery q) {
        return ApiResponse.success(service.workspace(q));
    }

    public ApiResponse<CostDashboardDetail> detail(CostDashboardQuery q, String source, String id) {
        return ApiResponse.success(service.detail(q, source, id));
    }

    public ApiResponse<CostDashboardExportView> export(CostDashboardExportCommand c) {
        if (c == null || c.query() == null) throw new IllegalArgumentException("缺少导出筛选");
        return ApiResponse.success(exports.create(c.query()));
    }

    public ApiResponse<CostDashboardExportView> exportStatus(String id) {
        return ApiResponse.success(exports.status(id));
    }

    public ApiResponse<Void> cancelExport(String id) {
        exports.cancel(id);
        return ApiResponse.success(null);
    }

    public ResponseEntity<byte[]> download(String id) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cost-dashboard.csv")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(exports.download(id));
    }
}
