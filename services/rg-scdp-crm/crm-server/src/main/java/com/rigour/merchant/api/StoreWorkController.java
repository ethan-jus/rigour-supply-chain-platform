package com.rigour.merchant.api;
import com.rigour.merchant.api.v1.model.StoreWorkModels.*;
import com.rigour.merchant.application.service.StoreWorkService;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/crm/stores/{store}")
public class StoreWorkController {
    private final StoreWorkService service;
    public StoreWorkController(StoreWorkService service){this.service=service;}
    @GetMapping("/visits/{visit}/capture") public ApiResponse<com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.Context> capture(@PathVariable String store,@PathVariable String visit){return ApiResponse.success(service.capture(store,visit));}
    @GetMapping("/visits/{visit}/evidence-access") public ApiResponse<String> evidenceAccess(@PathVariable String store,@PathVariable String visit){return ApiResponse.success(service.evidenceAccess(store,visit));}
    @PostMapping("/visits") public ApiResponse<Visit> checkIn(@PathVariable String store,@RequestBody CheckIn c){return ApiResponse.success(service.checkIn(store,c));}
    @PostMapping("/visits/{visit}/submit") public ApiResponse<Visit> submit(@PathVariable String store,@PathVariable String visit,@RequestBody SubmitVisit c){return ApiResponse.success(service.submit(store,visit,c));}
    @PostMapping("/visits/{visit}/cancel") public ApiResponse<Visit> cancel(@PathVariable String store,@PathVariable String visit,@RequestBody CancelVisit c){return ApiResponse.success(service.cancel(store,visit,c));}
    @PostMapping("/visits/{visit}/review") public ApiResponse<Visit> review(@PathVariable String store,@PathVariable String visit,@RequestBody ReviewVisit c){return ApiResponse.success(service.review(store,visit,c));}
    @GetMapping("/visits") public ApiResponse<List<Visit>> visits(@PathVariable String store,@RequestParam(defaultValue="MINE") String view){return ApiResponse.success(service.visits(store,view));}
    @PostMapping("/order-authorization") public ApiResponse<OrderPermit> authorize(@PathVariable String store,@RequestBody OrderAuthorization c){return ApiResponse.success(service.authorize(store,c));}
    @PostMapping("/refresh-protection") public ApiResponse<RefreshResult> refresh(@PathVariable String store){return ApiResponse.success(service.refresh(store));}
}
