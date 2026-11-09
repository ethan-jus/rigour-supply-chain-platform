package com.rigour.merchant.api;

import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Evidence;
import com.rigour.merchant.api.v1.model.StoreOrderResult;
import com.rigour.merchant.application.service.StoreFactService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/crm/store-facts")
public class StoreFactController {
    private final StoreFactService service;
    public StoreFactController(StoreFactService service) { this.service=service; }
    @PostMapping("/visits") public ApiResponse<Void> visit(@RequestBody Evidence e) { service.evidence(e); return ApiResponse.success(null); }
    @PostMapping("/orders") public ApiResponse<Void> order(@RequestBody StoreOrderResult r) { service.order(r); return ApiResponse.success(null); }
}
