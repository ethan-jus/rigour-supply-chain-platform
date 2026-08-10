package com.rigour.sales.api;

import com.rigour.sales.api.v1.SalesWorkApi;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CheckOutVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.CreateVisitCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.NearbyStorePageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitPageView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitActivitySummaryView;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitResultCommand;
import com.rigour.sales.api.v1.model.SalesWorkApiModels.VisitView;
import com.rigour.sales.application.service.SalesWorkVisitService;
import com.rigour.shared.core.api.ApiResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 附近门店和拜访执行 API。 */
@RestController
public final class SalesWorkVisitController {

    private final SalesWorkVisitService service;

    public SalesWorkVisitController(SalesWorkVisitService service) {
        this.service = service;
    }

    @GetMapping(SalesWorkApi.NEARBY_STORES_PATH)
    public ApiResponse<NearbyStorePageView> nearbyStores(
            @RequestParam("longitude") BigDecimal longitude,
            @RequestParam("latitude") BigDecimal latitude,
            @RequestParam(name = "radiusMeters", required = false) Integer radiusMeters,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.nearbyStores(longitude, latitude, radiusMeters, query, page, pageSize));
    }

    @PostMapping(SalesWorkApi.VISITS_PATH)
    public ApiResponse<VisitView> createVisit(@RequestBody CreateVisitCommand command) {
        return ApiResponse.success(service.createVisit(command));
    }

    @GetMapping(SalesWorkApi.VISITS_PATH)
    public ApiResponse<VisitPageView> visits(
            @RequestParam(name = "date", required = false) LocalDate date,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "pageSize", defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.visits(date, page, pageSize));
    }

    @GetMapping(SalesWorkApi.ACTIVITY_SUMMARY_PATH)
    public ApiResponse<VisitActivitySummaryView> activitySummary(
            @RequestParam("from") LocalDate from,
            @RequestParam("to") LocalDate to) {
        return ApiResponse.success(service.activitySummary(from, to));
    }

    @GetMapping(SalesWorkApi.VISIT_PATH)
    public ApiResponse<VisitView> visit(@PathVariable("visitId") UUID visitId) {
        return ApiResponse.success(service.visit(visitId));
    }

    @PostMapping(SalesWorkApi.VISIT_CHECK_OUT_PATH)
    public ApiResponse<VisitView> checkOutVisit(@PathVariable("visitId") UUID visitId,
                                                @RequestBody CheckOutVisitCommand command) {
        return ApiResponse.success(service.checkOutVisit(visitId, command));
    }

    @PutMapping(SalesWorkApi.VISIT_RESULT_PATH)
    public ApiResponse<VisitView> submitVisitResult(@PathVariable("visitId") UUID visitId,
                                                    @RequestBody VisitResultCommand command) {
        return ApiResponse.success(service.submitVisitResult(visitId, command));
    }
}
