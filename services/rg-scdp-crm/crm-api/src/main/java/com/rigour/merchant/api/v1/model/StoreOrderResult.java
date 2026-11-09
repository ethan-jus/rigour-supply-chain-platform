package com.rigour.merchant.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Order-owned immutable snapshot, delivered at least once and applied by source sequence. */
public record StoreOrderResult(String tenantId,String orderId,String storeId,String ownershipId,
        String salespersonId,String requestId,long sequence,String submissionStatus,String orderStatus,
        boolean deleted,BigDecimal payableAmount,Instant effectiveAt,Instant authorizationExpiresAt,
        Instant observedAt) {}
