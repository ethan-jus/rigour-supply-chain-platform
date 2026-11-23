package com.rigour.order.api.v1;

import com.rigour.shared.core.api.ApiResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/** 销售工作台：本人筛选叠加现有订单数据权限，金额与明细使用相同条件。 */
public interface OrderWorkbenchApi {
    record Row(String id, String orderId, String number, String customerName,
               Instant occurredAt, BigDecimal amount, String status) {}
    record Page(long total, BigDecimal amount, List<Row> items) {}
    record Summary(BigDecimal tradeAmount, long orderCount, BigDecimal pendingAmount,
                   long pendingCount, long outboundCount, Row pendingPreview, BigDecimal salesTargetAmount) {}

    record Performance(BigDecimal completedAmount, BigDecimal targetAmount, long orderCount) {}

    @GetMapping("/api/v1/orders/workbench/performance")
    ApiResponse<Performance> performance(@RequestParam String ownerEmployeeCode,
            @RequestParam LocalDate from, @RequestParam LocalDate to);

    @GetMapping("/api/v1/orders/workbench/summary")
    ApiResponse<Summary> summary(@RequestParam String ownerEmployeeCode,
            @RequestParam LocalDate from, @RequestParam LocalDate to);

    @GetMapping("/api/v1/orders/workbench/details")
    ApiResponse<Page> details(@RequestParam String ownerEmployeeCode,
            @RequestParam String kind, @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);
    record CustomerSummary(long purchasing, long newCooperation, long returning, long cooperated) {}
    record CustomerRow(String key, String name, long orderCount, Instant lastOrderAt) {}
    record Customers(long total, List<CustomerRow> items) {}

    @GetMapping("/api/v1/orders/workbench/customer-summary")
    ApiResponse<CustomerSummary> customerSummary(@RequestParam String ownerEmployeeCode,
            @RequestParam LocalDate from, @RequestParam LocalDate to);

    @GetMapping("/api/v1/orders/workbench/customers")
    ApiResponse<Customers> customers(@RequestParam String ownerEmployeeCode,
            @RequestParam String kind, @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step);

    @GetMapping("/api/v1/orders/workbench/customer-orders")
    ApiResponse<Page> customerOrders(@RequestParam String ownerEmployeeCode, @RequestParam String customerKey,
            @RequestParam(defaultValue = "0") int begin, @RequestParam(defaultValue = "20") int step);
}
