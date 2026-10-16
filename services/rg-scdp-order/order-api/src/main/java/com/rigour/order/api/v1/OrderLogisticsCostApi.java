package com.rigour.order.api.v1;

import com.rigour.order.api.v1.model.LogisticsCostActionCommand;
import com.rigour.order.api.v1.model.LogisticsCostAuditView;
import com.rigour.order.api.v1.model.LogisticsCostCommand;
import com.rigour.order.api.v1.model.LogisticsCostDetailView;
import com.rigour.order.api.v1.model.LogisticsCostPageView;
import com.rigour.order.api.v1.model.LogisticsCostQuery;
import com.rigour.order.api.v1.model.LogisticsCostSourceView;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface OrderLogisticsCostApi {
    String BASE_PATH = "/api/v1/orders/logistics-costs";

    @GetMapping(BASE_PATH)
    ApiResponse<LogisticsCostPageView> entries(@ModelAttribute LogisticsCostQuery query);

    @GetMapping(BASE_PATH + "/export")
    ApiResponse<LogisticsCostPageView> export(@ModelAttribute LogisticsCostQuery query);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<LogisticsCostDetailView> entry(@PathVariable String id);

    @PostMapping(BASE_PATH)
    ApiResponse<LogisticsCostDetailView> create(@RequestBody LogisticsCostCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<LogisticsCostDetailView> update(
            @PathVariable String id, @RequestBody LogisticsCostCommand command);

    @PostMapping(BASE_PATH + "/{id}/confirm")
    ApiResponse<LogisticsCostDetailView> confirm(
            @PathVariable String id, @RequestBody LogisticsCostActionCommand action);

    @PostMapping(BASE_PATH + "/{id}/void")
    ApiResponse<LogisticsCostDetailView> voidEntry(
            @PathVariable String id, @RequestBody LogisticsCostActionCommand action);

    @GetMapping(BASE_PATH + "/{id}/history")
    ApiResponse<List<LogisticsCostAuditView>> history(@PathVariable String id);

    @GetMapping(BASE_PATH + "/sources")
    ApiResponse<List<LogisticsCostSourceView>> sources(
            @RequestParam String legCode,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(defaultValue = "0") int begin);
}
