package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.CityProductReportView;
import com.rigour.analytics.api.v1.model.CityProductReportView.OrderTrace;
import com.rigour.analytics.api.v1.model.CityProductReportView.Row;
import com.rigour.analytics.api.v1.model.CityProductReportView.Summary;
import com.rigour.analytics.api.v1.model.CityProductReportView.UnitQuantity;
import com.rigour.analytics.api.v1.model.CityProductReportView.MonthlyRow;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.CityProductReportStore;
import com.rigour.analytics.application.port.out.CityProductReportStore.FactRow;
import com.rigour.analytics.application.port.out.CityProductReportStore.ProductSelection;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.exception.BusinessException;
import com.rigour.shared.core.api.ErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.rigour.analytics.application.model.BiBusinessTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 先按整订单归属应收与累计回款，再筛分类；保留未确认款项和完整订单审计轨迹。 */
@Service
public class CityProductReportService {
    public static final int MAX_ORDERS = 10_000;
    public static final int MAX_SOURCE_ROWS = 50_000;
    public static final int MAX_REPORT_ROWS = 10_000;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal QUANTUM = new BigDecimal("0.000001");
    private final CityProductReportStore store;
    private final Clock clock;

    public CityProductReportService(CityProductReportStore store, Clock analyticsClock) {
        this.store = store;
        this.clock = analyticsClock;
    }

    @Transactional(readOnly = true)
    public CityProductReportView report(
            Instant from, Instant to, String regionCode, String ownerStaffCode, String customerTypeCode,
            Long productCategoryId, String sourceSystemCode, String allocationMode) {
        return report(from, to, regionCode, ownerStaffCode, customerTypeCode, productCategoryId,
                sourceSystemCode, allocationMode, null, null, null);
    }

