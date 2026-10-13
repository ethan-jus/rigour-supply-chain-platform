package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LogisticsCostCommand(
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
        LogisticsCostTransportDetails logistics,
        String remark,
        String reason) {}
