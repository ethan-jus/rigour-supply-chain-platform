package com.rigour.erp.api.v1.model;

import java.time.Instant;
import java.util.List;

public record ErpCostDetailView(
        String id,
        String documentNo,
        String categoryCode,
        String statusCode,
        int revision,
        ErpCostCommand data,
        List<ErpCostSourceView> sources,
        String warehouseName,
        String ownerEmployeeName,
        Instant createdTime,
        Instant updatedTime) {}
