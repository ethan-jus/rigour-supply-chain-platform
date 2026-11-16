package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

public final class CustomerWorkbenchModels {
    private CustomerWorkbenchModels() {}
    public record PublicCount(long total) {}
    public record Summary(Long purchasing, Long newCooperation, Long returning, Long unknownHistory,
            Long contacts, Long wecom, Long mine, Long cooperated, Long publicPool,
            Long dueSoon, Long dormant, Long contactPending, String orderCoverage, Instant observedAt) {}
    public record Item(String id, String name, String address, String relation, Instant lastOrderAt,
                       long orderCount) {}
    public record Page(long total, List<Item> items, String nextCursor) {}
    public record ContactStore(String id,String name,String role) {}
    public record ContactItem(String id,String name,String role,String storeId,String storeName,Instant createdAt,List<ContactStore> stores) {}
    public record Contacts(long total,List<ContactItem> items,String nextCursor) {}
    public record OrderItem(String id,Instant effectiveAt,BigDecimal amount) {}
    public record Orders(long total,BigDecimal amount,List<OrderItem> items,String nextCursor) {}
}
