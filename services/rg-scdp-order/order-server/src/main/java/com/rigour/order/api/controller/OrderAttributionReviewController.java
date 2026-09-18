package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderAttributionReviewApi;
import com.rigour.order.api.v1.model.OrderAttributionReview.*;
import com.rigour.order.application.port.out.OrderAttributionReviewStore;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** 归属复核 HTTP 入口，授权和版本检查统一由本域事务执行。 */
@RestController
public final class OrderAttributionReviewController implements OrderAttributionReviewApi {
    private final OrderAttributionReviewStore store;

    public OrderAttributionReviewController(OrderAttributionReviewStore store) {
        this.store = store;
    }

    public ApiResponse<Context> context(Long id) {
        return ApiResponse.success(store.context(id));
    }

    public ApiResponse<Adjustment> propose(Long id, Propose c) {
        return ApiResponse.success(store.propose(id, c));
    }

    public ApiResponse<Adjustment> decide(Long id, String reviewId, Review c) {
        return ApiResponse.success(store.decide(id, reviewId, c));
    }
}
