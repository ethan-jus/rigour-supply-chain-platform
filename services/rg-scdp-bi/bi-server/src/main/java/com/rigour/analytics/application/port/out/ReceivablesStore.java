package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.ReceivablesView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public interface ReceivablesStore {
    record Scope(String tenant, String region, String owner, String customer, boolean full) {}

    Instant projectedAt(String tenant);

    List<ReceivablesView.Balance> balances(Scope scope, LocalDate asOf);

    List<ReceivablesView.Receipt> receipts(Scope scope, LocalDate asOf);

    List<ReceivablesView.Application> applications(Scope scope, LocalDate from, LocalDate asOf);

    java.math.BigDecimal adjustments(Scope scope, LocalDate from, LocalDate asOf);

    java.math.BigDecimal newReceivables(Scope scope, LocalDate from, LocalDate asOf);
}
