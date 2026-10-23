package com.rigour.analytics.domain.cost;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** 无框架计算内核：同一授权集合支持全部图表与明细核对，金额只使用BigDecimal。 */
public final class CostAnalysis {
    private CostAnalysis() {}

    public static final Map<String, String> CATEGORIES;

    static {
        Map<String, String> categories = new LinkedHashMap<>();
        categories.put("warehouse", "仓储费用");
        categories.put("marketing", "营销费用");
        categories.put("office", "办公费用");
        categories.put("freight", "运费");
        categories.put("labor", "人力薪酬");
        categories.put("expense", "员工报销");
        categories.put("premises_unknown", "待分类场地费用");
        categories.put("purchase", "商品采购");
        categories.put("loss", "商品损耗");
        CATEGORIES = Collections.unmodifiableMap(categories);
    }

    public static final Map<String, List<String>> DIMENSIONS =
            Map.of(
                    "freight",
                    List.of("leg", "route", "carrier", "method", "type", "employee"),
                    "warehouse",
                    List.of("premises", "contract"),
                    "office",
                    List.of("premises", "contract", "type", "employee"),
                    "labor",
                    List.of("employee", "department"),
                    "marketing",
                    List.of("type", "channel", "employee", "payee"),
                    "expense",
                    List.of("type", "employee", "department", "trip", "destination", "payee"),
                    "purchase",
                    List.of("supplier", "product", "warehouse"),
                    "loss",
                    List.of("product", "lossReason", "warehouse"),
                    "premises_unknown",
                    List.of("premises", "contract"));
    public static final Map<String, String> DIMENSION_LABELS =
            Map.ofEntries(
                    Map.entry("leg", "运输环节"),
                    Map.entry("carrier", "承运方"),
                    Map.entry("method", "运输方式"),
                    Map.entry("premises", "仓库或场地"),
                    Map.entry("contract", "合同编号"),
                    Map.entry("type", "费用类型"),
                    Map.entry("channel", "渠道"),
                    Map.entry("employee", "员工"),
                    Map.entry("department", "部门"),
                    Map.entry("supplier", "供应商"),
                    Map.entry("product", "商品规格"),
                    Map.entry("lossReason", "损耗原因"),
                    Map.entry("route", "运输路线"),
                    Map.entry("warehouse", "仓库或发生地点"),
                    Map.entry("payee", "收款方或合作方"),
                    Map.entry("trip", "出差或报销事项"),
                    Map.entry("destination", "出差目的地"));

    public record Filter(
            int year,
            int month,
            String compare,
            String city,
            String scope,
            String category,
            String dimension,
            String dimensionKey,
            String status,
            String payment,
            String search,
            String side,
            int page,
            int size,
            boolean missingAmount) {
        public Filter {
            if (!Set.of("opex", "purchase", "loss").contains(scope))
                throw new IllegalArgumentException("未知统计范围");
            if (category != null
                    && (!CATEGORIES.containsKey(category)
                            || !scope.equals(
                                    Set.of("purchase", "loss").contains(category)
                                            ? category
                                            : "opex")))
                throw new IllegalArgumentException("费用类别与范围不兼容");
            if (dimension != null
                    && (category == null || !DIMENSIONS.get(category).contains(dimension)))
                throw new IllegalArgumentException("专题与分析维度不兼容");
            if (dimensionKey != null && dimension == null)
                throw new IllegalArgumentException("对象筛选缺少维度");
            if (!Set.of("ALL", "CONFIRMED", "DRAFT", "VOID").contains(status)
                    || !Set.of("ALL", "PAID", "UNPAID", "UNKNOWN").contains(payment))
                throw new IllegalArgumentException("未知费用状态");
            if (!Set.of("current", "previous").contains(side)
                    || page < 1
                    || !Set.of(20, 50, 100).contains(size))
                throw new IllegalArgumentException("分页或期间参数无效");
            if (search != null && search.length() > 120
                    || dimensionKey != null && dimensionKey.length() > 500)
                throw new IllegalArgumentException("筛选内容超长");
        }

        public boolean matches(CostFact r) {
            return scope.equals(r.scope())
                    && (city == null || city.equals(r.city()))
                    && (category == null || category.equals(r.category()))
                    && (dimensionKey == null || dimensionKey.equals(r.dimension(dimension).key()));
        }

        public boolean ledger(CostFact r) {
            return matches(r)
                    && ("ALL".equals(status) || status.equals(r.status()))
                    && ("ALL".equals(payment) || payment.equals(r.payment()))
                    && (!missingAmount || r.amount() == null && !"VOID".equals(r.status()))
                    && (search == null
                            || search.isBlank()
                            || searchable(r).contains(search.toLowerCase(Locale.ROOT)));
        }
    }

    private static String searchable(CostFact r) {
        return String.join(
                        " ",
                        Objects.toString(r.documentNo(), ""),
                        Objects.toString(r.title(), ""),
                        Objects.toString(r.employeeName(), ""),
                        String.join(" ", r.originalNumbers()))
                .toLowerCase(Locale.ROOT);
    }

    public static List<CostFact> period(
            List<CostFact> rows,
            Filter filter,
            CostPeriod period,
            boolean previous,
            boolean ledger) {
        return rows.stream()
                .filter(r -> r.inPeriod(period, previous))
                .filter(ledger ? filter::ledger : filter::matches)
                .toList();
    }

    public static BigDecimal amount(List<CostFact> rows) {
        return rows.stream()
                .filter(CostFact::confirmed)
                .map(CostFact::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal sum(
            List<CostFact> rows, java.util.function.Predicate<CostFact> condition) {
        return rows.stream()
                .filter(condition)
                .map(CostFact::amount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static long count(List<CostFact> rows) {
        return rows.stream().filter(CostFact::confirmed).count();
    }

    public static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String ratio(BigDecimal part, BigDecimal base) {
        return base.signum() == 0
                ? null
                : part.multiply(BigDecimal.valueOf(100))
                        .divide(base, 1, RoundingMode.HALF_UP)
                        .toPlainString();
    }

    public record Bucket(
            String key,
            String label,
            BigDecimal current,
            BigDecimal previous,
            long count,
            boolean missing) {}

    public static List<Bucket> groups(
            List<CostFact> current,
            List<CostFact> previous,
            Function<CostFact, String> key,
            Function<CostFact, String> label) {
        Map<String, List<CostFact>> left = group(current, key), right = group(previous, key);
        Set<String> keys = new TreeSet<>(left.keySet());
        keys.addAll(right.keySet());
        List<Bucket> result = new ArrayList<>();
        for (String k : keys) {
            var a = left.getOrDefault(k, List.of());
            var b = right.getOrDefault(k, List.of());
            var sample = a.isEmpty() ? b.getFirst() : a.getFirst();
            result.add(
                    new Bucket(
                            k,
                            label.apply(sample),
                            amount(a),
                            amount(b),
                            count(a),
                            "UNKNOWN".equals(k)));
        }
        return result.stream()
                .sorted(Comparator.comparing(Bucket::current).reversed().thenComparing(Bucket::key))
                .toList();
    }

    private static Map<String, List<CostFact>> group(
            List<CostFact> rows, Function<CostFact, String> key) {
        return rows.stream()
                .filter(CostFact::confirmed)
                .collect(java.util.stream.Collectors.groupingBy(key));
    }

    public static List<CostFact> sorted(List<CostFact> rows) {
        return rows.stream()
                .sorted(
                        Comparator.comparing(CostFact::date)
                                .reversed()
                                .thenComparing(CostFact::source)
                                .thenComparing(CostFact::sourceId))
                .toList();
    }
}
