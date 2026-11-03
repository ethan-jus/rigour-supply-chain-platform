package com.rigour.hr.api.v1.model;

import java.math.BigDecimal;
import java.util.List;

/** 销售按下单月，回款按首次整单付清月，提成沿用工资快照。 */
public record PayrollPerformanceExportView(
        String payrollId, int payrollRevision, String month, String employeeCode, String employeeName, String cityName,
        List<Category> categories, List<ClothSales> clothSales,
        BigDecimal salesAmount, BigDecimal collectionAmount, BigDecimal refundAmount,
        BigDecimal collectionTarget, BigDecimal salesTarget, BigDecimal commission,
        List<String> issues) {
    public record Category(String code, String name, BigDecimal salesAmount,
                           BigDecimal collectionAmount, BigDecimal commission) {}
    public record ClothSales(String code, String name, BigDecimal salesAmount) {}
}
