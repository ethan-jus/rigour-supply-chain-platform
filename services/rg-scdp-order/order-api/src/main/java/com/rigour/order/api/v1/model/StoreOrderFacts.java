package com.rigour.order.api.v1.model;

import java.time.Instant;
import java.util.List;

/** Order 权威快照。未完成受理也必须返回，CRM 不把服务故障当成无订单。 */
public record StoreOrderFacts(String tenantId, String storeId, String ownershipId,
        Instant checkedAt, String ruleVersion, List<Fact> orders, boolean pendingAuthorization,
        Instant firstStoreOrderAt, Instant lastStoreOrderAt) {
    public record Fact(String orderId, String ownershipId, String salespersonId,
            Instant effectiveAt, long revision) {}
    public StoreOrderFacts { orders = List.copyOf(orders); }
}
