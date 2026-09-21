package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderInvoiceApi;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceApplyCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceCompleteCommand;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoicePageView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceProfileView;
import com.rigour.order.api.v1.model.OrderInvoiceModels.OrderInvoiceView;
import com.rigour.order.application.service.sales.OrderInvoiceService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

/** 订单开票登记 HTTP 边界；只维护自研发票登记，不触发订货宝同步。 */
@RestController
public class OrderInvoiceController implements OrderInvoiceApi {
    private final OrderInvoiceService service;

    public OrderInvoiceController(OrderInvoiceService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<OrderInvoiceView> invoice(String orderNo) {
        return ApiResponse.success(service.view(orderNo));
    }

    @Override
    public ApiResponse<OrderInvoicePageView> page(
            int begin,
            int step,
            String status,
            String orderNo,
            String customerName,
            Instant appliedFrom,
            Instant appliedTo) {
        return ApiResponse.success(
                service.page(begin, step, status, orderNo, customerName, appliedFrom, appliedTo));
    }

    @Override
    public ApiResponse<List<OrderInvoiceProfileView>> profiles(String orderNo) {
        return ApiResponse.success(service.profilesByOrderNo(orderNo));
    }

    @Override
    public ApiResponse<OrderInvoiceView> apply(OrderInvoiceApplyCommand command) {
        return ApiResponse.success(service.apply(command));
    }

    @Override
    public ApiResponse<OrderInvoiceView> uploadAttachments(Long id, List<MultipartFile> files) {
        return ApiResponse.success(service.uploadAttachments(id, files));
    }

    @Override
    public ApiResponse<OrderInvoiceView> complete(Long id, OrderInvoiceCompleteCommand command) {
        return ApiResponse.success(service.complete(id, command));
    }

    @Override
    public ApiResponse<OrderInvoiceView> withdraw(Long id) {
        return ApiResponse.success(service.withdraw(id));
    }
}
