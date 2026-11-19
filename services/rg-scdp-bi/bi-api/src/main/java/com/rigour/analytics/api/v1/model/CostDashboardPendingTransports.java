package com.rigour.analytics.api.v1.model;

import java.time.LocalDate;
import java.util.List;

public record CostDashboardPendingTransports(int total, int scopeTotal, int page, int size, List<Item> items) {
    public record Item(
            String id,
            String documentNo,
            String cityName,
            String sourceSystem,
            LocalDate date,
            String costMonth,
            List<String> missing) {}
}
