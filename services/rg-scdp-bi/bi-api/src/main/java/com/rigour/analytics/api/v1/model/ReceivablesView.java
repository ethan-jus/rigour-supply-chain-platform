package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReceivablesView(
        LocalDate from,
        LocalDate asOf,
        Instant projectedAt,
        String version,
        boolean completeCashScope,
        LocalDate coverageStart,
        Totals totals,
        List<Bucket> aging,
        List<Customer> customers,
        int customerCount,
        List<Balance> balances,
        int balanceCount,
        List<Receipt> receipts,
        int receiptCount,
        List<Application> applications,
        int applicationCount,
        List<CashGroup> cashGroups) {
    public record CashGroup(
            String dimension,
            String label,
            BigDecimal received,
            BigDecimal refunded,
            BigDecimal net) {}

    public record Totals(
            BigDecimal receipts,
            BigDecimal refunds,
            BigDecimal net,
            BigDecimal cumulativeReceipts,
            BigDecimal cumulativeNet,
            BigDecimal unapplied,
            BigDecimal pending,
            BigDecimal opening,
            BigDecimal newReceivables,
            BigDecimal applied,
            BigDecimal adjustments,
            BigDecimal outstanding,
            BigDecimal overdue,
            BigDecimal dueSoon,
            BigDecimal missingDue,
            BigDecimal openOrderAmount) {}

    public record Bucket(String code, BigDecimal amount) {}

    public record Customer(
            String id,
            String name,
            BigDecimal receipts,
            BigDecimal cumulativeReceipts,
            BigDecimal unpaid,
            BigDecimal overdue,
            BigDecimal unapplied) {}

    public record Balance(
            String orderId,
            String orderNo,
            String customerId,
            String customer,
            String region,
            String owner,
            String employeeCode,
            String regionCode,
            int installment,
            LocalDate dueDate,
            String dueSource,
            BigDecimal original,
            BigDecimal installmentAmount,
            BigDecimal paid,
            BigDecimal remaining,
            String aging,
            Long overdueDays,
            String followOwner,
            LocalDate promisedOn,
            Boolean disputed,
            Instant followedAt,
            Instant nextDue,
            String followNote) {}

    public record Receipt(
            String key,
            String reference,
            String customerId,
            String customer,
            LocalDate date,
            String kind,
            boolean verified,
            BigDecimal amount,
            BigDecimal allocated,
            BigDecimal available,
            String channel,
            String payee,
            String ownerCode,
            Instant verifiedAt) {}

    public record Application(
            String id,
            String cashKey,
            String reference,
            String orderId,
            String orderNo,
            LocalDate date,
            BigDecimal amount) {}
}
