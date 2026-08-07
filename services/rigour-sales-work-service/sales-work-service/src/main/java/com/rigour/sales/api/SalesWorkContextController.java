package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.SalesContextView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitTargetPageView;
import com.rigour.sales.application.service.SalesWorkContextService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 阶段 1 只读销售上下文和拜访目标 API。 */
@RestController
public final class SalesWorkContextController {

    private final SalesWorkContextService service;

    public SalesWorkContextController(SalesWorkContextService service) {
        this.service = service;
    }

    @GetMapping(SalesWorkApi.CONTEXT_PATH)
    public ApiResponse<SalesContextView> context() {
        return ApiResponse.success(service.context());
    }

    @GetMapping(SalesWorkApi.VISIT_TARGETS_PATH)
    public ApiResponse<VisitTargetPageView> visitTargets(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.visitTargets(query, page, pageSize));
    }
}
