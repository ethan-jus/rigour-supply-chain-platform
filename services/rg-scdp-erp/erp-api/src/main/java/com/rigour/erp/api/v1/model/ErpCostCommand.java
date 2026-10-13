package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ErpCostCommand(
        String requestId,
        Integer revision,
        String title,
        String typeCode,
        String cityCode,
        String cityName,
        Long departmentId,
        String ownerEmployeeCode,
        LocalDate costDate,
        BigDecimal amount,
        String paymentStatusCode,
        ErpCostRentalDetails rental,
        String remark,
        String reason) {}
