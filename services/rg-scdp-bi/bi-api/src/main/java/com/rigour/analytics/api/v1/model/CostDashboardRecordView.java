package com.rigour.analytics.api.v1.model;

import java.time.LocalDate;

/** 金额均为精确十进制字符串，空值与零值有不同含义。 */
public record CostDashboardRecordView(
        String source,
        String sourceId,
        String documentNo,
        String title,
        String category,
        String city,
        String cityName,
        LocalDate date,
        String amount,
        String status,
        String payment,
        String sourceSystem,
        String employeeName, String costMonth, String periodBasis, String costTreatment) {}
