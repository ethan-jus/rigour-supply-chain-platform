package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderSalesOrderProductRepairApi;
import com.rigour.order.api.v1.model.SalesOrderProductRepair.*;
import com.rigour.order.application.service.sales.OrderProductRepairService;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RestController;

/** 历史商品修复 HTTP 边界，所有授权与复核在 Order 用例执行。 */
@RestController
public final class OrderSalesOrderProductRepairController implements OrderSalesOrderProductRepairApi {
    private final OrderProductRepairService service;
    public OrderSalesOrderProductRepairController(OrderProductRepairService service) { this.service = service; }
    @Override public ApiResponse<EvidencePage> evidence(long afterLineId, int limit) {
        return ApiResponse.success(service.evidence(afterLineId, limit));
    }
    @Override public ApiResponse<Context> context(Long id) { return ApiResponse.success(service.context(id)); }
    @Override public ApiResponse<Preview> preview(Long id, PreviewCommand command) {
        return ApiResponse.success(service.preview(id, command));
    }
    @Override public ApiResponse<Applied> apply(Long id, String previewId, ApplyCommand command) {
        return ApiResponse.success(service.apply(id, previewId, command));
    }
}
