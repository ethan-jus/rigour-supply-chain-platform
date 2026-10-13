package com.rigour.order.api.v1.model;

import java.time.LocalDate;

public record LogisticsCostQuery(
        int begin,
        int step,
        String keyword,
        String typeCode,
        String cityCode,
        String statusCode,
        String paymentStatusCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        Long warehouseId,
        String ownerEmployeeCode) {}
