package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * All effective receipts by frozen operating city and arrival date; refunds are a separate metric.
 */
public record OrderCityCollectionSourceView(
        String sourceId,
        String orderNo,
        String cityCode,
        LocalDate date,
        BigDecimal amount,
        String qualityStatus) {}
