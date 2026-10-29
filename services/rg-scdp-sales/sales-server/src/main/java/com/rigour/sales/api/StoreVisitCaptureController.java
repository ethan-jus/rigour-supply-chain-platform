package com.rigour.sales.api;
import com.rigour.sales.application.service.StoreVisitCaptureService;
import com.rigour.sales.application.port.out.StoreVisitCaptureStore;
import com.rigour.merchant.api.v1.model.StoreVisitCaptureModels.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
public class StoreVisitCaptureController {
    private final StoreVisitCaptureService service;private final StoreVisitCaptureStore store;
    public StoreVisitCaptureController(StoreVisitCaptureService service,StoreVisitCaptureStore store){this.service=service;this.store=store;}
    @PostMapping("/api/v1/sales/work/store-visits") public ApiResponse<StoreVisitCaptureService.Created> create(@RequestBody StoreVisitCaptureService.Command c){return ApiResponse.success(service.create(c));}
    @GetMapping("/internal/v1/sales/crm-visits/policy") public ApiResponse<Policy> policy(){return ApiResponse.success(service.policy());}
    @GetMapping("/internal/v1/sales/crm-visits/facts/{storeId}") public ApiResponse<Facts> facts(@PathVariable long storeId,@RequestParam long ownershipId){var actor=AuthorizationContext.requireCurrent();if(!"SERVICE".equals(actor.principalScope())||actor.tenantId()==null)throw new AuthorizationDeniedException("sales:store-visit:source-read");AuthorizationContext.requirePermission("sales:store-visit:source-read");return ApiResponse.success(store.facts(actor.tenantId(),Long.toString(storeId),Long.toString(ownershipId)));}
}
