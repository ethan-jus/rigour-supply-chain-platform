package com.rigour.erp.api.v1.model;

import java.time.LocalDate;

public record ErpCostQuery(
        int begin,
        int step,
        String documentNo,
        String title,
        String remark,
        String contractNo,
        String typeCode,
        String cityCode,
        String statusCode,
        String paymentStatusCode,
        LocalDate dateFrom,
        LocalDate dateTo,
        Long warehouseId,
        String ownerEmployeeCode) {}
