package com.rigour.order.api;
import com.rigour.order.application.service.cost.ProcurementFreightSyncService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public final class ProcurementFreightSyncController {
    private final ProcurementFreightSyncService service;
    public ProcurementFreightSyncController(ProcurementFreightSyncService service){this.service=service;}
    @PostMapping("/api/v1/orders/internal/logistics-costs/procurements/{code}")
    public ApiResponse<String> sync(@PathVariable String code){return ApiResponse.success(service.sync(code));}
}
