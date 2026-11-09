package com.rigour.order.api;

import com.rigour.order.application.service.sales.StoreOrderSubmissionService;
import com.rigour.order.api.v1.model.*;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
public class StoreOrderController {
    private final StoreOrderSubmissionService service;
    public StoreOrderController(StoreOrderSubmissionService service){this.service=service;}
    @PostMapping("/api/v1/orders/sales/{id}/store-submit")
    public ApiResponse<SalesOrderDetailView> submit(@PathVariable long id,@RequestBody StoreOrderSubmissionService.Command c){return ApiResponse.success(service.submit(id,c));}
}
