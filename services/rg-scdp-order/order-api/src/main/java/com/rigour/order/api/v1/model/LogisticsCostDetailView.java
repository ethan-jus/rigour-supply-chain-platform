package com.rigour.order.api.v1.model;

import java.time.Instant;
import java.util.List;

public record LogisticsCostDetailView(
        String id,
        String documentNo,
        String categoryCode,
        String statusCode,
        int revision,
        LogisticsCostCommand data,
        List<LogisticsCostSourceView> sources,
        String warehouseName,
        String ownerEmployeeName,
        Instant createdTime,
        Instant updatedTime) {}
