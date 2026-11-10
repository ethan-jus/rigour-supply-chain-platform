package com.rigour.integration.application.port.out;

import com.rigour.order.api.v1.model.LogisticsImportModels.CarrierOrder;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public interface HuolalaVouchers {
    void enqueue(UUID tenant, String connector, CarrierOrder order, JsonNode detail);
}
