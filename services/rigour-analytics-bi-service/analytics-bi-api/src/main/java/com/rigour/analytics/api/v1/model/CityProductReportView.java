package com.rigour.analytics.api.v1.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 可导出的商品、分类及订单附表；超限时不返回不完整汇总，summary 为 null。 */
public record CityProductReportView(
        Instant from, Instant to, Instant generatedAt, Instant dataUpdatedAt, String allocationMode,
        List<Row> rows, List<Row> categoryRows, List<OrderTrace> orderTrace,
        Summary summary, boolean truncated, boolean exportBlocked, List<String> definitions,
        List<MonthlyRow> monthlyRows, List<CustomerArchive> customerArchives) {

    /** 北京时间订单月份；金额沿用整单归属结果，不按月份重新分配回款。 */
    public record MonthlyRow(String month, Row metrics) { }

    /** 当前客户档案，不按订单日期或商品筛选；城市、销售和客户类型沿用请求范围。 */
    public record CustomerArchive(String regionCode, String regionName, long customerCount) { }

    /** 两个列表分别汇总，不能相加；paidAmount 包含 allocatedPaidAmount，null 不代表零回款。 */
    public record Row(
            String regionCode, String regionName,
            String categoryId, String categoryCode, String categoryName,
            String productId, String productCode, String productName,
            String skuId, String skuCode, String unitCode,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
            List<UnitQuantity> quantities,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salesAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salesNetAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal allocatedPaidAmount,
            String allocationStatus,
            long unallocatedOrderCount, long orderCount, long customerCount, String specification,
            String brandId, String brandName) { }

    /** 订单总额每订单仅一行；所选行金额不能替换整订单分摊分母。 */
    public record OrderTrace(
            String orderId, String orderNo, String sourceOrderNo, String sourceSystemCode,
            String regionCode, String regionName, String ownerStaffCode, String customerId,
            Instant orderDate, long lineCount, long selectedLineCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal total,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal lineTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal selectedLineTotal,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderAdjustmentAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paid,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unpaid,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal selectedReceivableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal selectedPaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal allocatedPaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal excludedPaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unallocatedPaidAmount,
            String status, String categoryStatus, String customerName, String ownerStaffName) { }

    /** 订单/客户数单独去重；资金守恒以商品列表为准，分类列表是另一种观察粒度。 */
    public record Summary(
            long orderCount, long customerCount, long unallocatedOrderCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal categoryPaidAmount,
            long unallocatedCategoryOrderCount,
            List<UnitQuantity> quantities,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salesAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal salesNetAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal refundAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderPayableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderPaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal orderUnpaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal receivableAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal paidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal allocatedPaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal excludedPaidAmount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unallocatedPaidAmount) { }

    /** 原始单位数量，不折箱、不跨单位求和。 */
    public record UnitQuantity(
            String unitCode, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity) { }
}
