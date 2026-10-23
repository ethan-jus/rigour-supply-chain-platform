package com.rigour.analytics.application.service;

import static com.rigour.analytics.domain.cost.CostAnalysis.*;

import com.rigour.analytics.api.v1.model.CostDashboardCell;
import com.rigour.analytics.api.v1.model.CostDashboardChange;
import com.rigour.analytics.api.v1.model.CostDashboardChoice;
import com.rigour.analytics.api.v1.model.CostDashboardDetail;
import com.rigour.analytics.api.v1.model.CostDashboardGroup;
import com.rigour.analytics.api.v1.model.CostDashboardMeta;
import com.rigour.analytics.api.v1.model.CostDashboardMetric;
import com.rigour.analytics.api.v1.model.CostDashboardMonth;
import com.rigour.analytics.api.v1.model.CostDashboardPayrollComponent;
import com.rigour.analytics.api.v1.model.CostDashboardPeriod;
import com.rigour.analytics.api.v1.model.CostDashboardPoint;
import com.rigour.analytics.api.v1.model.CostDashboardQuery;
import com.rigour.analytics.api.v1.model.CostDashboardRecordSummary;
import com.rigour.analytics.api.v1.model.CostDashboardRecordView;
import com.rigour.analytics.api.v1.model.CostDashboardRecords;
import com.rigour.analytics.api.v1.model.CostDashboardReport;
import com.rigour.analytics.api.v1.model.CostDashboardWorkspace;
import com.rigour.analytics.application.port.out.CostDashboardStore;
import com.rigour.analytics.application.port.out.CostSourceDetail;
import com.rigour.analytics.domain.cost.*;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.*;
import java.time.*;
import java.util.*;

/** 页面只消费已发布批次；服务内先授权，再计算，同批次用于图表、明细及导出。 */
@Service
public class CostDashboardService {
    private static final Logger log = LoggerFactory.getLogger(CostDashboardService.class);
    private final CostDashboardStore store;
    private final CostDashboardRefreshService refresh;
    private final CostDashboardAccess access;
    private final CostSourceDetail sourceDetail;
    private final boolean enabled;
    private final Clock clock;

    public CostDashboardService(
            CostDashboardStore store,
            CostDashboardRefreshService refresh,
            CostDashboardAccess access,
            CostSourceDetail sourceDetail,
            @Value("${rigour.analytics.cost-dashboard.enabled:true}") boolean enabled,
            Clock analyticsClock) {
        this.store = store;
        this.refresh = refresh;
        this.access = access;
        this.sourceDetail = sourceDetail;
        this.enabled = enabled;
        this.clock = analyticsClock;
    }

    public record Context(
            CostDashboardStore.Snapshot snapshot,
            CostDashboardAccess.Access access,
            Filter filter,
            CostPeriod period,
            List<CostFact> rows,
            CostDashboardMeta meta) {}

