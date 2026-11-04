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
        String reason,
        LogisticsCostLedger ledger) {
    public LogisticsCostCommand(
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
        String reason) {
        this(requestId, revision, title, typeCode, cityCode, cityName, departmentId, ownerEmployeeCode, costDate, amount, paymentStatusCode, logistics, remark, reason, null);
    }
    public LogisticsCostCommand withLedger(LogisticsCostLedger value, BigDecimal total) {
        return new LogisticsCostCommand(requestId, revision, title, typeCode, cityCode, cityName, departmentId, ownerEmployeeCode, costDate, total, paymentStatusCode, logistics, remark, reason, value);
    }

}
