package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.LogisticsCostSourceView;

import java.util.List;
import java.util.Set;

public interface OrderLogisticsReferences {
    String city(String code, String action);

    String party(String kind, Long id, String city, String action);

    com.rigour.order.api.v1.model.LogisticsCostWarehouseView warehouseDetail(
            Long id, String action);

    String warehouse(Long id, String action);

    Set<String> validCodes(String tenant, String code);

    List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action);

    default List<LogisticsCostSourceView> sources(
            String leg, String keyword, List<Long> ids, String action, String city, int begin) {
        return sources(leg, keyword, ids, action);
    }
}
