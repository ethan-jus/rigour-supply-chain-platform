package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.ReceivablesView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ReceivablesStore {
    record Scope(
            String tenant,
            String region,
            String owner,
            String customer,
            boolean full,
            Long departmentId,
            boolean includeSubDepartments) {
        public Scope(String tenant, String region, String owner, String customer, boolean full) {
            this(tenant, region, owner, customer, full, null, false);
        }
    }

    Instant projectedAt(String tenant);

    List<ReceivablesView.Balance> balances(Scope scope, LocalDate asOf);

    List<ReceivablesView.Receipt> receipts(Scope scope, LocalDate asOf);

    record OverviewRow(LocalDate date, BigDecimal received, BigDecimal outstanding, BigDecimal overdue) {}

    List<OverviewRow> overview(Scope scope, LocalDate from, LocalDate asOf);

    List<ReceivablesView.Application> applications(Scope scope, LocalDate from, LocalDate asOf);

    java.math.BigDecimal adjustments(Scope scope, LocalDate from, LocalDate asOf);

    java.math.BigDecimal newReceivables(Scope scope, LocalDate from, LocalDate asOf);
}
