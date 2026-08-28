package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportResultView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardOverviewView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.api.v1.model.SupplyDashboardReconciliationView;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 供应链 BI 看板接口；只暴露分析口径，不承载业务写入流程。 */
public interface AnalyticsSupplyDashboardApi {
    String OVERVIEW_PATH = "/api/v1/analytics/supply/dashboard/overview";
    String REFRESH_RUNS_PATH = "/api/v1/analytics/supply/dashboard/refresh-runs";
    String TRUST_PATH = "/api/v1/analytics/supply/dashboard/trust";
    String RECONCILIATION_PATH = "/api/v1/analytics/supply/dashboard/reconciliation";
    String FILTER_OPTIONS_PATH = "/api/v1/analytics/supply/dashboard/filter-options";
    String CITY_COST_IMPORT_PATH = "/api/v1/analytics/supply/city-cost-records/import";
    String FEISHU_ARCHIVES_PATH = "/api/v1/analytics/supply/legacy/feishu-archives";

    @GetMapping(OVERVIEW_PATH)
    ApiResponse<SupplyDashboardOverviewView> overview(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode);

    @PostMapping(REFRESH_RUNS_PATH)
    ApiResponse<SupplyDashboardRefreshRunView> triggerRefreshRun();

    @GetMapping(TRUST_PATH)
    ApiResponse<SupplyDashboardDataTrustView> trust();

    @GetMapping(RECONCILIATION_PATH)
    ApiResponse<SupplyDashboardReconciliationView> reconciliation(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode);

    @GetMapping(FILTER_OPTIONS_PATH)
    ApiResponse<SupplyDashboardFilterOptionsView> filterOptions();

    @PostMapping(CITY_COST_IMPORT_PATH)
    ApiResponse<SupplyDashboardCityCostImportResultView> importCityCosts(
            @RequestBody SupplyDashboardCityCostImportCommand command);

    @GetMapping(FEISHU_ARCHIVES_PATH)
    ApiResponse<List<SupplyDashboardFeishuArchiveView>> feishuArchives();

    @PostMapping(FEISHU_ARCHIVES_PATH)
    ApiResponse<SupplyDashboardFeishuArchiveView> registerFeishuArchive(
            @RequestBody SupplyDashboardFeishuArchiveCommand command);
}
