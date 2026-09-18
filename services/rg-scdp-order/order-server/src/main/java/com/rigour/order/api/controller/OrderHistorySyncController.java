package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderHistorySyncApi;
import com.rigour.order.api.v1.model.HistorySyncModels.*;
import com.rigour.order.application.service.sales.OrderHistorySyncService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

/** 门店对账、组关联和回款归属入口。 */
@RestController
public class OrderHistorySyncController implements OrderHistorySyncApi {
    private final OrderHistorySyncService service;

    public OrderHistorySyncController(OrderHistorySyncService service) {
        this.service = service;
    }

    public ApiResponse<StoreView> overview(Long id) {
        return ApiResponse.success(service.overview(id));
    }

    public ApiResponse<Intake> sourceOrder(SourceOrder c) {
        return ApiResponse.success(service.sourceOrder(c));
    }

    public ApiResponse<Void> confirmNew(NewOrder c) {
        service.confirmNew(c);
        return ApiResponse.success(null);
    }

    public ApiResponse<String> bind(Bind c) {
        return ApiResponse.success(service.bind(c));
    }

    public ApiResponse<Intake> receipt(Receipt c) {
        return ApiResponse.success(service.receipt(c));
    }

    public ApiResponse<Void> allocate(Allocate c) {
        service.allocate(c);
        return ApiResponse.success(null);
    }

    public ApiResponse<Void> allocateProducts(AllocateProducts c) {
        service.allocateProducts(c);
        return ApiResponse.success(null);
    }

    public ApiResponse<Performance> performance(String month) {
        return ApiResponse.success(service.performance(month));
    }

    public ApiResponse<Void> confirmOwner(OwnerReview c) {
        service.confirmOwner(c);
        return ApiResponse.success(null);
    }
}
