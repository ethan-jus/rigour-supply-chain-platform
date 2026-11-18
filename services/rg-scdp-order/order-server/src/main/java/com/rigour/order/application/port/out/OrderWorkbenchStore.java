package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.OrderWorkbenchApi.*;
import java.time.Instant;

public interface OrderWorkbenchStore {
    record Totals(long total, java.math.BigDecimal amount) {}
    Summary summary(String tenant, String employee, String action, Instant from, Instant until);
    Totals performanceTotals(String tenant, String employee, String action, Instant from, Instant until);
    void requireSelf(String employee, String action);
    Page details(String tenant, String employee, String action, String kind,
                 Instant from, Instant until, int begin, int step);
    CustomerSummary customerSummary(String tenant, String employee, Instant from, Instant until);
    Customers customers(String tenant, String employee, String kind, Instant from, Instant until, String keyword, int begin, int step);
    Page customerOrders(String tenant, String employee, String customerKey, int begin, int step);
}
