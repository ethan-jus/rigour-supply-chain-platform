package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.*;

import java.util.List;

public interface OrderLedgerSourceStore {
    default List<String> visibleSalesNos(String tenant, List<String> documentNos, String action) {
        return List.of();
    }

    default java.util.Map<String, Integer> logisticsUsage(
            String tenant, List<String> documentNos, String action) {
        return java.util.Map.of();
    }

    List<OrderDeliveryCostSourceView> sales(
            String tenant, LogisticsLedgerSourceQuery query, String action, Long warehouseId);

    List<OrderDeliveryCostSourceView> salesByNos(
            String tenant, List<String> documentNos, String action, boolean historical);
}
