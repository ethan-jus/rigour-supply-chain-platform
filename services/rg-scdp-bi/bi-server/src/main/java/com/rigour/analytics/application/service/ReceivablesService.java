package com.rigour.analytics.application.service;

import static java.math.BigDecimal.ZERO;

import com.rigour.analytics.api.v1.model.ReceivablesView;
import com.rigour.analytics.api.v1.model.ReceivablesView.*;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.ReceivablesStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;

@Service
public class ReceivablesService {
    private final BiDataScopeService scopes;
    private final ReceivablesStore store;
    private final Clock clock;
    static final List<String> BANDS =
            List.of("CURRENT", "D1_30", "D31_60", "D61_90", "OVER90", "UNKNOWN");

    public ReceivablesService(
            BiDataScopeService scopes, ReceivablesStore store, Clock analyticsClock) {
        this.scopes = scopes;
        this.store = store;
        this.clock = analyticsClock;
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReceivablesView load(
            LocalDate from,
            LocalDate asOf,
            String region,
            String owner,
            String customer,
            String aging,
            int page,
            int size) {
        validatePage(aging, page, size);
        return page(calculate(from, asOf, region, owner, customer), aging, page, size, null, null);
    }

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReceivablesView loadFiltered(
            LocalDate from,
            LocalDate asOf,
            String region,
            String owner,
            String customer,
            String aging,
            int page,
            int size,
            String collector,
            Boolean disputed) {
        validatePage(aging, page, size);
        return page(
                calculate(from, asOf, region, owner, customer),
                aging,
                page,
                size,
                collector,
                disputed);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ReceivablesView loadFiltered(
            LocalDate from, LocalDate asOf, String region, String owner, String customer,
            String aging, int page, int size, String collector, Boolean disputed,
            Long departmentId, boolean includeSubDepartments) {
        validatePage(aging, page, size);
        return page(calculate(from, asOf, region, owner, customer, departmentId, includeSubDepartments),
                aging, page, size, collector, disputed);
    }

    ReceivablesView calculate(
            LocalDate from, LocalDate asOf, String region, String owner, String customer) {
        return calculate(from, asOf, region, owner, customer, null, false);
    }

    private ReceivablesView calculate(
            LocalDate from, LocalDate asOf, String region, String owner, String customer,
            Long departmentId, boolean includeSubDepartments) {
        if (from == null
                || asOf == null
                || from.isAfter(asOf)
                || asOf.isAfter(LocalDate.now(clock.withZone(BiBusinessTime.ZONE)))
                || (departmentId != null && departmentId <= 0)
                || (customer != null && !customer.matches("[0-9]{1,20}")))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请核对统计起止日期、筛选和分页条件", List.of());
        var current = scopes.resolve(region, owner);
        boolean full =
                current.fullTenant()
                        && current.regionCode() == null
                        && current.ownerStaffCode() == null
                        && departmentId == null;
        var scope =
                new ReceivablesStore.Scope(
                        current.tenantId(),
                        current.regionCode(),
                        current.ownerStaffCode(),
                        customer,
                        full,
                        departmentId,
                        includeSubDepartments);
        var updated = store.projectedAt(current.tenantId());
        if (updated == null)
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "资金数据尚未完成同步", List.of());
        var balances =
                store.balances(scope, asOf).stream()
                        .filter(b -> b.remaining().signum() > 0)
                        .toList();
        var money = store.receipts(scope, asOf);
        var apps = store.applications(scope, from, asOf);
        var period = money.stream().filter(r -> !r.date().isBefore(from)).toList();
        var openOrders = new HashMap<String, BigDecimal>();
        var buckets = new LinkedHashMap<String, BigDecimal>();
        var clients = new HashMap<String, CustomerTotals>();
        BANDS.forEach(k -> buckets.put(k, ZERO));
        BigDecimal dueSoon = ZERO;
        for (var b : balances) {
            openOrders.putIfAbsent(b.orderId(), b.original());
            buckets.merge(b.aging(), b.remaining(), BigDecimal::add);
            var client = clients.computeIfAbsent(b.customerId(), k -> new CustomerTotals());
            client.name = b.customer();
            client.unpaid = client.unpaid.add(b.remaining());
            if (b.overdueDays() != null && b.overdueDays() > 0)
                client.overdue = client.overdue.add(b.remaining());
            if (b.dueDate() != null
                    && !b.dueDate().isBefore(asOf)
                    && b.dueDate().isBefore(asOf.plusDays(7))) dueSoon = dueSoon.add(b.remaining());
        }
        for (var cash : money) {
            if (!cash.verified() || !"RECEIPT".equals(cash.kind())) continue;
            var client = clients.computeIfAbsent(cash.customerId(), k -> new CustomerTotals());
            if (client.name == null) client.name = cash.customer();
            client.cumulative = client.cumulative.add(cash.amount());
            client.unapplied = client.unapplied.add(cash.available());
            if (!cash.date().isBefore(from)) client.cash = client.cash.add(cash.amount());
        }
        var rows =
                clients.entrySet().stream()
                        .map(e -> e.getValue().view(e.getKey()))
                        .sorted(
                                Comparator.comparing(Customer::unpaid)
                                        .reversed()
                                        .thenComparing(
                                                Customer::id,
                                                Comparator.nullsLast(String::compareTo)))
                        .toList();
        var closing = sum(balances, Balance::remaining);
        var overdue = closing.subtract(buckets.get("CURRENT")).subtract(buckets.get("UNKNOWN"));
        var received = cashOf(period, "RECEIPT");
        var paidBack = cashOf(period, "REFUND");
        var lifetime = cashOf(money, "RECEIPT");
        var totals =
                new Totals(
                        received,
                        paidBack,
                        received.subtract(paidBack),
                        lifetime,
                        lifetime.subtract(cashOf(money, "REFUND")),
                        full ? sum(money, Receipt::available) : null,
                        sum(period.stream().filter(r -> !r.verified()).toList(), Receipt::amount),
                        sum(store.balances(scope, from.minusDays(1)), Balance::remaining),
                        store.newReceivables(scope, from, asOf),
                        sum(apps, Application::amount),
                        store.adjustments(scope, from, asOf),
                        closing,
                        overdue,
                        dueSoon,
                        buckets.get("UNKNOWN"),
                        openOrders.values().stream().reduce(ZERO, BigDecimal::add));
        String version = version(from, asOf, totals, balances, money, apps);
        return new ReceivablesView(
                from,
                asOf,
                updated,
                version,
                full,
                money.stream()
                        .filter(Receipt::verified)
                        .map(Receipt::date)
                        .min(LocalDate::compareTo)
                        .orElse(null),
                totals,
                buckets.entrySet().stream().map(e -> new Bucket(e.getKey(), e.getValue())).toList(),
                rows,
                rows.size(),
                balances,
                balances.size(),
                period,
                period.size(),
                apps,
                apps.size(),
                groupCash(period));
    }

    private static List<CashGroup> groupCash(List<Receipt> period) {
        var result = new ArrayList<CashGroup>();
        Map<String, java.util.function.Function<Receipt, String>> dimensions =
                new LinkedHashMap<>();
        dimensions.put("CHANNEL", Receipt::channel);
        dimensions.put("PAYEE", Receipt::payee);
        dimensions.put("COLLECTOR", Receipt::ownerCode);
        dimensions.forEach(
                (dimension, name) -> {
                    var groups = new TreeMap<String, BigDecimal[]>();
                    for (var cash : period)
                        if (cash.verified()) {
                            var label = Objects.requireNonNullElse(name.apply(cash), "待核");
                            var values =
                                    groups.computeIfAbsent(
                                            label, k -> new BigDecimal[] {ZERO, ZERO});
                            int index = "REFUND".equals(cash.kind()) ? 1 : 0;
                            values[index] = values[index].add(cash.amount());
                        }
                    groups.forEach(
                            (label, values) ->
                                    result.add(
                                            new CashGroup(
                                                    dimension,
                                                    label,
                                                    values[0],
                                                    values[1],
                                                    values[0].subtract(values[1]))));
                });
        return List.copyOf(result);
    }

    private static void validatePage(String aging, int page, int size) {
        if (page < 1
                || size < 1
                || size > 200
                || (aging != null && !BANDS.contains(aging) && !Set.of("ACTIONABLE", "OVERDUE").contains(aging)))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "分页或账龄分组无效", List.of());
    }