    public Context context(CostDashboardQuery q) {
        if (!enabled) throw new CostDashboardFailure("COST_DASHBOARD_DISABLED", 503, "成本看板尚未开放");
        var a = access.current();
        String tenant = a.actor().tenantId().toString();
        var state = store.state(tenant);
        if (blank(q.snapshotId()) == null || state == null || state.snapshotId() == null) {
            // Opening/reloading the board checks source versions. Drill-downs remain pinned to
            // their complete snapshot; a failed refresh may only fall back to an existing one.
            try {
                refresh.refreshTenant(tenant);
            } catch (CostDashboardFailure failure) {
                if (state == null || state.snapshotId() == null) throw failure;
            }
            state = store.state(tenant);
        }
        if (state == null || state.snapshotId() == null)
            throw new CostDashboardFailure("COST_SOURCE_UNAVAILABLE", 503, "费用数据正在准备，请稍后刷新");
        String id = blank(q.snapshotId()) == null ? state.snapshotId() : q.snapshotId();
        var snapshot = store.snapshot(tenant, id);
        if (!"6".equals(snapshot.versions().get("BI_COST_NORMALIZATION"))) {
            if (blank(q.snapshotId()) != null) throw CostDashboardFailure.expired();
            refresh.refreshTenant(tenant);
            state = store.state(tenant);
            snapshot = store.snapshot(tenant, state.snapshotId());
            id = snapshot.id();
            if (!"6".equals(snapshot.versions().get("BI_COST_NORMALIZATION")))
                throw new CostDashboardFailure("COST_SOURCE_UNAVAILABLE", 503, "六类成本口径正在准备，请稍后刷新");
        }
        Filter f = filter(q, snapshot.cutoff());
        CostPeriod p = CostPeriod.resolve(f.year(), f.month(), f.compare(), snapshot.cutoff());
        var latest =
                id.equals(state.snapshotId())
                        ? snapshot
                        : store.snapshot(tenant, state.snapshotId());
        // 旧快照不可恢复最新已撤销的数据归属；在历史批次上也与当前可见源记录求交集。
        Set<String> currentVisible =
                latest.records().stream()
                        .filter(a::visible)
                        .map(CostFact::key)
                        .collect(java.util.stream.Collectors.toSet());
        List<CostFact> rows =
                snapshot.records().stream()
                        .filter(a::visible)
                        .filter(r -> currentVisible.contains(r.key()))
                        .toList();
        if (f.category() != null && !a.categories().contains(f.category()))
            throw new AuthorizationDeniedException("cost-category-scope");
        if (f.city() != null
                && !cityChoices(snapshot, a, rows).stream().anyMatch(c -> f.city().equals(c.key())))
            throw new AuthorizationDeniedException("cost-city-scope");
        boolean comparison =
                rows.stream().anyMatch(r -> f.matches(r) && r.inPeriod(p, true) && r.confirmed());
        var excluded =
                CATEGORIES.keySet().stream()
                        .filter(k -> !a.sources().containsKey(k))
                        .map(CATEGORIES::get)
                        .toList();
        var included =
                a.sources().keySet().stream()
                        .map(
                                category ->
                                        Set.of("expense", "labor").contains(category)
                                                ? "HR"
                                                : "freight".equals(category) ? "ORDER" : "ERP")
                        .distinct()
                        .sorted()
                        .toList();
        String dataState =
                "FAILED".equals(state.status()) || state.succeededAt() == null ? "STALE" : "READY";
        CostDashboardMeta meta =
                new CostDashboardMeta(
                        "cost-v9",
                        id,
                        snapshot.generatedAt(),
                        snapshot.cutoff(),
                        new CostDashboardPeriod(
                                p.from(), p.to(), p.previousFrom(), p.previousTo(), p.compare()),
                        included,
                        excluded,
                        comparison,
                        comparison ? null : "无可比期确认记录",
                        dataState,
                        a.exportAllowed(),
                        CATEGORIES.keySet().stream().filter(a.categories()::contains).toList());
        return new Context(snapshot, a, f, p, rows, meta);
    }

    public CostDashboardReport overview(CostDashboardQuery query) {
        return overview(context(query));
    }

    public CostDashboardWorkspace workspace(CostDashboardQuery query) {
        var c = context(query);
        return new CostDashboardWorkspace(overview(c), records(c));
    }

