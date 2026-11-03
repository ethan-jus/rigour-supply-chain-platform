package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 销售按下单月，回款按首次整单付清月，提成沿用工资快照。 */
public record PayrollPerformanceExportView(
        String payrollId, int payrollRevision, String month, String employeeCode, String employeeName, String cityName,
        List<Category> categories, List<ClothSales> clothSales,
        BigDecimal salesAmount, BigDecimal collectionAmount, BigDecimal refundAmount,
        BigDecimal collectionTarget, BigDecimal salesTarget, BigDecimal commission,
        List<String> issues,
        String cityCode, String role, LocalDate entryDate, LocalDate leaveDate,
        Summary summary, List<Metric> metrics, String collectionTargetBasis) {
    public record Category(String code, String name, BigDecimal salesAmount,
                           BigDecimal collectionAmount, BigDecimal commission) {}
    /** 金额按下单月；数量按首次整单付清月、订单原单位分别保存，不跨单位相加。 */
    public record ClothSales(String code, String name, BigDecimal salesAmount, List<UnitQuantity> quantities) {}
    public record UnitQuantity(String unitCode, BigDecimal quantity) {}
    public record Summary(BigDecimal expectedDays, BigDecimal actualDays,
                          Integer expectedCheckins, Integer actualCheckins,
                          Integer expectedVisits, Integer actualVisits, BigDecimal processDeduction) {}
    public record Metric(String code, String name, BigDecimal actual, BigDecimal target,
                         BigDecimal weight, String evidence) {}
}