    private static boolean matchesAging(Balance balance, String aging, LocalDate asOf) {
        if (aging == null) return true;
        return switch (aging) {
            case "ACTIONABLE" -> balance.dueDate() == null || balance.dueDate().isBefore(asOf.plusDays(7));
            case "OVERDUE" -> balance.overdueDays() != null && balance.overdueDays() > 0;
            default -> aging.equals(balance.aging());
        };
    }

    static ReceivablesView page(
            ReceivablesView v,
            String aging,
            int page,
            int size,
            String collector,
            Boolean disputed) {
        validatePage(aging, page, size);
        var balances =
                v.balances().stream()
                        .filter(
                                b ->
                                        collector == null
                                                || collector.isBlank()
                                                || collector.equals(b.followOwner()))
                        .filter(b -> disputed == null || disputed.equals(b.disputed()))
                        .filter(
                                b ->
                                        matchesAging(b, aging, v.asOf()))
                        .toList();
        return new ReceivablesView(
                v.from(),
                v.asOf(),
                v.projectedAt(),
                v.version(),
                v.completeCashScope(),
                v.coverageStart(),
                v.totals(),
                v.aging(),
                slice(v.customers(), page, size),
                v.customerCount(),
                slice(balances, page, size),
                balances.size(),
                slice(v.receipts(), page, size),
                v.receiptCount(),
                slice(v.applications(), page, size),
                v.applicationCount(),
                v.cashGroups());
    }

