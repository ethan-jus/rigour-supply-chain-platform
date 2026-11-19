package com.rigour.order.api;

import com.rigour.order.api.v1.model.ShipmentTransportModels.*;
import com.rigour.order.application.service.cost.ShipmentTransportService;
import com.rigour.shared.core.api.ApiResponse;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders/shipment-transports")
public class ShipmentTransportController {
    private final ShipmentTransportService service;

    public ShipmentTransportController(ShipmentTransportService service) {
        this.service = service;
    }

    @PostMapping("/carrier-backfill")
    public ApiResponse<Integer> backfillCarrier(@RequestBody CarrierBackfill command) {
        return ApiResponse.success(service.backfillCarrier(command));
    }

    @PostMapping
    public ApiResponse<Source> sync(@RequestBody Command command) {
        return ApiResponse.success(service.sync(command));
    }
}
