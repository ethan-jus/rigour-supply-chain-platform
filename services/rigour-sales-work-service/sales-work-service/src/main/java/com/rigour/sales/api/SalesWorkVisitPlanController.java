package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.api.v1.SalesWorkManagementApi;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPlanListView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetPageView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.CancelVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitPlanPageView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.ManagementVisitPlanView;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.UpsertVisitPlanCommand;
import com.rigour.sales.api.v1.model.SalesWorkManagementApiModels.VisitPlanProfileOptionView;
import com.rigour.sales.application.service.SalesWorkVisitPlanService;
import com.rigour.shared.core.api.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 主管拜访计划下发与销售本人今日计划入口。 */
@RestController
public final class SalesWorkVisitPlanController {

    private final SalesWorkVisitPlanService service;

    public SalesWorkVisitPlanController(SalesWorkVisitPlanService service) {
        this.service = service;
    }

    @GetMapping(SalesWorkApi.VISIT_PLANS_PATH)
    public ApiResponse<VisitPlanListView> ownPlans(@RequestParam("date") LocalDate date) {
        return ApiResponse.success(service.ownPlans(date));
    }

    @GetMapping(SalesWorkManagementApi.VISIT_PLANS_PATH)
    public ApiResponse<ManagementVisitPlanPageView> managementPlans(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.managementPlans(from, to, status, page, pageSize));
    }

    @PostMapping(SalesWorkManagementApi.VISIT_PLANS_PATH)
    public ApiResponse<ManagementVisitPlanView> create(@RequestBody UpsertVisitPlanCommand command) {
        return ApiResponse.success(service.create(command));
    }

    @PutMapping(SalesWorkManagementApi.VISIT_PLAN_PATH)
    public ApiResponse<ManagementVisitPlanView> update(
            @PathVariable("planId") UUID planId,
            @RequestBody UpsertVisitPlanCommand command) {
        return ApiResponse.success(service.update(planId, command));
    }

    @PutMapping(SalesWorkManagementApi.CANCEL_VISIT_PLAN_PATH)
    public ApiResponse<ManagementVisitPlanView> cancel(
            @PathVariable("planId") UUID planId,
            @RequestBody CancelVisitPlanCommand command) {
        return ApiResponse.success(service.cancel(planId, command));
    }

    @GetMapping(SalesWorkManagementApi.VISIT_PLAN_PROFILES_PATH)
    public ApiResponse<List<VisitPlanProfileOptionView>> profiles() {
        return ApiResponse.success(service.profileOptions());
    }

    @GetMapping(SalesWorkManagementApi.VISIT_PLAN_TARGETS_PATH)
    public ApiResponse<VisitTargetPageView> targets(
            @PathVariable("salesProfileId") UUID salesProfileId,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.targetOptions(salesProfileId, query, page, pageSize));
    }
}
