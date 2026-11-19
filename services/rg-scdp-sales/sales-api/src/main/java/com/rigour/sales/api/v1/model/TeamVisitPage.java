package com.rigour.sales.api.v1.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TeamVisitPage(List<Row> items, long total) {
    public record Row(
            String id,
            LocalDate date,
            String employee,
            String name,
            String customer,
            String store,
            String status,
            Instant arrived,
            Instant departed,
            String result,
            String outcome,
            String intention) {
        public Row displayName(String value) {
            return new Row(
                    id, date, employee, value, customer, store, status, arrived, departed, result,
                    outcome, intention);
        }
    }
}
