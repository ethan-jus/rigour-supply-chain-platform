package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpCostDetailView;

import java.util.*;

/** 从已确认采购明细派生货款成本，仍由采购领域持有原始事实。 */
public interface ProcurementGoodsCosts {
    List<Map<String, String>> page(String tenant, String after);

    String version(String tenant);

    Optional<ErpCostDetailView> detail(String tenant, String id, String action);

    String reconcileLegacy(String tenant, String dataJson);

    default Map<String, String> warehouseDepartments(String tenant) {
        return Map.of();
    }
}
