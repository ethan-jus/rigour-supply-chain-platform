package com.rigour.analytics.api.v1.model;

import java.math.BigDecimal;

/**
 * BI 城市/销售经营目标完成度，不代表 HR 工资或绩效结算。
 *
 * <p>沿用 dashboard UTC 期间，目标按期间涉及的已配置自然月累加；订单实际值只计入同指标已配置月份
 * 与查询期间的交集。SALES_AMOUNT 为订单应付，PAID_AMOUNT 为这些订单的累计已回款，
 * COOPERATED_CUSTOMER 为交集内去重下单客户，不是新增客户。CONTACTED_CUSTOMER 为当前有效且有联系方式的
 * 客户档案存量，不能解释为历史月新增建联或拜访转化。该存量指标只在单月查询提供完成度。</p>
 * <p>configuredMonthCount 小于 periodMonthCount 表示部分月份未配置，不能当成整个期间的完成率；
 * 目标为零时 achievementRate 为零占位，不应绘制为已配置的完成度。兼容旧调用时月份覆盖为 null。</p>
 * <p>SALES_OWNER 目标不含城市子维度。指定城市时仅返回在本租户客户和全部订单归属中完整落于该城的销售；
 * 跨城、未知城市或没有可验证归属时不返回目标，不把个人全域目标按城市分摊，也不表示未配置。</p>
 * <p>UTC 月与 Asia/Shanghai 业务月存在八小时边界差；此响应没有自动把 UTC 月转为北京时间月份。
 * 日期筛选、趋势、目标月和报表需同时统一业务时区，不能仅偏移目标分母。</p>
 */
public record SupplyDashboardTargetCompletionItemView(
        String dimensionType,
        String dimensionCode,
        String dimensionName,
        String metricCode,
        String metricName,
        BigDecimal targetValue,
        BigDecimal actualValue,
        BigDecimal achievementRate,
        Long configuredMonthCount,
        Long periodMonthCount) {
    public SupplyDashboardTargetCompletionItemView(String dimensionType, String dimensionCode, String dimensionName,
            String metricCode, String metricName, BigDecimal targetValue, BigDecimal actualValue,
            BigDecimal achievementRate) {
        this(dimensionType, dimensionCode, dimensionName, metricCode, metricName, targetValue,
                actualValue, achievementRate, null, null);
    }
}
