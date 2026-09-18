package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.HistorySyncModels.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

/** 历史门店对账及组核销；写操作均要求版本和业务证据。 */
public interface OrderHistorySyncApi {
    String BASE = "/api/v1/orders/history-sync";

    @GetMapping(BASE)
    ApiResponse<StoreView> overview(@RequestParam(required = false) Long customerId);

    @PostMapping(BASE + "/source-orders")
    ApiResponse<Intake> sourceOrder(@RequestBody SourceOrder command);

    @PostMapping(BASE + "/new-order")
    ApiResponse<Void> confirmNew(@RequestBody NewOrder command);

    @PostMapping(BASE + "/groups")
    ApiResponse<String> bind(@RequestBody Bind command);

    @PostMapping(BASE + "/receipts")
    ApiResponse<Intake> receipt(@RequestBody Receipt command);

    @PostMapping(BASE + "/allocations")
    ApiResponse<Void> allocate(@RequestBody Allocate command);

    @PostMapping(BASE + "/product-allocations")
    ApiResponse<Void> allocateProducts(@RequestBody AllocateProducts command);

    @GetMapping(BASE + "/performance")
    ApiResponse<Performance> performance(@RequestParam String month);

    @PostMapping(BASE + "/receipt-owner")
    ApiResponse<Void> confirmOwner(@RequestBody OwnerReview command);
}
