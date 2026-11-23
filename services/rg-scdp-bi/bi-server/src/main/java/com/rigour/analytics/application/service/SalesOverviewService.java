package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.ReceivablesView.Balance;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.model.SalesOverview;
import com.rigour.analytics.application.model.SalesOverview.*;
import com.rigour.analytics.application.port.out.ReceivablesStore;
import com.rigour.analytics.application.port.out.SalesOverviewStore;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SalesOverviewService {
    private final BiDataScopeService scopes;
    private final SalesOverviewStore store;
    private final ReceivablesStore receivables;
    private final Clock clock;

    public SalesOverviewService(
            BiDataScopeService scopes,
            SalesOverviewStore store,
            ReceivablesStore receivables,
            Clock analyticsClock) {
        this.scopes = scopes;
        this.store = store;
        this.receivables = receivables;
        this.clock = analyticsClock;
    }

    public record Query(
            LocalDate from,
            LocalDate asOf,
            String ownerStaffCode,
            Long departmentId,
            Boolean includeSubDepartments) {}

    private ReceivablesStore.Scope scope(Query q) {
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        if (q.from() == null
                || q.asOf() == null
                || q.from().isAfter(q.asOf())
                || q.from().getYear() != q.asOf().getYear()
                || q.asOf().isAfter(LocalDate.now(clock.withZone(BiBusinessTime.ZONE)))
                || (q.departmentId() != null && q.departmentId() <= 0))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择有效的销售统计期间与部门", List.of());
        var access = scopes.resolve(null, q.ownerStaffCode());
        return new ReceivablesStore.Scope(
                access.tenantId(),
                access.regionCode(),
                access.ownerStaffCode(),
                null,
                access.fullTenant() && access.ownerStaffCode() == null && q.departmentId() == null,
                q.departmentId(),
                Boolean.TRUE.equals(q.includeSubDepartments()));
    }

    public SalesOverview overview(Query q) {
        var scope = scope(q);
        var tenant = scope.tenant();
        var financeAsOf = receivables.projectedAt(tenant);
        boolean receiptsAvailable = store.receiptsAvailable(tenant);
        var allInDepartment =
                new ReceivablesStore.Scope(
                        tenant,
                        scope.region(),
                        null,
                        null,
                        false,
                        scope.departmentId(),
                        scope.includeSubDepartments());
        var owners =
                store.owners(allInDepartment, q.from(), q.asOf(), financeAsOf != null, receiptsAvailable);
        var employees =
                owners.stream()
                        .filter(r -> r.code() != null)
                        .map(r -> new Option(r.code(), r.name()))
                        .sorted(Comparator.comparing(Option::name).thenComparing(Option::code))
                        .toList();
        var selected =
                owners.stream()
                        .filter(
                                r ->
                                        scope.owner() == null
                                                || scope.owner().equalsIgnoreCase(r.code()))
                        .toList();
        var first = YearMonth.from(q.from());
        var last = YearMonth.from(q.asOf());
        int months = (int) ChronoUnit.MONTHS.between(first, last) + 1;
        List<Target> targetRows;
        boolean targetsAvailable = true;
        try {
            targetRows =
                    selected.isEmpty()
                            ? List.of()
                            : store.targets(tenant, first.toString(), last.toString());
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE) throw e;
            targetRows = List.of();
            targetsAvailable = false;
        }
        var goals =
                targetRows.stream()
                        .filter(t -> "SALES_OWNER".equals(t.dimensionType()))
                        .collect(Collectors.groupingBy(
                                Target::code, Collectors.groupingBy(Target::metric)));
        var balances =
                financeAsOf == null
                        ? List.<Balance>of()
                        : receivables.balances(scope, q.asOf()).stream()
                                .filter(b -> b.remaining().signum() > 0)
                                .toList();
        var balancesByEmployee =
                balances.stream()
                        .filter(b -> b.employeeCode() != null)
                        .collect(
                                Collectors.toMap(
                                        Balance::employeeCode,
                                        Balance::remaining,
                                        BigDecimal::add));
        var people =
                selected.stream()
                        .map(
                                r -> {
                                    var metrics =
                                            r.code() == null
                                                    ? Map.<String, List<Target>>of()
                                                    : goals.getOrDefault(r.code(), Map.of());
                                    var orderGoal = periodTarget(
                                            metrics.getOrDefault("SALES_AMOUNT", List.of()), months);
                                    var receiptGoal = periodTarget(
                                            metrics.getOrDefault("RECEIPT_AMOUNT", List.of()), months);
                                    var balance =
                                            financeAsOf == null || r.code() == null
                                                    ? null
                                                    : balancesByEmployee.getOrDefault(
                                                            r.code(), BigDecimal.ZERO);
                                    return new Employee(
                                            r.code(),
                                            r.name(),
                                            r.city(),
                                            r.booked(),
                                            r.received(),
                                            r.applied(),
                                            balance,
                                            orderGoal.value(),
                                            orderGoal.missing(),
                                            receiptGoal.value(),
                                            receiptGoal.missing());
                                })
                        .toList();

        var booked = people.stream().map(Employee::booked).reduce(BigDecimal.ZERO, BigDecimal::add);
        var received = receiptsAvailable
                ? people.stream().map(Employee::received).reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        BigDecimal applied = null, outstanding = null, overdue = null;
        Long overdueOrders = null;
        if (financeAsOf != null) {
            applied =
                    people.stream()
                            .map(Employee::applied)
                            .filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            var overdueBalances =
                    balances.stream()
                            .filter(b -> b.overdueDays() != null && b.overdueDays() > 0)
                            .toList();
            outstanding =
                    balances.stream()
                            .map(Balance::remaining)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            overdue =
                    overdueBalances.stream()
                            .map(Balance::remaining)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            overdueOrders = overdueBalances.stream().map(Balance::orderId).distinct().count();
        }
        return new SalesOverview(
                store.departments(tenant),
                employees,
                people,
                booked,
                received,
                applied,
                outstanding,
                overdue,
                overdueOrders,
                financeAsOf,
                targetsAvailable,
                scopes.effective().globalGovernance());
    }

    private record PeriodTarget(BigDecimal value, int missing) {}

    private static PeriodTarget periodTarget(List<Target> rows, int months) {
        int missing = months - (int) rows.stream().map(Target::month).distinct().count();
        var value = missing == 0
                ? rows.stream().map(Target::value).reduce(BigDecimal.ZERO, BigDecimal::add)
                : null;
        return new PeriodTarget(value, missing);
    }

    public DetailPage details(Query q, DetailKind kind, int page, int size) {
        var scope = scope(q);
        if (page < 1 || size < 1 || size > 100)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择有效的分页范围", List.of());
        boolean finance =
                kind != DetailKind.PRODUCTS && receivables.projectedAt(scope.tenant()) != null;
        if (kind == DetailKind.APPLICATIONS && !finance)
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "核销数据待同步", List.of());
        return store.details(scope, q.from(), q.asOf(), kind, finance, page, size);
    }
}
