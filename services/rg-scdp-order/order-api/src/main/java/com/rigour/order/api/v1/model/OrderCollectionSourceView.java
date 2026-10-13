package com.rigour.order.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OrderCollectionSourceView(
        String sourceId, String orderNo, LocalDate date, BigDecimal amount, String kind) {}
