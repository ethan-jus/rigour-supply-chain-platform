package com.rigour.order.api;

import com.rigour.order.api.v1.model.SalesShipmentSnapshotCommand;
import com.rigour.order.application.service.sales.SalesShipmentSnapshotService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
public class SalesShipmentSnapshotController {
    private final SalesShipmentSnapshotService service;

    public SalesShipmentSnapshotController(SalesShipmentSnapshotService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/orders/sales-shipment-source-snapshots")
    public ApiResponse<Long> save(@RequestBody SalesShipmentSnapshotCommand command) {
        return ApiResponse.success(service.save(command));
    }
}
