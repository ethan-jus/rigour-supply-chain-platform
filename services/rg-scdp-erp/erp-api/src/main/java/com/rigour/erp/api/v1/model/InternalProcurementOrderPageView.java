package com.rigour.erp.api.v1.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 采购订单分页及同一筛选、数据权限范围内的全部单据合计。 */
public record InternalProcurementOrderPageView(
        long total, int begin, int step, List<InternalProcurementOrderSummaryView> items,
        Totals totals) {
    public record Totals(Map<String, BigDecimal> quantityByUnit, BigDecimal purchaseAmount) {
        public Totals {
            quantityByUnit = Map.copyOf(quantityByUnit);
        }
    }
}
