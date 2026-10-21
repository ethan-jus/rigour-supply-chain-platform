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
        Instant updatedTime,
        String createdBy,
        String updatedBy) {
    public LogisticsCostDetailView(
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
            Instant updatedTime) {
        this(
                id,
                documentNo,
                categoryCode,
                statusCode,
                revision,
                data,
                sources,
                warehouseName,
                ownerEmployeeName,
                createdTime,
                updatedTime,
                null,
                null);
    }
}
