package com.rigour.analytics.api.v1;

import com.rigour.analytics.api.v1.model.SupplyDashboardReceiptDetailsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardOverviewView;
import com.rigour.analytics.api.v1.model.SupplyDashboardOperatingAnalysisView;
import com.rigour.analytics.api.v1.model.SupplyDashboardProductSalesItemView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
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
    String OPERATING_ANALYSIS_PATH = "/api/v1/analytics/supply/dashboard/operating-analysis";
    String REFRESH_RUNS_PATH = "/api/v1/analytics/supply/dashboard/refresh-runs";
    String TRUST_PATH = "/api/v1/analytics/supply/dashboard/trust";
    String FILTER_OPTIONS_PATH = "/api/v1/analytics/supply/dashboard/filter-options";

    @GetMapping("/api/v1/analytics/supply/dashboard/department-filters")
    ApiResponse<com.rigour.analytics.api.v1.model.DepartmentDashboardFilters> departmentFilters();

    enum OverviewProjection { FULL, OPERATING, OPERATING_COMPARISON }

    enum OverviewRankingKind { PRODUCT, CATEGORY }

    @GetMapping(OVERVIEW_PATH)
    ApiResponse<SupplyDashboardOverviewView> overview(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode,
            @RequestParam(defaultValue = "FULL") OverviewProjection projection);

    @GetMapping(OVERVIEW_PATH + "/ranking")
    ApiResponse<List<SupplyDashboardProductSalesItemView>> operatingOverviewRanking(
            @RequestParam OverviewRankingKind kind,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode);

    /** 沿用 overview 查询参数及读取权限；不支持全局商品分类过滤，非 null productCategoryId 返回 BAD_REQUEST。 */
    @GetMapping(OPERATING_ANALYSIS_PATH)
    ApiResponse<SupplyDashboardOperatingAnalysisView> operatingAnalysis(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(required = false) String sourceSystemCode);

    @GetMapping("/api/v1/analytics/supply/dashboard/receipt-details")
    ApiResponse<SupplyDashboardReceiptDetailsView> receiptDetails(
            @RequestParam String kind,
            @RequestParam Instant from, @RequestParam Instant to,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String ownerStaffCode,
            @RequestParam(required = false) String customerTypeCode,
            @RequestParam(required = false) String sourceSystemCode,
            @RequestParam(required = false) Long productCategoryId,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);

    @PostMapping(REFRESH_RUNS_PATH)
    ApiResponse<SupplyDashboardRefreshRunView> triggerRefreshRun(
            @RequestBody(required = false) SupplyDashboardRefreshCommand command);

    @GetMapping(TRUST_PATH)
    ApiResponse<SupplyDashboardDataTrustView> trust();


    @GetMapping(FILTER_OPTIONS_PATH)
    ApiResponse<SupplyDashboardFilterOptionsView> filterOptions();



}
