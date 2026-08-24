package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalStockBalanceView;
import com.rigour.erp.api.v1.model.MasterDataPageView;

/** ERP 库存余额查询端口；只读 `erp_stock_balance`，库存写入必须通过库存单据。 */
public interface ErpStockBalanceStore {
    MasterDataPageView<InternalStockBalanceView> stockBalances(
            String tenantId, int begin, int step, StockBalanceSearchCriteria criteria);

    /** 库存余额筛选条件。 */
    record StockBalanceSearchCriteria(
            String productCode,
            String productName,
            Long warehouseId,
            String warehouseName) {
    }
}
