package com.rigour.erp.api;
import com.rigour.erp.api.v1.model.ProcurementCostSource;
import com.rigour.erp.application.service.cost.ProcurementCostSourceService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public final class ProcurementCostSourceController {
    private final ProcurementCostSourceService service;
    public ProcurementCostSourceController(ProcurementCostSourceService service){this.service=service;}
    @GetMapping("/api/v1/erp/internal/cost-sources/procurements/{code}")
    public ApiResponse<ProcurementCostSource> source(@PathVariable String code){return ApiResponse.success(service.source(code));}
}
