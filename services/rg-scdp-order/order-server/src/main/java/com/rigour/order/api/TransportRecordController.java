package com.rigour.order.api;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportRecordModels.*;
import com.rigour.order.application.service.cost.TransportRecordService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/orders/transport-records")
public class TransportRecordController {
    private final TransportRecordService service;

    public TransportRecordController(TransportRecordService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Page> page(@ModelAttribute LogisticsCostQuery query) {
        return ApiResponse.success(service.page(query, false));
    }

    @GetMapping("/export")
    public ApiResponse<Page> export(@ModelAttribute LogisticsCostQuery query) {
        return ApiResponse.success(service.page(query, true));
    }

    @PostMapping("/export-selected")
    public ApiResponse<List<Detail>> selected(@RequestBody List<String> ids) {
        return ApiResponse.success(service.selected(ids));
    }

    @GetMapping("/{id}")
    public ApiResponse<Detail> detail(@PathVariable String id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Detail> create(@RequestBody Draft draft) {
        return ApiResponse.success(service.save(null, draft));
    }

    @PutMapping("/{id}")
    public ApiResponse<Detail> save(@PathVariable String id, @RequestBody Draft draft) {
        return ApiResponse.success(service.save(id, draft));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Detail> confirm(
            @PathVariable String id, @RequestBody LogisticsCostActionCommand action) {
        return ApiResponse.success(service.confirm(id, action));
    }

    @GetMapping("/{id}/vouchers/{voucherId}")
    public ApiResponse<Map<String, String>> voucher(
            @PathVariable String id,
            @PathVariable String voucherId,
            @RequestParam(defaultValue = "false") boolean download) {
        return ApiResponse.success(Map.of("url", service.voucher(id, voucherId, download)));
    }
}