    private CostDashboardReport overview(Context c) {
        long start = System.nanoTime();
        var f = c.filter();
        var p = c.period();
        var cur = period(c.rows(), f, p, false, false);
        var prev = period(c.rows(), f, p, true, false);
        BigDecimal total = amount(cur), before = amount(prev);
        boolean comparable = c.meta().comparisonAvailable();
        var categories = groups(cur, prev, CostFact::category, r -> CATEGORIES.get(r.category()));
        var cities = groups(cur, prev, CostFact::city, CostFact::cityName);
        Map<String, BigDecimal> cityTotals = new HashMap<>();
        cities.forEach(g -> cityTotals.put(g.key(), g.current()));
        var cells = groups(cur, prev, r -> r.city() + "\u001f" + r.category(), r -> r.cityName());
        BigDecimal increased =
                cells.stream()
                        .map(g -> g.current().subtract(g.previous()))
                        .filter(d -> d.signum() > 0)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal decreased =
                cells.stream()
                        .map(g -> g.current().subtract(g.previous()))
                        .filter(d -> d.signum() < 0)
                        .map(BigDecimal::abs)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CostDashboardCell> matrix = new ArrayList<>();
        List<CostDashboardChange> changes = new ArrayList<>();
        for (var g : cells) {
            String[] keys = g.key().split("\u001f");
            BigDecimal d = g.current().subtract(g.previous());
            matrix.add(
                    new CostDashboardCell(
                            keys[0],
                            g.label(),
                            keys[1],
                            money(g.current()),
                            comparable ? money(g.previous()) : null,
                            comparable ? money(d) : null,
                            ratio(g.current(), cityTotals.get(keys[0])),
                            g.count()));
            if (comparable && d.signum() != 0)
                changes.add(
                        new CostDashboardChange(
                                keys[0],
                                g.label(),
                                keys[1],
                                money(g.current()),
                                money(g.previous()),
                                money(d),
                                ratio(d.abs(), d.signum() > 0 ? increased : decreased),
                                d.signum() > 0 ? "increase" : "decrease"));
        }
        changes.sort(
                Comparator.<CostDashboardChange, BigDecimal>comparing(
                                x -> new BigDecimal(x.delta()).abs())
                        .reversed()
                        .thenComparing(CostDashboardChange::city)
                        .thenComparing(CostDashboardChange::category));
        String dimension = f.dimension();
        List<CostDashboardGroup> objects =
                dimension == null
                        ? List.of()
                        : groups(
                                        cur,
                                        prev,
                                        r -> r.dimension(dimension).key(),
                                        r -> r.dimension(dimension).label())
                                .stream()
                                .map(g -> view(g, total, comparable))
                                .toList();
        BigDecimal covered =
                dimension == null
                        ? BigDecimal.ZERO
                        : sum(cur, r -> r.confirmed() && !r.dimension(dimension).missing());
        var dims =
                f.category() == null
                        ? List.<CostDashboardChoice>of()
                        : DIMENSIONS.get(f.category()).stream()
                                .map(key -> new CostDashboardChoice(key, DIMENSION_LABELS.get(key)))
                                .toList();
        BigDecimal purchase = scopeTotal(c, "purchase"), loss = scopeTotal(c, "loss");
        var result =
                new CostDashboardReport(
                        c.meta(),
                        f.scope(),
                        f.category(),
                        dimension,
                        metric(cur),
                        metric(prev),
                        comparable ? money(total.subtract(before)) : null,
                        comparable ? ratio(total.subtract(before), before) : null,
                        money(purchase),
                        money(loss),
                        comparable ? money(increased) : null,
                        comparable ? money(decreased) : null,
                        cityChoices(c.snapshot(), c.access(), c.rows()),
                        categories.stream().map(g -> view(g, total, comparable)).toList(),
                        cities.stream().map(g -> view(g, total, comparable)).toList(),
                        matrix,
                        changes,
                        objects,
                        ratio(covered, total),
                        dims,
                        cumulative(cur, prev, p, f.month() == 0, comparable),
                        months(c),
                        cur.stream()
                                .filter(CostFact::confirmed)
                                .sorted(
                                        Comparator.comparing(CostFact::amount)
                                                .reversed()
                                                .thenComparing(CostFact::key))
                                .limit(8)
                                .map(CostDashboardService::record)
                                .toList(),
                        c.access().categories().contains("labor")
                                ? payrollComponents(cur, prev)
                                : List.of(),
                        money(sum(cur, r -> r.confirmed() && r.monthly())),
                        money(sum(prev, r -> r.confirmed() && r.monthly())));
        log.info(
                "cost_dashboard_query tenant={} actor={} snapshot={} year={} month={} scope={}"
                        + " city={} rows={} elapsedMs={}",
                c.snapshot().tenant(),
                c.access().actor().principalId(),
                c.snapshot().id(),
                f.year(),
                f.month(),
                f.scope(),
                f.city(),
                cur.size(),
                (System.nanoTime() - start) / 1_000_000);
        return result;
    }

    public CostDashboardRecords records(CostDashboardQuery query) {
        return records(context(query));
    }