    @Transactional(readOnly = true)
    public CityProductReportView report(
            Instant from, Instant to, String regionCode, String ownerStaffCode, String customerTypeCode,
            Long productCategoryId, String sourceSystemCode, String allocationMode,
            Long brandId, Long productId, Long skuId) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        Mode mode;
        try {
            mode = allocationMode == null ? Mode.EXACT_ONLY : Mode.valueOf(allocationMode);
        } catch (IllegalArgumentException exception) {
            throw badRequest("allocationMode仅支持EXACT_ONLY或PROPORTIONAL");
        }
        Instant generatedAt = clock.instant();
        String tenantId = caller.tenantId().toString();
        String region = code(regionCode, "regionCode");
        String owner = text(ownerStaffCode, 50, "ownerStaffCode");
        String customerType = code(customerTypeCode, "customerTypeCode");
        String source = code(sourceSystemCode, "sourceSystemCode");
        if ("DHB".equals(source)) source = "DINGHUOBAO";
        if (productCategoryId != null && productCategoryId < 1) throw badRequest("productCategoryId必须大于0");
        for (Long dimension : new Long[]{brandId, productId, skuId})
            if (dimension != null && dimension < 1) throw badRequest("品牌、商品和SKU标识必须大于0");
        var selection = new ProductSelection(brandId, productId, skuId);
        Set<Long> categoryScope = productCategoryId == null ? null : new HashSet<>(store.categoryIds(tenantId, productCategoryId));
        if (categoryScope != null && categoryScope.isEmpty()) throw badRequest("分类目录尚未同步或该分类不存在，请同步ERP分类后重试");
        Predicate<FactRow> selectedLine = row -> (categoryScope == null || categoryScope.contains(row.categoryId()))
                && (brandId == null || brandId.equals(row.brandId()))
                && (productId == null || productId.equals(row.productId()))
                && (skuId == null || skuId.equals(row.skuId()));
        Instant end = to == null ? store.latestOrderDate(tenantId).orElse(generatedAt) : to;
        Instant start = from == null ? BiBusinessTime.monthStart(end) : from;
        Instant roundedStart = start.truncatedTo(ChronoUnit.MICROS);
        if (roundedStart.isBefore(start)) roundedStart = roundedStart.plus(1, ChronoUnit.MICROS);
        end = end.truncatedTo(ChronoUnit.MICROS);
        if (roundedStart.isAfter(end)) throw badRequest("from不能晚于to，时间窗口至少包含一个微秒");
        var filter = new SupplyDashboardFilter(roundedStart, end, region, owner, customerType, productCategoryId, source);
        List<FactRow> facts = store.load(tenantId, filter, selection, MAX_ORDERS + 1, MAX_SOURCE_ROWS + 1);
        if (facts.size() > MAX_SOURCE_ROWS) return blocked(filter, generatedAt, mode);
        Map<Long, List<FactRow>> orders = group(facts, FactRow::orderId);
        if (orders.size() > MAX_ORDERS) return blocked(filter, generatedAt, mode);
        Map<Key, Totals> products = new LinkedHashMap<>();
        Map<Key, Totals> categories = new LinkedHashMap<>();
        Map<String, Map<Key, Totals>> months = new java.util.TreeMap<>();
        List<OrderTrace> traces = new ArrayList<>();
        Instant dataUpdatedAt = facts.stream().flatMap(row -> java.util.stream.Stream.of(row.orderSyncedAt(), row.lineSyncedAt()))
                .filter(Objects::nonNull).map(value -> value.toInstant(ZoneOffset.UTC)).max(Instant::compareTo).orElse(null);
        for (List<FactRow> orderRows : orders.values()) {
            FactRow order = orderRows.getFirst();
            List<FactRow> lines = orderRows.stream().filter(row -> row.lineId() != null).toList();
            // LIMIT 不允许截断最后一个订单；整单分母和返回行都必须完整才能生成报表。
            if (lines.size() != order.lineCount()) return blocked(filter, generatedAt, mode);
            Map<Key, List<FactRow>> productGroups = group(lines, row -> key(row, false));
            Map<Key, List<FactRow>> categoryGroups = group(lines, row -> key(row, true));
            String problem = problem(order, lines);
            Allocation productAllocation = allocate(order, productGroups, categoryGroups, problem, mode, false);
            Allocation categoryAllocation = allocate(order, categoryGroups, categoryGroups, problem, mode, true);
            accumulate(products, productGroups, productAllocation, selectedLine);
            String selectedCategoryStatus = accumulateCategories(categories, categoryGroups,
                    categoryAllocation, productAllocation, selectedLine);
            String month = YearMonth.from(order.orderDate().toInstant(ZoneOffset.UTC)
                    .atZone(BiBusinessTime.ZONE)).toString();
            accumulate(months.computeIfAbsent(month, ignored -> new LinkedHashMap<>()),
                    productGroups, productAllocation, selectedLine);
            List<FactRow> selected = lines.stream().filter(selectedLine).toList();
            BigDecimal selectedReceivable = assigned(productGroups, productAllocation.receivables(), selectedLine, true);
            BigDecimal selectedPaid = assigned(productGroups, productAllocation.payments(), selectedLine, true);
            BigDecimal excludedPaid = assigned(productGroups, productAllocation.payments(), selectedLine, false);
            BigDecimal allocated = productAllocation.status().equals("PROPORTIONAL") ? selectedPaid : null;
            BigDecimal unallocated = order.paid().subtract(z(selectedPaid)).subtract(z(excludedPaid));
            traces.add(new OrderTrace(id(order.orderId()), order.orderNo(), order.sourceOrderNo(), order.sourceSystemCode(),
                    order.regionCode(), order.regionName(), order.ownerStaffCode(), id(order.customerId()),
                    order.orderDate().toInstant(ZoneOffset.UTC), order.lineCount(), selected.size(), order.payable(),
                    order.lineTotal(), sum(selected, FactRow::salesAmount), order.payable().subtract(order.lineTotal()),
                    order.paid(), order.unpaid(), selectedReceivable, selectedPaid, allocated, z(excludedPaid), unallocated,
                    productAllocation.status(), selectedCategoryStatus, order.customerName(), order.ownerStaffName()));
            if (products.size() > MAX_REPORT_ROWS || categories.size() > MAX_REPORT_ROWS
                    || months.values().stream().mapToInt(Map::size).sum() > MAX_REPORT_ROWS) {
                return blocked(filter, generatedAt, mode);
            }
        }
        List<Row> rows = products.values().stream().map(Totals::view).toList();
        List<Row> categoryRows = categories.values().stream().map(Totals::view).toList();
        return new CityProductReportView(filter.from(), filter.to(), generatedAt, dataUpdatedAt, mode.name(),
                rows, categoryRows, List.copyOf(traces), summary(rows, categoryRows, traces), false, false, definitions(),
                months.entrySet().stream().flatMap(entry -> entry.getValue().values().stream()
                        .map(total -> new MonthlyRow(entry.getKey(), total.view()))).toList(),
                store.customerArchives(tenantId, filter));
    }

    private static Allocation allocate(FactRow order, Map<Key, List<FactRow>> groups,
                                       Map<Key, List<FactRow>> categories, String problem, Mode mode, boolean category) {
        if (problem != null) return new Allocation(problem, Map.of(), Map.of());
        if (groups.keySet().stream().anyMatch(key -> key.category().startsWith("missing:")
                || (!category && (key.product().startsWith("missing:") || !real(key.unit()))))) {
            return new Allocation("UNKNOWN_DIMENSION", Map.of(), Map.of());
        }
        if (groups.size() == 1) {
            Key only = groups.keySet().iterator().next();
            return new Allocation("EXACT", Map.of(only, order.payable()), Map.of(only, order.paid()));
        }
        if (mode == Mode.EXACT_ONLY) return new Allocation("MIXED_ITEMS", Map.of(), Map.of());
        Map<Key, BigDecimal> categoryReceivables = distribute(order.payable(), weights(categories), null);
        Map<Key, BigDecimal> categoryPayments = distribute(order.paid(), weights(categories), categoryReceivables);
        if (category) return new Allocation("PROPORTIONAL", categoryReceivables, categoryPayments);
        Map<Key, BigDecimal> receivables = new LinkedHashMap<>();
        Map<Key, BigDecimal> payments = new LinkedHashMap<>();
        // 应收与回款先使用整单分类权重，再分到SKU/单位；单位不影响分类金额能否精确归属。
        categories.forEach((categoryKey, lines) -> {
            Map<Key, BigDecimal> children = weights(group(lines, row -> key(row, false)));
            Map<Key, BigDecimal> childReceivables = distribute(categoryReceivables.get(categoryKey), children, null);
            receivables.putAll(childReceivables);
            payments.putAll(distribute(categoryPayments.get(categoryKey), children, childReceivables));
        });
        return new Allocation("PROPORTIONAL", receivables, payments);
    }

    private static Map<Key, BigDecimal> weights(Map<Key, List<FactRow>> groups) {
        Map<Key, BigDecimal> result = new LinkedHashMap<>();
        groups.forEach((key, rows) -> result.put(key, sum(rows, FactRow::salesAmount)));
        return result;
    }

    /** 六位小数最大余数法；回款尾差受已分应收上限约束，避免舍入后出现单品超收。 */
    private static Map<Key, BigDecimal> distribute(BigDecimal total, Map<Key, BigDecimal> weights,
                                                  Map<Key, BigDecimal> caps) {
        BigDecimal denominator = weights.values().stream().reduce(ZERO, BigDecimal::add);
        Map<Key, BigDecimal> amounts = new LinkedHashMap<>();
        Map<Key, BigDecimal> remainders = new LinkedHashMap<>();
        weights.forEach((key, weight) -> {
            BigDecimal amount = denominator.signum() == 0 ? ZERO : total.multiply(weight).divide(denominator, 6, RoundingMode.DOWN);
            amounts.put(key, amount);
            remainders.put(key, total.multiply(weight).subtract(amount.multiply(denominator)));
        });
        int remaining = total.subtract(amounts.values().stream().reduce(ZERO, BigDecimal::add))
                .divide(QUANTUM).intValueExact();
        List<Key> order = new ArrayList<>(weights.keySet());
        order.sort(Comparator.<Key, BigDecimal>comparing(remainders::get).reversed().thenComparing(Key::stableOrder));
        while (remaining > 0) {
            boolean progressed = false;
            for (Key key : order) {
                BigDecimal next = amounts.get(key).add(QUANTUM);
                if (weights.get(key).signum() > 0 && (caps == null || next.compareTo(caps.get(key)) <= 0)) {
                    amounts.put(key, next);
                    progressed = true;
                    if (--remaining == 0) break;
                }
            }
            if (!progressed) throw new IllegalStateException("回款分配尾差超过应收上限");
        }
        return amounts;
    }

    private static String problem(FactRow order, List<FactRow> lines) {
        if (lines.isEmpty()) return "MISSING_LINES";
        if (lines.stream().anyMatch(row -> row.salesAmount() == null || row.quantity() == null
                || row.salesNetAmount() == null || row.refundAmount() == null)) return "INVALID_LINE";
        if (lines.stream().anyMatch(row -> row.refundAmount().signum() != 0)) return "REFUND_REVIEW";
        if (lines.stream().anyMatch(row -> row.salesAmount().signum() < 0 || row.quantity().signum() < 0
                || row.salesNetAmount().signum() < 0)) return "NEGATIVE_LINE";
        if (order.payable().signum() < 0 || order.paid().signum() < 0 || order.unpaid().signum() < 0
                || order.paid().add(order.unpaid()).compareTo(order.payable()) != 0) return "ORDER_BALANCE_REVIEW";
        if (order.lineTotal().signum() <= 0) return "ZERO_DENOMINATOR";
        if (order.payable().compareTo(order.lineTotal()) > 0) return "EXTRA_CHARGE_OR_MISSING_LINES";
        if (sum(lines, FactRow::salesAmount).compareTo(order.lineTotal()) != 0
                || lines.stream().anyMatch(row -> row.salesNetAmount().compareTo(row.salesAmount()) != 0)) return "LINE_TOTAL_REVIEW";
        if (!real(order.regionCode())) return "UNKNOWN_DIMENSION";
        return null;
    }

    private static void accumulate(Map<Key, Totals> totals, Map<Key, List<FactRow>> groups,
                                   Allocation allocation, Predicate<FactRow> selected) {
        groups.forEach((key, lines) -> {
            if (!selected.test(lines.getFirst())) return;
            Totals aggregate = totals.computeIfAbsent(key, ignored -> new Totals(lines.getFirst(), key.product() == null));
            lines.forEach(aggregate::add);
            BigDecimal paid = allocation.payments().get(key);
            if (paid == null) aggregate.unallocated.add(lines.getFirst().orderId());
            else {
                aggregate.receivable = plus(aggregate.receivable, allocation.receivables().get(key));
                aggregate.paid = plus(aggregate.paid, paid);
                if (allocation.status().equals("PROPORTIONAL")) aggregate.allocated = plus(aggregate.allocated, paid);
            }
        });
    }

    private static BigDecimal assigned(Map<Key, List<FactRow>> groups, Map<Key, BigDecimal> amounts,
                                       Predicate<FactRow> selected, boolean matching) {
        BigDecimal sum = null;
        for (var group : groups.entrySet()) {
            if (selected.test(group.getValue().getFirst()) == matching && amounts.containsKey(group.getKey())) {
                sum = plus(sum, amounts.get(group.getKey()));
            }
        }
        return sum;
    }

    /** 品牌或SKU只选中分类的一部分时，不能把整分类回款复制到所选子集。 */
    private static String accumulateCategories(Map<Key, Totals> totals, Map<Key, List<FactRow>> groups,
            Allocation categoryAllocation, Allocation productAllocation, Predicate<FactRow> selected) {
        boolean pending = false;
        boolean proportional = false;
        for (var entry : groups.entrySet()) {
            List<FactRow> selectedRows = entry.getValue().stream().filter(selected).toList();
            if (selectedRows.isEmpty()) continue;
            Totals aggregate = totals.computeIfAbsent(entry.getKey(), ignored -> new Totals(selectedRows.getFirst(), true));
            selectedRows.forEach(aggregate::add);
            BigDecimal paid;
            BigDecimal receivable;
            String status;
            if (selectedRows.size() == entry.getValue().size()) {
                paid = categoryAllocation.payments().get(entry.getKey());
                receivable = categoryAllocation.receivables().get(entry.getKey());
                status = categoryAllocation.status();
            } else {
                var children = group(selectedRows, row -> key(row, false));
                boolean complete = children.keySet().stream().allMatch(productAllocation.payments()::containsKey);
                paid = complete ? assigned(children, productAllocation.payments(), row -> true, true) : null;
                receivable = complete ? assigned(children, productAllocation.receivables(), row -> true, true) : null;
                status = productAllocation.status();
            }
            if (paid == null) {
                aggregate.unallocated.add(selectedRows.getFirst().orderId());
                pending = true;
            } else {
                aggregate.paid = plus(aggregate.paid, paid);
                aggregate.receivable = plus(aggregate.receivable, receivable);
                if ("PROPORTIONAL".equals(status)) {
                    aggregate.allocated = plus(aggregate.allocated, paid);
                    proportional = true;
                }
            }
        }
        return pending ? "MIXED_ITEMS" : proportional ? "PROPORTIONAL" : categoryAllocation.status();
    }

    private static Summary summary(List<Row> rows, List<Row> categories, List<OrderTrace> traces) {
        Set<String> customers = new HashSet<>();
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        rows.forEach(row -> quantities.merge(row.unitCode(), row.quantity(), BigDecimal::add));
        traces.stream().map(OrderTrace::customerId).filter(Objects::nonNull).forEach(customers::add);
        return new Summary(traces.size(), customers.size(), traces.stream().filter(trace -> !resolved(trace.status())).count(),
                nullableSum(categories, Row::paidAmount), traces.stream().filter(trace -> !resolved(trace.categoryStatus())).count(),
                quantities.entrySet().stream().map(entry -> new UnitQuantity(entry.getKey(), entry.getValue())).toList(),
                sum(rows, Row::salesAmount), sum(rows, Row::salesNetAmount), sum(rows, Row::refundAmount),
                sum(traces, OrderTrace::total), sum(traces, OrderTrace::paid), sum(traces, OrderTrace::unpaid),
                nullableSum(rows, Row::receivableAmount), nullableSum(rows, Row::paidAmount), nullableSum(rows, Row::allocatedPaidAmount),
                sum(traces, OrderTrace::excludedPaidAmount), sum(traces, OrderTrace::unallocatedPaidAmount));
    }

    private static Key key(FactRow row, boolean category) {
        return new Key(row.regionCode(), identity(row.categoryId(), row.categoryCode(), row.lineId()),
                category ? null : identity(row.productId(), row.productCode(), row.lineId()),
                category ? null : identity(row.skuId(), row.skuCode(), null), category ? null : row.unitCode(),
                category ? null : id(row.brandId()));
    }

    private static String identity(Long id, String code, Long missingId) {
        return id != null ? "id:" + id : real(code) ? "code:" + code : "missing:" + missingId;
    }

    private static boolean real(String value) {
        return value != null && !value.isBlank() && !value.equalsIgnoreCase("UNKNOWN") && !value.equalsIgnoreCase("MULTI");
    }

    private static boolean resolved(String status) { return status.equals("EXACT") || status.equals("PROPORTIONAL"); }
    private static String id(Long value) { return value == null ? null : value.toString(); }
    private static BigDecimal z(BigDecimal value) { return value == null ? ZERO : value; }
    private static BigDecimal plus(BigDecimal left, BigDecimal right) { return z(left).add(right); }

    private static <T> BigDecimal sum(List<T> values, Function<T, BigDecimal> getter) {
        return values.stream().map(getter).map(CityProductReportService::z).reduce(ZERO, BigDecimal::add);
    }

    private static <T> BigDecimal nullableSum(List<T> values, Function<T, BigDecimal> getter) {
        return values.stream().map(getter).filter(Objects::nonNull).reduce(BigDecimal::add).orElse(null);
    }

    private static <K, V> Map<K, List<V>> group(List<V> values, Function<V, K> key) {
        Map<K, List<V>> result = new LinkedHashMap<>();
        values.forEach(value -> result.computeIfAbsent(key.apply(value), ignored -> new ArrayList<>()).add(value));
        return result;
    }

    private static String text(String value, int max, String name) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static String code(String value, String name) {
        String normalized = text(value, 64, name);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static BusinessException badRequest(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of()); }

    private static CityProductReportView blocked(SupplyDashboardFilter filter, Instant generatedAt, Mode mode) {
        return new CityProductReportView(filter.from(), filter.to(), generatedAt, null, mode.name(),
                List.of(), List.of(), List.of(), null, true, true, definitions(), List.of(), List.of());
    }

    private static List<String> definitions() {
        return List.of(
                "monthlyRows按Asia/Shanghai订单月份分组，沿用整单归属结果；不是该月现金入账。customerArchives为当前未删除客户档案，城市/销售/类型与请求一致，不按订单日期、分类、来源筛选，不等于本期下单客户。",
                "日期为UTC订单日期闭区间，回款为同批订单bi_sales_order_fact.paid_amount累计值，不是期间回款流水或商品核销。",
                "quantity及quantities为原始订货数量，未扣退货数量，不是净出库销量。",
                "salesAmount为商品行销售额；salesNetAmount为行金额扣退款后的金额，未减整单优惠，与已分配订单净应收receivableAmount不同。refundAmount沿用BI订单退款按行分摊快照，不表示商品实际退款。",
                "receivableAmount为已归属订单净应收；paidAmount包含allocatedPaidAmount，后者仅为其中比例估算部分，严禁再次相加。",
                "EXACT表示整订单唯一归入该输出粒度，不代表存在商品付款核销凭证；分类可精确不代表其下每个SKU都可精确。",
                "订单应收低于整单行合计的差额按订单整单优惠模型处理；PROPORTIONAL先按整单分类行金额分配净应收和回款，再在组内分到SKU/单位。",
                "金额分配保留6位小数，最大余数法确定尾差；回款不超过已分应收。分类、品牌、商品和SKU筛选在分配后相交，不改变整单分母。选中部分分类时仅汇总所选商品的已归属回款，不能使用整分类回款。",
                "退款、负数、零分母、缺行、应收高于行合计、收付不平和未知维度均待确认，不猜运费、不强行分摊。",
                "rows按城市/分类/商品/SKU/单位，categoryRows按城市/分类且金额不按单位拆分；多单位分类quantity与unitCode为null，数量看quantities逐单位展示，不折箱不混加。",
                "rows与categoryRows是独立汇总视图，不能相加；订单和客户数在各粒度独立去重，前端不得跨行合计去重数。",
                "summary.paidAmount/unallocatedOrderCount是商品粒度；categoryPaidAmount/unallocatedCategoryOrderCount是分类粒度，同分类多SKU订单在EXACT_ONLY下两者可能不同。",
                "summary.orderPaidAmount = paidAmount(空按0) + excludedPaidAmount + unallocatedPaidAmount；订单总金额包含所选分类之外的行，不能当作该分类金额。",
                "回款或应收为null表示无可归属值；存在unallocatedOrderCount时已归属金额只是部分值，不能称为总回款。",
                "generatedAt为生成时间；dataUpdatedAt为本批订单/行最近同步时间，不代表所有来源同步完成水位。",
                "最多10000个订单、50000条源结果、每种汇总10000行；超限truncated/exportBlocked=true，rows/categoryRows/orderTrace为空且summary=null，请缩小范围。");
    }

    private enum Mode { EXACT_ONLY, PROPORTIONAL }
    private record Allocation(String status, Map<Key, BigDecimal> receivables, Map<Key, BigDecimal> payments) { }
    private record Key(String region, String category, String product, String sku, String unit, String brand) {
        String stableOrder() { return toString(); }
    }

    private static final class Totals {
        private final FactRow first;
        private final boolean category;
        private final Set<Long> orders = new HashSet<>();
        private final Set<Long> customers = new HashSet<>();
        private final Set<Long> unallocated = new HashSet<>();
        private final Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        private BigDecimal quantity = ZERO;
        private BigDecimal sales = ZERO;
        private BigDecimal net = ZERO;
        private BigDecimal refund = ZERO;
        private BigDecimal receivable;
        private BigDecimal paid;
        private BigDecimal allocated;

        Totals(FactRow first, boolean category) { this.first = first; this.category = category; }

        void add(FactRow row) {
            orders.add(row.orderId());
            if (row.customerId() != null) customers.add(row.customerId());
            quantity = quantity.add(z(row.quantity()));
            quantities.merge(row.unitCode(), z(row.quantity()), BigDecimal::add);
            sales = sales.add(z(row.salesAmount()));
            net = net.add(z(row.salesNetAmount()));
            refund = refund.add(z(row.refundAmount()));
        }

        Row view() {
            String status = paid == null ? "UNALLOCATED" : !unallocated.isEmpty() ? "PARTIAL"
                    : allocated != null ? "PROPORTIONAL" : "EXACT";
            return new Row(first.regionCode(), first.regionName(), id(first.categoryId()), first.categoryCode(), first.categoryName(),
                    category ? null : id(first.productId()), category ? null : first.productCode(), category ? null : first.productName(),
                    category ? null : id(first.skuId()), category ? null : first.skuCode(),
                    quantities.size() == 1 ? first.unitCode() : null, quantities.size() == 1 ? quantity : null,
                    quantities.entrySet().stream().map(entry -> new UnitQuantity(entry.getKey(), entry.getValue())).toList(), sales, net, refund,
                    receivable, paid, allocated, status, unallocated.size(), orders.size(), customers.size(),
                    category ? null : first.specification(), category ? null : id(first.brandId()),
                    category ? null : first.brandName());
        }
    }
}
