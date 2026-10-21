package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.ErpCostActionCommand;
import com.rigour.erp.api.v1.model.ErpCostAuditView;
import com.rigour.erp.api.v1.model.ErpCostCommand;
import com.rigour.erp.api.v1.model.ErpCostDetailView;
import com.rigour.erp.api.v1.model.ErpCostEmployeeView;
import com.rigour.erp.api.v1.model.ErpCostOptionView;
import com.rigour.erp.api.v1.model.ErpCostPageView;
import com.rigour.erp.api.v1.model.ErpCostQuery;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ErpCostApi {
    String BASE_PATH = "/api/v1/erp/costs";

    @GetMapping(BASE_PATH + "/{category}")
    ApiResponse<ErpCostPageView> entries(
            @PathVariable String category, @ModelAttribute ErpCostQuery query);

    @GetMapping(BASE_PATH + "/{category}/export")
    ApiResponse<ErpCostPageView> export(
            @PathVariable String category, @ModelAttribute ErpCostQuery query);

    @GetMapping(BASE_PATH + "/{category}/{id}")
    ApiResponse<ErpCostDetailView> entry(@PathVariable String category, @PathVariable String id);

    @PostMapping(BASE_PATH + "/{category}")
    ApiResponse<ErpCostDetailView> create(
            @PathVariable String category, @RequestBody ErpCostCommand command);

    @PutMapping(BASE_PATH + "/{category}/{id}")
    ApiResponse<ErpCostDetailView> update(
            @PathVariable String category,
            @PathVariable String id,
            @RequestBody ErpCostCommand command);

    @PostMapping(BASE_PATH + "/{category}/{id}/confirm")
    ApiResponse<ErpCostDetailView> confirm(
            @PathVariable String category,
            @PathVariable String id,
            @RequestBody ErpCostActionCommand action);

    @PostMapping(BASE_PATH + "/{category}/{id}/void")
    ApiResponse<ErpCostDetailView> voidEntry(
            @PathVariable String category,
            @PathVariable String id,
            @RequestBody ErpCostActionCommand action);

    @GetMapping(BASE_PATH + "/{category}/{id}/history")
    ApiResponse<List<ErpCostAuditView>> history(
            @PathVariable String category, @PathVariable String id);

    @GetMapping(BASE_PATH + "/{category}/warehouses")
    ApiResponse<List<ErpCostOptionView>> warehouses(
            @PathVariable String category, @RequestParam(defaultValue = "") String keyword);

    @GetMapping(BASE_PATH + "/{category}/references/{kind}")
    ApiResponse<List<com.rigour.erp.api.v1.model.ErpCostReferenceView>> references(
            @PathVariable String category,
            @PathVariable String kind,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Long parentId,
            @RequestParam(defaultValue = "0") int begin);

    @GetMapping(BASE_PATH + "/{category}/purchase-orders")
    ApiResponse<List<com.rigour.erp.api.v1.model.ErpCostSourceView>> purchaseOrders(
            @PathVariable String category,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(defaultValue = "0") int begin);

    @GetMapping(BASE_PATH + "/marketing/employees")
    ApiResponse<List<ErpCostEmployeeView>> employees();
}
