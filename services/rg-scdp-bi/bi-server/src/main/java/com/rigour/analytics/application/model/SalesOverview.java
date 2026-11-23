package com.rigour.analytics.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SalesOverview(
        List<Department> departments,
        List<Option> employees,
        List<Employee> people,
        BigDecimal booked,
        BigDecimal received,
        BigDecimal applied,
        BigDecimal outstanding,
        BigDecimal overdue,
        Long overdueOrders,
        Instant financeAsOf,
        boolean targetsAvailable,
        boolean canSeeCompanyCash) {
    public record Department(long id, Long parentId, String departmentName, int sortOrder) {}

    public record Option(String code, String name) {}

    public record Employee(
            String code,
            String name,
            String city,
            BigDecimal booked,
            BigDecimal received,
            BigDecimal applied,
            BigDecimal outstanding,
            BigDecimal target,
            int missingTargetMonths,
            BigDecimal receiptTarget,
            int missingReceiptTargetMonths) {}

    public enum DetailKind {
        PRODUCTS,
        APPLICATIONS,
        ORDERS
    }

    public record Detail(
            String id,
            String orderNo,
            LocalDate date,
            String name,
            String employee,
            BigDecimal amount,
            BigDecimal applied) {}

    public record DetailPage(List<Detail> items, long total) {}
}
