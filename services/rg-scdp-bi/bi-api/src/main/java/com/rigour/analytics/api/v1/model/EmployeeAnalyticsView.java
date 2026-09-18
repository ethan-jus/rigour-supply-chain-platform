package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** HR 当前人员快照与所选期间订单业绩；当前状态不回推历史在职人数。 */
public record EmployeeAnalyticsView(
        String status, Instant syncedAt, Instant from, Instant to, Summary summary,
        List<Structure> cities, List<Structure> positions, List<Employee> employees, List<Month> months) {
    public record Summary(long total, long active, long left, long inactive, long pending,
            long joinedInPeriod, long leftInPeriod, long missingEntryDate,
            long missingDepartment, long unmappedCity, Long orderingEmployees) { }
    public record Month(String month, long joined, long left) { }
    public record Structure(String name, long total, long active, long left) { }
    public record Employee(String employeeCode, String employeeName, String employmentStatus,
            String cityName, String positionName, String departmentName, Instant entryDate, Instant leaveDate,
            Long customerCount, Long orderCount, BigDecimal salesAmount, BigDecimal paidAmount) { }
}