    private static String version(Object... parts) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                if (part instanceof List<?> rows)
                    for (var row : rows) {
                        digest.update(
                                row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                    }
                else
                    digest.update(
                            part.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 1);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Overview(
            BigDecimal received,
            BigDecimal outstanding,
            BigDecimal overdue,
            List<com.rigour.analytics.api.v1.model.SupplyDashboardTrendPointView> days) {}

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Overview overview(
            com.rigour.analytics.application.model.SupplyDashboardFilter filter,
            BiDataScopeService.ScopedSelection current) {
        if (filter.productCategoryId() != null
                || filter.customerTypeCode() != null
                || filter.sourceSystemCode() != null
                || store.projectedAt(current.tenantId()) == null)
            return new Overview(null, null, null, List.of());
        var start = filter.from().atZone(BiBusinessTime.ZONE).toLocalDate();
        var end = filter.to().atZone(BiBusinessTime.ZONE).toLocalDate();
        var scope =
                new ReceivablesStore.Scope(
                        current.tenantId(),
                        current.regionCode(),
                        current.ownerStaffCode(),
                        null,
                        current.fullTenant()
                                && current.regionCode() == null
                                && current.ownerStaffCode() == null);
        var balances = store.balances(scope, end);
        var cash =
                store.receipts(scope, end).stream()
                        .filter(
                                r ->
                                        r.verified()
                                                && "RECEIPT".equals(r.kind())
                                                && !r.date().isBefore(start))
                        .toList();
        var days = new java.util.TreeMap<LocalDate, BigDecimal>();
        cash.forEach(r -> days.merge(r.date(), r.amount(), BigDecimal::add));
        return new Overview(
                sum(cash, Receipt::amount),
                sum(balances, Balance::remaining),
                sum(
                        balances.stream()
                                .filter(b -> b.overdueDays() != null && b.overdueDays() > 0)
                                .toList(),
                        Balance::remaining),
                days.entrySet().stream()
                        .map(
                                e ->
                                        new com.rigour.analytics.api.v1.model
                                                .SupplyDashboardTrendPointView(
                                                "receipt_amount",
                                                e.getKey().toString(),
                                                e.getValue(),
                                                null))
                        .toList());
    }

    private static BigDecimal cashOf(List<Receipt> rows, String kind) {
        return sum(
                rows.stream().filter(r -> r.verified() && kind.equals(r.kind())).toList(),
                Receipt::amount);
    }

    private static <T> BigDecimal sum(List<T> values, Function<T, BigDecimal> amount) {
        return values.stream().map(amount).reduce(ZERO, BigDecimal::add);
    }

    private static <T> List<T> slice(List<T> rows, int page, int size) {
        int from = (int) Math.min((long) (page - 1) * size, rows.size());
        return rows.subList(from, Math.min(rows.size(), from + size));
    }

    private static class CustomerTotals {
        String name;
        BigDecimal cash = ZERO, cumulative = ZERO, unpaid = ZERO, overdue = ZERO, unapplied = ZERO;

        Customer view(String id) {
            return new Customer(id, name, cash, cumulative, unpaid, overdue, unapplied);
        }
    }
}