    public CostDashboardRecords records(Context c) {
        var rows = matching(c);
        int offset =
                Math.min(
                        rows.size(),
                        Math.toIntExact(
                                Math.min(
                                        Integer.MAX_VALUE,
                                        (long) (c.filter().page() - 1) * c.filter().size())));
        log.info(
                "cost_dashboard_records tenant={} actor={} snapshot={} year={} month={} scope={}"
                        + " city={} category={} side={} status={} payment={} page={} size={}"
                        + " matched={}",
                c.snapshot().tenant(),
                c.access().actor().principalId(),
                c.snapshot().id(),
                c.filter().year(),
                c.filter().month(),
                c.filter().scope(),
                c.filter().city(),
                c.filter().category(),
                c.filter().side(),
                c.filter().status(),
                c.filter().payment(),
                c.filter().page(),
                c.filter().size(),
                rows.size());
        return new CostDashboardRecords(
                c.meta(),
                new CostDashboardRecordSummary(
                        rows.size(),
                        money(amount(rows)),
                        money(sum(rows, r -> "DRAFT".equals(r.status()))),
                        rows.stream()
                                .filter(r -> r.amount() == null && !"VOID".equals(r.status()))
                                .count()),
                c.filter().page(),
                c.filter().size(),
                rows.subList(offset, Math.min(rows.size(), offset + c.filter().size())).stream()
                        .map(CostDashboardService::record)
                        .toList());
    }

    public List<CostFact> matching(Context c) {
        return sorted(
                period(
                        c.rows(),
                        c.filter(),
                        c.period(),
                        "previous".equals(c.filter().side()),
                        true));
    }

