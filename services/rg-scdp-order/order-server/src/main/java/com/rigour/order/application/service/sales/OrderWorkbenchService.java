package com.rigour.order.application.service.sales;

import com.rigour.order.api.v1.OrderWorkbenchApi.*;
import com.rigour.order.application.port.out.OrderWorkbenchStore;
import com.rigour.order.application.port.out.EmployeePerformanceTargetClient;
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
    private final EmployeePerformanceTargetClient targets;
    public OrderWorkbenchService(OrderWorkbenchStore store, EmployeePerformanceTargetClient targets) {
        this.store = store; this.targets = targets;
    }

    public Performance performance(String employee, LocalDate from, LocalDate to) {
        validate(employee, "performance", from, to, 0, 1);
        store.requireSelf(employee.trim(), "order:payment:read");
        var page = store.performanceTotals(AuthorizationContext.requireCurrent().tenantId().toString(),
                employee.trim(), "order:payment:read", start(from), start(to.plusDays(1)));
        var tenant = AuthorizationContext.requireCurrent().tenantId().toString();
        var target = targets.value(tenant, employee.trim(), YearMonth.from(from).toString(), YearMonth.from(to).toString());
        return new Performance(page.amount(), target, page.total());
    }

    public Summary summary(String employee, LocalDate from, LocalDate to) {
        validate(employee, "orders", from, to, 0, 1);
        return store.summary(AuthorizationContext.requireCurrent().tenantId().toString(), employee.trim(),
                "order:read", start(from), start(to.plusDays(1)));
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
}
