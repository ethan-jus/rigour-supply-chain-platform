package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record SalesPayrollBoardView(String month, List<Employee> employees) {
    public record Order(
            String id, String orderNo, LocalDate settledOn, BigDecimal basis, String state) {}

    public record Commission(
            String orderNo,
            String product,
            String role,
            String rule,
            int ruleVersion,
            BigDecimal quantity,
            BigDecimal accrued,
            BigDecimal released) {}

    public record Employee(
            String code,
            String name,
            String city,
            String status,
            String basisSource,
            BigDecimal basis,
            List<Order> orders,
            BigDecimal commission,
            BigDecimal held,
            BigDecimal baseSalary,
            BigDecimal processPay,
            BigDecimal performance,
            BigDecimal allowance,
            BigDecimal deduction,
            BigDecimal gross,
            BigDecimal net,
            BigDecimal paid,
            BigDecimal correction,
            List<String> issues,
            List<Commission> details,
            List<PayrollMetricResultView> metrics,
            int revision,
            Instant updatedAt,
            String payrollId,
            String department,
            String role,
            BigDecimal collectionTarget,
            List<PayrollCorrectionView> corrections) {
        public Employee withPayment(BigDecimal registered) {
            if (registered == null) return this;
            String progress =
                    net != null && net.signum() > 0 && registered.compareTo(net) >= 0
                            ? "PAID"
                            : status;
            return new Employee(
                    code,
                    name,
                    city,
                    progress,
                    basisSource,
                    basis,
                    orders,
                    commission,
                    held,
                    baseSalary,
                    processPay,
                    performance,
                    allowance,
                    deduction,
                    gross,
                    net,
                    registered,
                    correction,
                    issues,
                    details,
                    metrics,
                    revision,
                    updatedAt,
                    payrollId,
                    department,
                    role,
                    collectionTarget,
                    corrections);
        }
    }
}
