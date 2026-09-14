package com.rigour.analytics.application.port.out;

import com.rigour.analytics.api.v1.model.CityProductSupplyView.Stock;
import com.rigour.analytics.api.v1.model.CityProductSupplyView.Operation;
import java.time.Instant;
import java.util.List;

/** BI本地供货事实查询端口，不跨库补齐关联，不执行采购或同步。 */
public interface CityProductSupplyStore {
    List<Stock> stocks(String tenantId, Long productId, Long warehouseId, Long skuId, int limit);
    List<Operation> operations(String tenantId, Instant from, Instant to, Long productId, Long skuId, int limit);
    Checkpoint checkpoint(String tenantId, String sourceCode);
    record Checkpoint(String status, Instant lastSuccessAt) { }
}
