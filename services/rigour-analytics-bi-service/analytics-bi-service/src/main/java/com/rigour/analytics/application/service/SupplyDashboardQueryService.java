package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.SupplyDashboardCityCostItemView;
import com.rigour.analytics.api.v1.model.SupplyDashboardDataFreshnessView;
import com.rigour.analytics.api.v1.model.SupplyDashboardMetricCardView;
import com.rigour.analytics.api.v1.model.SupplyDashboardMetricDefinitionView;
import com.rigour.analytics.api.v1.model.SupplyDashboardOverviewView;
import com.rigour.analytics.api.v1.model.SupplyDashboardProductSalesItemView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRankingItemView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRiskItemView;
import com.rigour.analytics.api.v1.model.SupplyDashboardRolePerspectiveView;
import com.rigour.analytics.api.v1.model.SupplyDashboardTrendPointView;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SupplyDashboardData;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 供应链 BI 看板查询用例。 */
@Service
public final class SupplyDashboardQueryService {
    static final String READ_PERMISSION = "analytics:dashboard:read";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final SupplyDashboardStore store;
    private final Clock clock;

    public SupplyDashboardQueryService(SupplyDashboardStore store, Clock analyticsClock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(analyticsClock, "analyticsClock");
    }

    public SupplyDashboardOverviewView overview(
            Instant from, Instant to, String regionCode, String ownerStaffCode,
            String customerTypeCode, Long productCategoryId, String sourceSystemCode) {
        CallerIdentity actor = actor();
        Instant now = Instant.now(clock);
        Instant normalizedTo = to == null ? now : to;
        Instant normalizedFrom = from == null ? monthStart(normalizedTo) : from;
        if (normalizedFrom.isAfter(normalizedTo)) throw badRequest("from不能晚于to");
        SupplyDashboardFilter filter = new SupplyDashboardFilter(
                normalizedFrom,
                normalizedTo,
                code(regionCode, "regionCode"),
                text(ownerStaffCode, 50, "ownerStaffCode"),
                code(customerTypeCode, "customerTypeCode"),
                positiveId(productCategoryId, "productCategoryId"),
                sourceSystemCode(sourceSystemCode));
        SupplyDashboardData data = store.overview(actor.tenantId().toString(), filter);
        Instant cutoff = cutoff(data);
        return new SupplyDashboardOverviewView(
                filter.from(),
                filter.to(),
                now,
                metrics(data),
                data.salesTrend().stream().map(SupplyDashboardQueryService::trend).toList(),
                data.collectionTrend().stream().map(SupplyDashboardQueryService::trend).toList(),
                data.cityCostTrend().stream().map(SupplyDashboardQueryService::trend).toList(),
                data.citySalesRanking().stream().map(SupplyDashboardQueryService::ranking).toList(),
                data.salesRanking().stream().map(SupplyDashboardQueryService::ranking).toList(),
                data.sourceSystemBreakdown().stream().map(SupplyDashboardQueryService::ranking).toList(),
                data.productSalesRanking().stream().map(SupplyDashboardQueryService::productSales).toList(),
                data.categorySalesRanking().stream().map(SupplyDashboardQueryService::productSales).toList(),
                data.brandSalesRanking().stream().map(SupplyDashboardQueryService::productSales).toList(),
                data.paymentRiskCityRanking().stream().map(SupplyDashboardQueryService::ranking).toList(),
                data.paymentRiskSalesRanking().stream().map(SupplyDashboardQueryService::ranking).toList(),
                data.cityCostRanking().stream().map(SupplyDashboardQueryService::cityCost).toList(),
                data.risks().stream().map(SupplyDashboardQueryService::risk).toList(),
                data.freshness().stream().map(SupplyDashboardQueryService::freshness).toList(),
                rolePerspectives(),
                definitions(now, cutoff));
    }

