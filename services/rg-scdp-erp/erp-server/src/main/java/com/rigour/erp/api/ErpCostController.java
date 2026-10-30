package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpCostApi;
import com.rigour.erp.api.v1.model.ErpCostActionCommand;
import com.rigour.erp.api.v1.model.ErpCostAuditView;
import com.rigour.erp.api.v1.model.ErpCostCommand;
import com.rigour.erp.api.v1.model.ErpCostDetailView;
import com.rigour.erp.api.v1.model.ErpCostEmployeeView;
import com.rigour.erp.api.v1.model.ErpCostOptionView;
import com.rigour.erp.api.v1.model.ErpCostPageView;
import com.rigour.erp.api.v1.model.ErpCostQuery;
import com.rigour.erp.application.service.cost.ErpCostService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public final class ErpCostController implements ErpCostApi {
    private final ErpCostService service;

    public ErpCostController(ErpCostService service) {
        this.service = service;
    }

    @Override
    public ApiResponse<ErpCostPageView> entries(String category, ErpCostQuery query) {
        return ApiResponse.success(service.entries(category, query));
    }

    @Override
    public ApiResponse<ErpCostPageView> export(String category, ErpCostQuery query) {
        return ApiResponse.success(service.export(category, query));
    }

    @Override
    public ApiResponse<ErpCostDetailView> entry(String category, String id) {
        return ApiResponse.success(service.entry(category, id));
    }

    @Override
    public ApiResponse<ErpCostDetailView> create(String category, ErpCostCommand command) {
        return ApiResponse.success(service.save(category, null, command));
    }

    @Override
    public ApiResponse<ErpCostDetailView> update(
            String category, String id, ErpCostCommand command) {
        return ApiResponse.success(service.save(category, id, command));
    }

    @Override
    public ApiResponse<ErpCostDetailView> confirm(
            String category, String id, ErpCostActionCommand action) {
        return ApiResponse.success(service.action(category, id, action, true));
    }

    @Override
    public ApiResponse<ErpCostDetailView> voidEntry(
            String category, String id, ErpCostActionCommand action) {
        return ApiResponse.success(service.action(category, id, action, false));
    }

    @Override
    public ApiResponse<List<ErpCostAuditView>> history(String category, String id) {
        return ApiResponse.success(service.history(category, id));
    }

    @Override
    public ApiResponse<List<ErpCostOptionView>> warehouses(String category, String keyword) {
        return ApiResponse.success(service.warehouses(category, keyword));
    }

    @Override
    public ApiResponse<List<com.rigour.erp.api.v1.model.ErpCostReferenceView>> references(
            String category, String kind, String keyword, Long parentId, int begin) {
        return ApiResponse.success(service.references(category, kind, keyword, parentId, begin));
    }

    @Override
    public ApiResponse<List<com.rigour.erp.api.v1.model.ErpCostSourceView>> purchaseOrders(
            String category, String keyword, String cityCode, int begin) {
        return ApiResponse.success(service.purchaseOrders(category, keyword, cityCode, begin));
    }

    @Override
    public ApiResponse<List<ErpCostEmployeeView>> employees(String category) {
        return ApiResponse.success(service.employees(category));
    }
}
