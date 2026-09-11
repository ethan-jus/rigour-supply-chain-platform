package com.rigour.analytics.api.v1.model;

import java.util.List;

/** 供应链 BI 手动刷新命令；sourceCodes 为空时按全链路刷新。 */
public record SupplyDashboardRefreshCommand(List<String> sourceCodes, Boolean fullRefresh) {
    public SupplyDashboardRefreshCommand(List<String> sourceCodes) {
        this(sourceCodes, null);
    }

    public SupplyDashboardRefreshCommand {
        sourceCodes = sourceCodes == null
                ? List.of()
                : sourceCodes.stream().map(value -> value == null ? "" : value).toList();
    }

    public boolean fullRefreshEnabled() {
        return Boolean.TRUE.equals(fullRefresh);
    }
}
