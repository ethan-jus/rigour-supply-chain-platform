package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.OrderWorkbenchApi.*;
import com.rigour.order.application.port.out.OrderWorkbenchStore;
import com.rigour.order.application.port.out.EmployeeBusinessTargetClient;
import com.rigour.shared.context.*;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public final class OrderWorkbenchService {
    private final OrderWorkbenchStore store;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final EmployeeBusinessTargetClient targets;
    public OrderWorkbenchService(OrderWorkbenchStore store, EmployeeBusinessTargetClient targets) {
        this.store = store; this.targets = targets;
    }

    public Performance performance(String employee, LocalDate from, LocalDate to) {
        validate(employee, "performance", from, to, 0, 1);
        store.requireSelf(employee.trim(), "order:payment:read");
        var page = store.performanceTotals(AuthorizationContext.requireCurrent().tenantId().toString(),
                employee.trim(), "order:payment:read", start(from), start(to.plusDays(1)));
        var tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        var target = targets.values(tenant, employee.trim(), YearMonth.from(from).toString(), YearMonth.from(to).toString());
        return new Performance(page.amount(), target.receiptTargetAmount(), page.total());
    }

    public Summary summary(String employee, LocalDate from, LocalDate to) {
        validate(employee, "orders", from, to, 0, 1);
        store.requireSelf(employee.trim(), "order:read");
        var tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        var summary = store.summary(tenant, employee.trim(), "order:read", start(from), start(to.plusDays(1)));
        // HR targets are read outside the order aggregate cache so monthly edits take effect immediately.
        var target = targets.values(tenant, employee.trim(), YearMonth.from(from).toString(), YearMonth.from(to).toString());
        return new Summary(summary.tradeAmount(), summary.orderCount(), summary.pendingAmount(),
                summary.pendingCount(), summary.outboundCount(), summary.pendingPreview(), target.salesTargetAmount());
    }

    private static Instant start(LocalDate date) { return date.atStartOfDay(BUSINESS_ZONE).toInstant(); }

    private static String validate(String employee, String kind, LocalDate from, LocalDate to, int begin, int step) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        String action = ("receipts".equals(kind) || "performance".equals(kind)) ? "order:payment:read" : "order:read";
        AuthorizationContext.requirePermission(action);
        if (employee == null || employee.isBlank() || employee.length() > 80
                || from == null || to == null || from.isAfter(to)
                || ChronoUnit.DAYS.between(from, to) > 366
                || kind == null || !Set.of("orders", "unpaid", "outbound", "receipts", "performance").contains(kind)
                || begin < 0 || step < 1 || step > 100) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工作台查询条件无效", java.util.List.of());
        }
        return action;
    }

    public Page details(String employee, String kind, LocalDate from, LocalDate to, int begin, int step) {
        var action = validate(employee, kind, from, to, begin, step);
        var caller = AuthorizationContext.requireCurrent();
        if ("performance".equals(kind)) store.requireSelf(employee.trim(), action);
        return store.details(caller.tenantId().toString(), employee.trim(), action, kind,
                from.atStartOfDay(BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant(), begin, step);
    }
    public CustomerSummary customerSummary(String employee, LocalDate from, LocalDate to) {
        validate(employee, "orders", from, to, 0, 1);
        return store.customerSummary(AuthorizationContext.requireCurrent().tenantId().toString(), employee.trim(), start(from), start(to.plusDays(1)));
    }
    public Customers customers(String employee, String kind, LocalDate from, LocalDate to, String keyword, int begin, int step) {
        validate(employee, "orders", from, to, begin, step);
        if (kind == null || !Set.of("PURCHASING", "NEW", "RETURNING", "COOPERATED").contains(kind)
                || keyword == null || keyword.length() > 100)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "客户统计条件无效", java.util.List.of());
        return store.customers(AuthorizationContext.requireCurrent().tenantId().toString(), employee.trim(), kind, start(from), start(to.plusDays(1)), keyword.trim(), begin, step);
    }
    public Page customerOrders(String employee, String customerKey, int begin, int step) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("order:read");
        if (employee == null || employee.isBlank() || employee.length() > 80 || begin < 0 || step < 1 || step > 100)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "客户订单查询条件无效", java.util.List.of());
        if (customerKey == null || !customerKey.matches("[CS][1-9][0-9]{0,18}"))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "客户标识无效", java.util.List.of());
        return store.customerOrders(AuthorizationContext.requireCurrent().tenantId().toString(), employee.trim(), customerKey, begin, step);
    }
}
