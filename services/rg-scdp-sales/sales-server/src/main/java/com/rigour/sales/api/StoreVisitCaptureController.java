package com.rigour.sales.api;
import com.rigour.sales.application.service.StoreVisitCaptureService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public class StoreVisitCaptureController {
    private final StoreVisitCaptureService service;
    public StoreVisitCaptureController(StoreVisitCaptureService service){this.service=service;}
    @PostMapping("/api/v1/sales/work/store-visits") public ApiResponse<StoreVisitCaptureService.Created> create(@RequestBody StoreVisitCaptureService.Command c){return ApiResponse.success(service.create(c));}
}
