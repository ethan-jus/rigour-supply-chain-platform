package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 供应链 BI 字典化筛选项集合。 */
public record SupplyDashboardFilterOptionsView(
        List<SupplyDashboardFilterOptionView> regions,
        List<SupplyDashboardFilterOptionView> salesOwners,
        List<SupplyDashboardFilterOptionView> customerTypes,
        List<SupplyDashboardFilterOptionView> productCategories,
        List<SupplyDashboardFilterOptionView> sourceSystems) {
    public SupplyDashboardFilterOptionsView {
        regions = List.copyOf(regions == null ? List.of() : regions);
        salesOwners = List.copyOf(salesOwners == null ? List.of() : salesOwners);
        customerTypes = List.copyOf(customerTypes == null ? List.of() : customerTypes);
        productCategories = List.copyOf(productCategories == null ? List.of() : productCategories);
        sourceSystems = List.copyOf(sourceSystems == null ? List.of() : sourceSystems);
    }
}
