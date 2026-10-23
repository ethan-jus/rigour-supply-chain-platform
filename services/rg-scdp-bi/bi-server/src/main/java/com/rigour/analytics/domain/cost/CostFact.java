package com.rigour.analytics.domain.cost;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 每条费用仅有一个事实；来源关联、附件和配送点不展开为金额行。 */
public record CostFact(
        String source,
        String sourceId,
        String sourceTable,
        String documentNo,
        String title,
        String category,
        String sourceCategory,
        String city,
        String cityName,
        String rawCity,
        String mappingState,
        LocalDate date,
        BigDecimal amount,
        String status,
        String payment,
        String sourceSystem,
        String employeeCode,
        String employeeName,
        String departmentId,
        List<String> departmentPath,
        String warehouseId,
        String sourceWarehouseId,
        String createdBy,
        long revision,
        String remark,
        Map<String, Dimension> dimensions,
        List<String> originalNumbers,
        Map<String, BigDecimal> components) {

    public CostFact {
        components = components == null ? Map.of() : Map.copyOf(components);
    }

    public CostFact(
            String source,
            String sourceId,
            String sourceTable,
            String documentNo,
            String title,
            String category,
            String sourceCategory,
            String city,
            String cityName,
            String rawCity,
            String mappingState,
            LocalDate date,
            BigDecimal amount,
            String status,
            String payment,
            String sourceSystem,
            String employeeCode,
            String employeeName,
            String departmentId,
            List<String> departmentPath,
            String warehouseId,
            String sourceWarehouseId,
            String createdBy,
            long revision,
            String remark,
            Map<String, Dimension> dimensions,
            List<String> originalNumbers) {
        this(
                source,
                sourceId,
                sourceTable,
                documentNo,
                title,
                category,
                sourceCategory,
                city,
                cityName,
                rawCity,
                mappingState,
                date,
                amount,
                status,
                payment,
                sourceSystem,
                employeeCode,
                employeeName,
                departmentId,
                departmentPath,
                warehouseId,
                sourceWarehouseId,
                createdBy,
                revision,
                remark,
                dimensions,
                originalNumbers,
                Map.of());
    }

    public record Dimension(String key, String label, boolean missing) {}

    public Dimension dimension(String key) {
        return dimensions.getOrDefault(key, new Dimension("UNKNOWN", "未记录", true));
    }

    /** 手工费用未录部门时，BI部门范围沿用已明确映射的经营城市部门；源业务授权仍独立校验。 */
    public String dashboardDepartment() {
        var city = dimensions.get("authorizationCityDepartment");
        return departmentId != null ? departmentId : city == null ? null : city.key();
    }

    public List<String> dashboardDepartmentPath() {
        var city = dimensions.get("authorizationCityDepartment");
        return departmentId != null
                ? departmentPath
                : city == null || city.label().isBlank()
                        ? List.of()
                        : List.of(city.label().split(","));
    }

    public String key() {
        return source + ":" + sourceId;
    }

    public boolean confirmed() {
        return "CONFIRMED".equals(status) && amount != null && "DIRECT".equals(treatment());
    }

    public String treatment() {
        return dimensions.containsKey("costTreatment")
                ? dimension("costTreatment").key()
                : "DIRECT";
    }

    public CostFact withDimensions(Map<String, Dimension> values) {
        return new CostFact(
                source,
                sourceId,
                sourceTable,
                documentNo,
                title,
                category,
                sourceCategory,
                city,
                cityName,
                rawCity,
                mappingState,
                date,
                amount,
                status,
                payment,
                sourceSystem,
                employeeCode,
                employeeName,
                departmentId,
                departmentPath,
                warehouseId,
                sourceWarehouseId,
                createdBy,
                revision,
                remark,
                Map.copyOf(values),
                originalNumbers,
                components);
    }

    public boolean monthly() {
        return "MONTH".equals(dimension("periodBasis").key());
    }

    public java.time.YearMonth costMonth() {
        var month = dimensions.get("costMonth");
        return month == null
                ? java.time.YearMonth.from(date)
                : java.time.YearMonth.parse(month.key());
    }

    public boolean inPeriod(CostPeriod period, boolean previous) {
        if (!monthly()) return period.contains(date, previous);
        var start = java.time.YearMonth.from(previous ? period.previousFrom() : period.from());
        var end = java.time.YearMonth.from(previous ? period.previousTo() : period.to());
        return !costMonth().isBefore(start) && !costMonth().isAfter(end);
    }

    public String scope() {
        return Set.of("purchase", "loss").contains(category) ? category : "opex";
    }
}
