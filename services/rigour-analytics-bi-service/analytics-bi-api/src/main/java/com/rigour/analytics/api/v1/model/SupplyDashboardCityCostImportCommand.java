package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 城市端成本 BI 导入命令。 */
public record SupplyDashboardCityCostImportCommand(
        String sourceSystemCode,
        List<SupplyDashboardCityCostImportRecord> records) {
}
