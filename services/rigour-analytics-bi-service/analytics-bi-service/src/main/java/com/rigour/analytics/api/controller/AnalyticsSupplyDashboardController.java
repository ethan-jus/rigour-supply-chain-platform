package com.rigour.analytics.api.controller;

import com.rigour.analytics.api.v1.AnalyticsSupplyDashboardApi;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostImportResultView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataTrustView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveCommand;
import com.rigour.analytics.api.v1.model.SupplyDashboardFeishuArchiveView;
import com.rigour.analytics.api.v1.model.SupplyDashboardFilterOptionsView;
import com.rigour.analytics.api.v1.model.SupplyDashboardOverviewView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRefreshRunView;
import com.rigour.analytics.api.v1.model.SupplyDashboardReconciliationView;
import com.rigour.analytics.application.service.SupplyDashboardCityCostImportService;
import com.rigour.analytics.application.service.SupplyDashboardGovernanceService;
import com.rigour.analytics.application.service.SupplyDashboardQueryService;
import com.rigour.analytics.application.service.SupplyDashboardRefreshService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

/** 供应链 BI 看板 HTTP 边界。 */
@RestController
public final class AnalyticsSupplyDashboardController implements AnalyticsSupplyDashboardApi {
    private final SupplyDashboardQueryService queryService;
    private final SupplyDashboardRefreshService refreshService;
    private final SupplyDashboardCityCostImportService cityCostImportService;
    private final SupplyDashboardGovernanceService governanceService;

    public AnalyticsSupplyDashboardController(
            SupplyDashboardQueryService queryService,
            SupplyDashboardRefreshService refreshService,
            SupplyDashboardCityCostImportService cityCostImportService,
            SupplyDashboardGovernanceService governanceService) {
        this.queryService = queryService;
        this.refreshService = refreshService;
        this.cityCostImportService = cityCostImportService;
        this.governanceService = governanceService;
    }

    @Override
    public ApiResponse<SupplyDashboardOverviewView> overview(
            Instant from, Instant to, String regionCode, String ownerStaffCode,
            String customerTypeCode, Long productCategoryId, String sourceSystemCode) {
        return ApiResponse.success(queryService.overview(
                from, to, regionCode, ownerStaffCode, customerTypeCode, productCategoryId, sourceSystemCode));
    }

    @Override
    public ApiResponse<SupplyDashboardRefreshRunView> triggerRefreshRun() {
        return ApiResponse.success(refreshService.refreshCurrentTenant());
    }

    @Override
    public ApiResponse<SupplyDashboardDataTrustView> trust() {
        return ApiResponse.success(governanceService.trust());
    }

    @Override
    public ApiResponse<SupplyDashboardReconciliationView> reconciliation(
            Instant from, Instant to, String regionCode, String ownerStaffCode,
            String customerTypeCode, Long productCategoryId, String sourceSystemCode) {
        return ApiResponse.success(governanceService.reconciliation(
                from, to, regionCode, ownerStaffCode, customerTypeCode, productCategoryId, sourceSystemCode));
    }

    @Override
    public ApiResponse<SupplyDashboardFilterOptionsView> filterOptions() {
        return ApiResponse.success(governanceService.filterOptions());
    }

    @Override
    public ApiResponse<SupplyDashboardCityCostImportResultView> importCityCosts(
            SupplyDashboardCityCostImportCommand command) {
        return ApiResponse.success(cityCostImportService.importRecords(command));
    }

    @Override
    public ApiResponse<List<SupplyDashboardFeishuArchiveView>> feishuArchives() {
        return ApiResponse.success(governanceService.feishuArchives());
    }

    @Override
    public ApiResponse<SupplyDashboardFeishuArchiveView> registerFeishuArchive(
            SupplyDashboardFeishuArchiveCommand command) {
        return ApiResponse.success(governanceService.registerFeishuArchive(command));
    }
}
