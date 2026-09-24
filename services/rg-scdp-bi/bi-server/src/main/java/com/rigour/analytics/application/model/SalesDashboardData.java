package com.rigour.analytics.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Sales dashboard read model, amounts in yuan; receipt attribution is the order salesperson. */
public record SalesDashboardData(
        List<Person> people,
        List<Goal> goals,
        Cohort history,
        List<Product> products,
        List<Customer> customers,
        List<Month> months,
        ReceiptSplit receiptSplit,
        Instant productSyncedAt,
        List<DailyReceipt> dailyReceipts) {
    public record DailyReceipt(String period, BigDecimal value) {}

    public record Person(
            String code,
            String name,
            String city,
            String employmentStatus,
            BigDecimal sales,
            BigDecimal paid,
            BigDecimal receipts) {}

    public record Goal(String code, int month, String metric, BigDecimal target) {}

    public record Cohort(BigDecimal amount, BigDecimal received) {}

    public record Product(
            String categoryId,
            String category,
            String productId,
            String product,
            String sku,
            BigDecimal quantity,
            BigDecimal sales,
            BigDecimal received,
            BigDecimal receipts,
            boolean allocated) {}

    public record Customer(String code, String name, BigDecimal sales, BigDecimal received) {}

    public record Month(String month, BigDecimal sales, BigDecimal received, BigDecimal receipts) {}

    public record ReceiptSplit(
            BigDecimal currentOrders, BigDecimal historicalOrders, BigDecimal otherOrders) {}
}
