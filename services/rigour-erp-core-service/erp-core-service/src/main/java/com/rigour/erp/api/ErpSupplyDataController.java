package com.rigour.erp.api;

import com.rigour.erp.api.v1.ErpSupplyDataApi;
import com.rigour.erp.api.v1.model.InventoryBalanceView;
import com.rigour.erp.api.v1.model.PurchaseOrderView;
import com.rigour.erp.api.v1.model.PurchaseOrderDetailView;
import com.rigour.erp.api.v1.model.PurchaseReturnView;
import com.rigour.erp.api.v1.model.PurchaseReturnDetailView;
import com.rigour.erp.api.v1.model.SupplierView;
import com.rigour.erp.api.v1.model.SupplyDataPageView;
import com.rigour.erp.api.v1.model.WarehouseView;
import com.rigour.erp.api.v1.model.WarehousingReceiptDetailView;
import com.rigour.erp.api.v1.model.WarehousingReceiptView;
import com.rigour.erp.application.service.supply.SupplyDataQueryService;
import com.rigour.shared.context.TenantContext;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ERP 供应商、采购、仓储和库存浏览器边界。 */
@RestController
@RequestMapping
public final class ErpSupplyDataController implements ErpSupplyDataApi {
    private final SupplyDataQueryService query;

    public ErpSupplyDataController(SupplyDataQueryService query) {
        this.query = query;
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<SupplyDataPageView<SupplierView>> suppliers(
            int begin, int step, String q, String status) {
        return ApiResponse.success(query.suppliers(tenantId(), begin, step, q, status));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<SupplyDataPageView<PurchaseOrderView>> purchaseOrders(
            int begin, int step, String q, String status) {
        return ApiResponse.success(query.purchaseOrders(tenantId(), begin, step, q, status));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<PurchaseOrderDetailView> purchaseOrder(String id) {
        return ApiResponse.success(query.purchaseOrder(tenantId(), id));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<SupplyDataPageView<PurchaseReturnView>> purchaseReturns(
            int begin, int step, String q, String status) {
        return ApiResponse.success(query.purchaseReturns(tenantId(), begin, step, q, status));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<PurchaseReturnDetailView> purchaseReturn(String id) {
        return ApiResponse.success(query.purchaseReturn(tenantId(), id));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<SupplyDataPageView<WarehousingReceiptView>> warehousingReceipts(
            int begin, int step, String q, String status) {
        return ApiResponse.success(query.warehousingReceipts(tenantId(), begin, step, q, status));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<WarehousingReceiptDetailView> warehousingReceipt(String id) {
        return ApiResponse.success(query.warehousingReceipt(tenantId(), id));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<SupplyDataPageView<WarehouseView>> warehouses(
            int begin, int step, String q, String status) {
        return ApiResponse.success(query.warehouses(tenantId(), begin, step, q, status));
    }

    /** {@inheritDoc} */
    @Override
    public ApiResponse<SupplyDataPageView<InventoryBalanceView>> inventory(
            int begin, int step, String q, String warehouseCode, String status) {
        return ApiResponse.success(query.inventory(tenantId(), begin, step, q, warehouseCode, status));
    }

    private static String tenantId() {
        String value = TenantContext.getTenantId();
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少可信租户上下文");
        return value;
    }
}
