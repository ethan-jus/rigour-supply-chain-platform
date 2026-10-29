package com.rigour.merchant.application.port.out;

import com.rigour.order.api.v1.model.StoreOrderFacts;

public interface StoreOrderClient {
    StoreOrderFacts facts(String tenant, String storeId, String ownershipId);
}