    public CostDashboardDetail detail(CostDashboardQuery q, String source, String id) {
        var c = context(q);
        var fact =
                c.rows().stream()
                        .filter(
                                r ->
                                        r.source().equals(source)
                                                && r.sourceId().equals(id)
                                                && c.filter().matches(r)
                                                && r.inPeriod(
                                                        c.period(),
                                                        "previous".equals(c.filter().side())))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.NOT_FOUND, "费用不存在或无访问权限", List.of()));
        var current = sourceDetail.verify(fact.source(), fact.sourceCategory(), fact.sourceId());
        if (current.revision() != fact.revision()
                || !Objects.equals(current.status(), fact.status()))
            throw new CostDashboardFailure("SOURCE_VERSION_CHANGED", 409, "源单据已更新，请刷新整个看板后查看");
        Map<String, String> dimensions = new LinkedHashMap<>();
        DIMENSIONS
                .get(fact.category())
                .forEach(k -> dimensions.put(DIMENSION_LABELS.get(k), fact.dimension(k).label()));
        Map<String, String> sourceFields =
                Map.of(
                        "recordArea",
                        "面积（平方米）",
                        "recordUnitPrice",
                        "进货单价（元）",
                        "recordQuantity",
                        "数量",
                        "recordUnit",
                        "单位");
        sourceFields.forEach(
                (key, label) -> {
                    var value = fact.dimensions().get(key);
                    if (value != null) dimensions.put(label, value.label());
                });
        Map.of("courier_contents", "寄送内容", "courier_recipientName", "收件客户或合作方",
                        "courier_companyName", "快递公司", "courier_trackingNo", "运单号")
                .forEach((key, label) -> {
                    var value = fact.dimensions().get(key);
                    if (value != null) dimensions.put(label, value.label());
                });
        fact.components()
                .forEach(
                        (key, value) ->
                                dimensions.put(
                                        switch (key) {
                                            case "baseSalary" -> "底薪（元）";
                                            case "performance" -> "绩效（元）";
                                            case "commission" -> "提成含激励（元）";
                                            case "allowance" -> "津补贴（元）";
                                            case "correctionAmount" -> "跨月补差（元）";
                                            default -> key;
                                        },
                                        money(value)));
        log.info(
                "cost_dashboard_detail tenant={} actor={} snapshot={} source={} sourceId={}"
                        + " revision={}",
                c.snapshot().tenant(),
                c.access().actor().principalId(),
                c.snapshot().id(),
                source,
                id,
                fact.revision());
        return new CostDashboardDetail(
                c.meta(),
                record(fact),
                dimensions,
                fact.remark(),
                fact.originalNumbers(),
                fact.sourceTable(),
                fact.revision(),
                fact.mappingState(),
                sourceRoute(fact));
    }

    private static List<CostDashboardPayrollComponent> payrollComponents(
            List<CostFact> current, List<CostFact> previous) {
        var rows =
                current.stream()
                        .filter(r -> "PAYROLL".equals(r.sourceCategory()) && r.confirmed())
                        .toList();
        var before =
                previous.stream()
                        .filter(r -> "PAYROLL".equals(r.sourceCategory()) && r.confirmed())
                        .toList();
        Map<String, String> names = new LinkedHashMap<>();
        names.put("baseSalary", "底薪");
        names.put("performance", "绩效");
        names.put("commission", "提成（含激励）");
        names.put("allowance", "津补贴");
        names.put("correctionAmount", "跨月补差");
        List<CostDashboardPayrollComponent> result = new ArrayList<>();
        names.forEach(
                (key, label) -> {
                    long missing =
                            rows.stream().filter(r -> !r.components().containsKey(key)).count();
                    BigDecimal amount =
                            missing > 0
                                    ? null
                                    : rows.stream()
                                            .map(r -> r.components().get(key))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal old =
                            before.isEmpty()
                                            || before.stream()
                                                    .anyMatch(r -> !r.components().containsKey(key))
                                    ? null
                                    : before.stream()
                                            .map(r -> r.components().get(key))
                                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    result.add(
                            new CostDashboardPayrollComponent(
                                    key,
                                    label,
                                    money(amount),
                                    money(old),
                                    amount == null || old == null
                                            ? null
                                            : money(amount.subtract(old)),
                                    rows.size(),
                                    missing));
                });
        return result;
    }

    private static String sourceRoute(CostFact r) {
        if ("HR".equals(r.source()))
            return ("PAYROLL".equals(r.sourceCategory())
                            ? "/supply-chain/hr/payroll?sourceId="
                            : "/supply-chain/hr/expenses?sourceId=")
                    + r.sourceId();
        String path =
                switch (r.category()) {
                    case "freight" -> "/supply-chain/cost/logistics";
                    case "purchase" -> "/supply-chain/cost/purchases";
                    case "loss" -> "/supply-chain/cost/losses";
                    case "marketing" -> "/supply-chain/cost/marketing";
                    case "office" -> "/supply-chain/cost/office-rentals";
                    case "expense" -> "/supply-chain/hr/expenses";
                    default -> "/supply-chain/cost/warehouse-rentals";
                };
        return path + "?sourceId=" + r.sourceId();
    }

    public static CostDashboardRecordView record(CostFact r) {
        return new CostDashboardRecordView(
                r.source(),
                r.sourceId(),
                r.documentNo(),
                r.title(),
                r.category(),
                r.city(),
                r.cityName(),
                r.date(),
                money(r.amount()),
                r.status(),
                r.payment(),
                r.sourceSystem(),
                r.employeeName(),
                r.costMonth().toString(),
                r.monthly() ? "MONTH" : "DAY",
                r.treatment());
    }

    private static Filter filter(CostDashboardQuery q, LocalDate cutoff) {
        return new Filter(
                q.year() == null ? cutoff.getYear() : q.year(),
                q.month() == null ? cutoff.getMonthValue() : q.month(),
                value(q.compare(), "previous"),
                blank(q.city()),
                value(q.scope(), "opex"),
                blank(q.category()),
                blank(q.dimension()),
                blank(q.dimensionKey()),
                value(q.status(), "CONFIRMED"),
                value(q.payment(), "ALL"),
                blank(q.search()),
                value(q.periodSide(), "current"),
                q.page() == null ? 1 : q.page(),
                q.size() == null ? 20 : q.size(),
                Boolean.TRUE.equals(q.missingAmount()));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String value(String s, String def) {
        return blank(s) == null ? def : s;
    }

    private static CostDashboardGroup view(Bucket g, BigDecimal total, boolean comparable) {
        return new CostDashboardGroup(
                g.key(),
                g.label(),
                money(g.current()),
                comparable ? money(g.previous()) : null,
                comparable ? money(g.current().subtract(g.previous())) : null,
                comparable ? ratio(g.current().subtract(g.previous()), g.previous()) : null,
                ratio(g.current(), total),
                g.count(),
                g.missing());
    }

    private static CostDashboardMetric metric(List<CostFact> rows) {
        return new CostDashboardMetric(
                money(amount(rows)),
                count(rows),
                money(sum(rows, r -> r.confirmed() && "UNPAID".equals(r.payment()))),
                rows.stream().filter(r -> "DRAFT".equals(r.status())).count(),
                money(sum(rows, r -> "DRAFT".equals(r.status()))),
                rows.stream().filter(r -> !"VOID".equals(r.status()) && r.amount() == null).count(),
                money(sum(rows, r -> r.confirmed() && "UNKNOWN".equals(r.city()))),
                money(
                        sum(
                                rows,
                                r ->
                                        "CONFIRMED".equals(r.status())
                                                && "LINKED".equals(r.treatment()))),
                rows.stream()
                        .filter(
                                r ->
                                        "CONFIRMED".equals(r.status())
                                                && "REVIEW".equals(r.treatment()))
                        .count(),
                money(
                        sum(
                                rows,
                                r ->
                                        "CONFIRMED".equals(r.status())
                                                && "REVIEW".equals(r.treatment()))));
    }

    private static BigDecimal scopeTotal(Context c, String scope) {
        return amount(
                c.rows().stream()
                        .filter(
                                r ->
                                        scope.equals(r.scope())
                                                && (c.filter().city() == null
                                                        || c.filter().city().equals(r.city()))
                                                && r.inPeriod(c.period(), false))
                        .toList());
    }

    private static List<CostDashboardChoice> cityChoices(
            CostDashboardStore.Snapshot snapshot,
            CostDashboardAccess.Access a,
            List<CostFact> rows) {
        Map<String, String> cities = new TreeMap<>();
        for (var city : snapshot.cities()) {
            var sample =
                    new CostFact(
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            city.code(),
                            city.name(),
                            city.sourceRegion(),
                            "",
                            snapshot.cutoff(),
                            BigDecimal.ZERO,
                            "CONFIRMED",
                            "UNKNOWN",
                            null,
                            null,
                            null,
                            city.departmentId(),
                            city.departmentPath(),
                            null,
                            null,
                            null,
                            0,
                            null,
                            Map.of(),
                            List.of());
            if (CostDashboardAccess.dashboardVisible(a.dashboard(), sample))
                cities.put(city.code(), city.name());
        }
        rows.forEach(r -> cities.put(r.city(), r.cityName()));
        return cities.entrySet().stream()
                .map(e -> new CostDashboardChoice(e.getKey(), e.getValue()))
                .toList();
    }

    private static List<CostDashboardPoint> cumulative(
            List<CostFact> cur,
            List<CostFact> prev,
            CostPeriod p,
            boolean year,
            boolean comparable) {
        int currentEnd = year ? p.to().getMonthValue() : p.to().getDayOfMonth(),
                previousEnd =
                        year ? p.previousTo().getMonthValue() : p.previousTo().getDayOfMonth();
        List<CostDashboardPoint> result = new ArrayList<>();
        BigDecimal left = BigDecimal.ZERO, right = BigDecimal.ZERO;
        for (int i = 1; i <= Math.max(currentEnd, previousEnd); i++) {
            final int n = i;
            left =
                    left.add(
                            sum(
                                    cur,
                                    r ->
                                            r.confirmed()
                                                    && (year || !r.monthly())
                                                    && (year
                                                                    ? r.costMonth().getMonthValue()
                                                                    : r.date().getDayOfMonth())
                                                            == n));
            right =
                    right.add(
                            sum(
                                    prev,
                                    r ->
                                            r.confirmed()
                                                    && (year || !r.monthly())
                                                    && (year
                                                                    ? r.costMonth().getMonthValue()
                                                                    : r.date().getDayOfMonth())
                                                            == n));
            result.add(
                    new CostDashboardPoint(
                            i + (year ? "月" : "日"),
                            i <= currentEnd ? money(left) : null,
                            comparable && i <= previousEnd ? money(right) : null));
        }
        return result;
    }

    private static List<CostDashboardMonth> months(Context c) {
        var f = c.filter();
        YearMonth end =
                f.month() == 0
                        ? YearMonth.from(c.period().to())
                        : YearMonth.of(f.year(), f.month());
        YearMonth start = f.month() == 0 ? YearMonth.of(f.year(), 1) : end.minusMonths(5);
        if (start.isBefore(YearMonth.of(2000, 1))) start = YearMonth.of(2000, 1);
        List<CostDashboardMonth> result = new ArrayList<>();
        for (var month = start; !month.isAfter(end); month = month.plusMonths(1)) {
            var p =
                    CostPeriod.resolve(
                            month.getYear(),
                            month.getMonthValue(),
                            c.period().compare(),
                            c.snapshot().cutoff());
            var current = period(c.rows(), f, p, false, false);
            var previous = period(c.rows(), f, p, true, false);
            result.add(
                    new CostDashboardMonth(
                            month.getYear(),
                            month.getMonthValue(),
                            month.toString(),
                            money(amount(current)),
                            count(previous) > 0 ? money(amount(previous)) : null,
                            p.to()));
        }
        return result;
    }
}
