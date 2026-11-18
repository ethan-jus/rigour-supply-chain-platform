package com.rigour.merchant.api;
import com.rigour.merchant.api.v1.model.StoreVisitVerificationModels.*;
import com.rigour.merchant.application.service.StoreVisitVerificationService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class StoreVisitVerificationController {
    private final StoreVisitVerificationService service;
    public StoreVisitVerificationController(StoreVisitVerificationService service){this.service=service;}
    @GetMapping("/api/v1/crm/stores/{store}/visits/{visit}/verification")
    public ApiResponse<Verification> read(@PathVariable String store,@PathVariable String visit){return ApiResponse.success(service.read(store,visit));}
    @PostMapping("/api/v1/crm/stores/{store}/visits/{visit}/appeals")
    public ApiResponse<Verification> appeal(@PathVariable String store,@PathVariable String visit,@RequestBody AppealCommand c){return ApiResponse.success(service.appeal(store,visit,c));}
    @GetMapping("/api/v1/crm/visit-appeals")
    public ApiResponse<Queue> queue(@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize){return ApiResponse.success(service.queue(status,page,pageSize));}
}