    private static CallerIdentity actor() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission(READ_PERMISSION);
        return caller;
    }

    private static List<SupplyDashboardMetricCardView> metrics(SupplyDashboardData data) {
        BigDecimal cityCostRate = ratio(data.cityCost().costAmount(), data.sales().salesAmount());
        return List.of(
                metric("sales_amount", "销售额", money(data.sales().salesAmount()), "CNY",
                        "销售订单应收金额，不含已取消订单"),
                metric("paid_amount", "订单已收", money(data.sales().paidAmount()), "CNY",
                        "销售订单累计已收金额"),
                metric("unpaid_amount", "待回款", money(data.sales().unpaidAmount()), "CNY",
                        "销售订单未收金额，供销售和运营跟进"),
                metric("receipt_amount", "回款额", money(data.collections().receiptAmount()), "CNY",
                        "销售回款记录的回款金额，不把订货宝付款流水默认为退款"),
                metric("refund_amount", "退款额", money(data.profit().refundAmount()), "CNY",
                        "订单级退款按订单行金额比例分摊后的退款金额"),
                metric("order_count", "订单数", decimal(data.sales().orderCount()), "COUNT",
                        "查询周期内销售订单数量"),
                metric("ordering_customer_count", "下单客户数", decimal(data.sales().orderingCustomerCount()), "COUNT",
                        "查询周期内有订单的客户数"),
                metric("active_customer_count", "有效客户数", decimal(data.customers().activeCustomerCount()), "COUNT",
                        "CRM 有效客户数"),
                metric("sales_net_amount", "销售净收入", money(data.profit().salesNetAmount()), "CNY",
                        "订单行金额扣减订单级退款分摊后的金额"),
                metric("estimated_cost_amount", "估算销售成本", money(data.profit().estimatedCostAmount()), "CNY",
                        "订单行数量 × ERP 商品规格采购参考价"),
                metric("estimated_gross_profit", "估算毛利", money(data.profit().estimatedGrossProfit()), "CNY",
                        "销售净收入 - ERP 采购参考价估算销售成本"),
                metric("estimated_gross_profit_rate", "估算毛利率", ratioValue(data.profit().estimatedGrossProfitRate()), "PERCENT",
                        "估算毛利 / 销售净收入；成本缺失会影响准确性"),
                metric("cost_coverage_rate", "成本覆盖率", ratioValue(data.profit().costCoverageRate()), "PERCENT",
                        "有 ERP 采购参考价的订单行金额占比"),
                metric("payment_risk_amount", "回款风险金额", money(data.paymentRisk().riskAmount()), "CNY",
                        "当前仍待回款订单金额"),
                metric("payment_risk_customer_count", "回款风险门店数", decimal(data.paymentRisk().riskCustomerCount()), "COUNT",
                        "当前存在待回款订单的客户/门店数"),
                metric("payment_high_risk_customer_count", "高风险门店数", decimal(data.paymentRisk().highRiskCustomerCount()), "COUNT",
                        "逾期60天及以上且仍有待回款的客户/门店数"),
                metric("payment_avg_overdue_days", "平均逾期天数", money(data.paymentRisk().averageOverdueDays()), "DAYS",
                        "待回款订单按订单日期估算的平均逾期天数"),
                metric("payment_risk_amount_rate", "风险金额占比", ratioValue(data.paymentRisk().riskAmountRate()), "PERCENT",
                        "待回款金额 / 销售额"),
                metric("target_achievement_rate", "目标达成率", BigDecimal.ZERO, "PERCENT",
                        "目标配置尚未接入 BI，当前只保留占位"),
                metric("city_cost_amount", "城市端成本", money(data.cityCost().costAmount()), "CNY",
                        "城市端成本 BI 快照表中的成本金额"),
                metric("city_cost_rate", "城市成本率", cityCostRate, "PERCENT",
                        "城市端成本 / 销售额"),
                metric("inventory_risk_count", "重点库存风险", decimal((long) data.risks().size()), "COUNT",
                        "当前筛选范围内优先关注的库存风险项"));
    }

    private static SupplyDashboardMetricCardView metric(
            String code, String name, BigDecimal value, String unit, String description) {
        return new SupplyDashboardMetricCardView(code, name, value, unit, null, null, description);
    }

    private static SupplyDashboardTrendPointView trend(SupplyDashboardStore.TrendPoint item) {
        return new SupplyDashboardTrendPointView(
                item.metricCode(), item.period(), money(item.value()), money(item.secondaryValue()));
    }

    private static SupplyDashboardRankingItemView ranking(SupplyDashboardStore.RankingItem item) {
        return new SupplyDashboardRankingItemView(
                item.rankType(),
                item.dimensionCode(),
                item.dimensionName(),
                money(item.salesAmount()),
                money(item.paidAmount()),
                money(item.unpaidAmount()),
                number(item.orderCount()),
                number(item.customerCount()),
                ratioValue(item.rate()));
    }

    private static SupplyDashboardProductSalesItemView productSales(SupplyDashboardStore.ProductSalesItem item) {
        return new SupplyDashboardProductSalesItemView(
                item.rankType(),
                item.dimensionCode(),
                item.dimensionName(),
                item.categoryCode(),
                item.categoryName(),
                money(item.salesQuantity()),
                money(item.salesAmount()),
                money(item.discountAmount()),
                money(item.refundAmount()),
                money(item.salesNetAmount()),
                money(item.estimatedCostAmount()),
                money(item.estimatedGrossProfit()),
                ratioValue(item.estimatedGrossProfitRate()),
                ratioValue(item.costCoverageRate()),
                number(item.orderCount()),
                number(item.customerCount()));
    }

    private static SupplyDashboardCityCostItemView cityCost(SupplyDashboardStore.CityCostItem item) {
        return new SupplyDashboardCityCostItemView(
                item.regionCode(),
                item.regionName(),
                money(item.costAmount()),
                money(item.budgetAmount()),
                money(item.varianceAmount()),
                money(item.salesAmount()),
                ratioValue(item.costRate()),
                number(item.recordCount()),
                item.latestCostTime());
    }

    private static SupplyDashboardRiskItemView risk(SupplyDashboardStore.RiskItem item) {
        return new SupplyDashboardRiskItemView(
                item.riskType(),
                item.riskLevel(),
                item.dimensionCode(),
                item.dimensionName(),
                item.description(),
                item.primaryValue(),
                item.secondaryValue(),
                item.observedAt());
    }

    private static SupplyDashboardDataFreshnessView freshness(SupplyDashboardStore.DataFreshness item) {
        return new SupplyDashboardDataFreshnessView(
                item.sourceCode(),
                item.sourceName(),
                item.latestUpdatedTime(),
                item.status(),
                item.description());
    }

    private static List<SupplyDashboardRolePerspectiveView> rolePerspectives() {
        return List.of(
                new SupplyDashboardRolePerspectiveView(
                        "CEO", "CEO",
                        List.of("sales_amount", "estimated_gross_profit_rate", "payment_risk_amount", "inventory_risk_count"),
                        List.of("overview", "productSalesRanking", "citySalesRanking", "paymentRisk"),
                        "默认关注经营规模、估算毛利、回款风险和库存风险"),
                new SupplyDashboardRolePerspectiveView(
                        "OPERATION", "运营",
                        List.of("city_cost_amount", "city_cost_rate", "inventory_risk_count", "payment_risk_customer_count"),
                        List.of("cityCost", "productSalesRanking", "inventoryRisk", "paymentRisk"),
                        "默认关注城市预算执行、库存风险和回款风险跟进"),
                new SupplyDashboardRolePerspectiveView(
                        "SALES", "销售",
                        List.of("sales_amount", "paid_amount", "payment_risk_amount", "ordering_customer_count"),
                        List.of("salesRanking", "collectionTrend", "paymentRiskCityRanking"),
                        "默认关注本人业绩、已收回款、回款风险和下单客户"));
    }

    private static List<SupplyDashboardMetricDefinitionView> definitions(Instant updatedAt, Instant cutoff) {
        return List.of(
                definition("sales_amount", "销售额",
                        "SUM(bi_sales_order_fact.payable_amount)，排除逻辑删除和已取消订单",
                        "Analytics BI / bi_sales_order_fact", "排除 deleted=1 和 order_status_code=CANCELLED",
                        updatedAt, cutoff),
                definition("receipt_amount", "回款额",
                        "SUM(bi_sales_payment_fact.paid_amount)，以销售回款记录为准",
                        "Analytics BI / bi_sales_payment_fact", "排除 deleted=1；不把订货宝付款流水默认识别为退款",
                        updatedAt, cutoff),
                definition("product_sales_amount", "商品销售额",
                        "SUM(bi_sales_order_line_fact.line_amount)，按商品或分类汇总",
                        "Analytics BI / bi_sales_order_line_fact", "排除 deleted=1 和 order_status_code=CANCELLED",
                        updatedAt, cutoff),
                definition("product_sales_quantity", "商品订货数量",
                        "SUM(bi_sales_order_line_fact.quantity)，按商品或分类汇总",
                        "Analytics BI / bi_sales_order_line_fact", "排除 deleted=1 和 order_status_code=CANCELLED",
                        updatedAt, cutoff),
                definition("refund_amount", "退款额",
                        "SUM(bi_sales_order_line_fact.refund_amount)，订单级退款按订单行金额比例分摊",
                        "Analytics BI / bi_sales_order_line_fact", "退款按订单行金额比例分摊到行；不把订货宝付款流水默认识别为退款",
                        updatedAt, cutoff),
                definition("sales_net_amount", "销售净收入",
                        "SUM(bi_sales_order_line_fact.sales_net_amount)，订单行金额扣减订单级退款分摊",
                        "Analytics BI / bi_sales_order_line_fact", "退款按订单行金额比例分摊到行",
                        updatedAt, cutoff),
                definition("estimated_cost_amount", "估算销售成本",
                        "SUM(bi_sales_order_line_fact.estimated_cost_amount)，订单行数量乘 ERP 商品规格采购参考价",
                        "Analytics BI / bi_sales_order_line_fact + ERP 商品规格采购参考价", "采购参考价缺失时按0计入，并通过成本覆盖率提示",
                        updatedAt, cutoff),
                definition("estimated_gross_profit", "估算毛利",
                        "SUM(sales_net_amount - estimated_cost_amount)，销售净收入扣减订单级退款分摊和 ERP 采购参考价估算成本",
                        "Analytics BI / bi_sales_order_line_fact + ERP 商品规格采购参考价", "退款按订单行金额比例分摊；不是真实出库成本",
                        updatedAt, cutoff),
                definition("estimated_gross_profit_rate", "估算毛利率",
                        "估算毛利 / 销售净收入；销售净收入为0时返回0",
                        "Analytics BI / bi_sales_order_line_fact", "成本缺失会降低成本覆盖率，不宣称为真实毛利",
                        updatedAt, cutoff),
                definition("payment_risk_amount", "回款风险金额",
                        "SUM(bi_sales_order_fact.unpaid_amount)，仅统计未取消且仍有待回款订单",
                        "Analytics BI / bi_sales_order_fact", "高风险暂按订单日期距查询截止日 >= 60 天估算",
                        updatedAt, cutoff),
                definition("city_cost_amount", "城市端成本",
                        "SUM(bi_city_cost_record.cost_amount)，按城市和成本类型汇总",
                        "Analytics BI / bi_city_cost_record", "排除 deleted=1；Feishu 历史仅允许作为归档来源标记",
                        updatedAt, cutoff),
                definition("city_cost_rate", "城市成本率",
                        "城市端成本 / 销售额；销售额为0时返回0",
                        "Analytics BI / bi_city_cost_record + bi_sales_order_fact", "销售额为0时返回0，不做跨源实时兜底",
                        updatedAt, cutoff),
                definition("inventory_risk_count", "重点库存风险",
                        "available_quantity <= 0 或 available_quantity < locked_quantity 的库存余额项",
                        "Analytics BI / bi_inventory_balance_current", "看板只返回前20条重点风险项",
                        updatedAt, cutoff));
    }

    private static SupplyDashboardMetricDefinitionView definition(
            String code, String name, String formula, String source, String exclusionRule,
            Instant updatedAt, Instant cutoff) {
        return new SupplyDashboardMetricDefinitionView(
                code, name, formula, source, "analytics-bi-service", "v1", exclusionRule, updatedAt, cutoff);
    }

    private static Instant cutoff(SupplyDashboardData data) {
        return data.freshness().stream()
                .map(SupplyDashboardStore.DataFreshness::latestUpdatedTime)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    private static Instant monthStart(Instant instant) {
        LocalDate date = instant.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String code(String value, String name) {
        String normalized = upper(value);
        if (normalized == null) return null;
        if (!CODE.matcher(normalized).matches()) throw badRequest(name + "格式无效");
        return normalized;
    }

    private static String sourceSystemCode(String value) {
        String normalized = code(value, "sourceSystemCode");
        if ("DHB".equals(normalized)) return "DINGHUOBAO";
        return normalized;
    }

    private static String upper(String value) {
        String normalized = text(value, 64, "code");
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private static String text(String value, int max, String name) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > max) throw badRequest(name + "长度不能超过" + max);
        return normalized;
    }

    private static Long positiveId(Long value, String name) {
        if (value == null) return null;
        if (value < 1) throw badRequest(name + "必须大于0");
        return value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal decimal(Long value) {
        return BigDecimal.valueOf(number(value));
    }

    private static long number(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(ONE_HUNDRED).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratioValue(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of());
    }
}
