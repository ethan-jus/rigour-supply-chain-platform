package com.rigour.order.api;

import com.rigour.order.application.port.out.StoreOrderSubmissionStore;
import com.rigour.order.application.service.sales.StoreOrderSubmissionService;
import com.rigour.order.api.v1.model.*;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ApiResponse;
import java.time.Clock;
import org.springframework.web.bind.annotation.*;

@RestController
public class StoreOrderController {
    private final StoreOrderSubmissionService service;
    private final StoreOrderSubmissionStore store;
    private final Clock clock;
    public StoreOrderController(StoreOrderSubmissionService service,StoreOrderSubmissionStore store,Clock clock){this.service=service;this.store=store;this.clock=clock;}
    @PostMapping("/api/v1/orders/sales/{id}/store-submit")
    public ApiResponse<SalesOrderDetailView> submit(@PathVariable long id,@RequestBody StoreOrderSubmissionService.Command c){return ApiResponse.success(service.submit(id,c));}
    @PostMapping("/internal/v1/orders/store-facts/{storeId}")
    public ApiResponse<StoreOrderFacts> facts(@PathVariable long storeId,@RequestParam long ownershipId){
        var actor=AuthorizationContext.requireCurrent();
        if(!"SERVICE".equals(actor.principalScope())||actor.tenantId()==null)throw new AuthorizationDeniedException("order:store:source-read");
        AuthorizationContext.requirePermission("order:store:source-read");
        return ApiResponse.success(store.facts(actor.tenantId().toString(),Long.toString(storeId),Long.toString(ownershipId),clock.instant()));
    }
}
