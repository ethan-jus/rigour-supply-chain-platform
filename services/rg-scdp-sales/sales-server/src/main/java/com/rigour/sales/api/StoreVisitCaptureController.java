package com.rigour.sales.api;
import com.rigour.sales.application.service.StoreVisitCaptureService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public class StoreVisitCaptureController {
    private final StoreVisitCaptureService service;
    public StoreVisitCaptureController(StoreVisitCaptureService service){this.service=service;}
    @GetMapping("/api/v1/sales/me/visits/{capture}/fieldwork") public ApiResponse<com.rigour.merchant.api.v1.model.StoreVisitFieldworkModels.Accepted> fieldwork(@PathVariable java.util.UUID capture){return ApiResponse.success(service.readFieldwork(capture));}
    @PostMapping("/api/v1/sales/work/store-visits") public ApiResponse<StoreVisitCaptureService.Created> create(@RequestBody StoreVisitCaptureService.Command c){return ApiResponse.success(service.create(c));}
}
