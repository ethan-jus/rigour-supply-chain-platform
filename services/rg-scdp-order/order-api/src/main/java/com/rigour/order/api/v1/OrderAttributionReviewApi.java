package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.OrderAttributionReview.*;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

/** 历史归属复核资源，普通订单编辑和来源同步均不能代替审批。 */
@RequestMapping("/api/v1/orders/sales/{id}/attribution-reviews")
public interface OrderAttributionReviewApi {
    @GetMapping
    ApiResponse<Context> context(@PathVariable Long id);

    @PostMapping
    ApiResponse<Adjustment> propose(@PathVariable Long id, @RequestBody Propose command);

    @PostMapping("/{reviewId}/decision")
    ApiResponse<Adjustment> decide(
            @PathVariable Long id, @PathVariable String reviewId, @RequestBody Review command);
}
