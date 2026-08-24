package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalStockBalanceView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ERP 库存余额查询接口。
 *
 * <p>库存余额由采购入库、销售出库和调拨出入库同事务维护；本接口只提供查询，
 * 不允许前端绕过库存单据直接修改库存。</p>
 */
public interface ErpStockBalanceApi {
    String BASE_PATH = "/api/v1/erp/stock-balances";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalStockBalanceView>> stockBalances(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) String productName,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String warehouseName);
}
