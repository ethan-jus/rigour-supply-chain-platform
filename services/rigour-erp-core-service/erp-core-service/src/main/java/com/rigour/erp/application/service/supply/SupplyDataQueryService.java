package com.rigour.erp.application.service.supply;

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
import com.rigour.erp.application.port.out.SupplyDataStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.util.List;
import org.springframework.stereotype.Service;

/** ERP 采购与库存本地查询用例；查询链路不调用 Integration。 */
@Service
public final class SupplyDataQueryService {
    private final SupplyDataStore store;
    public SupplyDataQueryService(SupplyDataStore store) { this.store = store; }

    public SupplyDataPageView<SupplierView> suppliers(String tenantId, int begin, int step, String q, String status) {
        check(begin, step); return store.suppliers(tenantId, begin, step, q, status);
    }
    public SupplyDataPageView<PurchaseOrderView> purchaseOrders(String tenantId, int begin, int step, String q, String status) {
        check(begin, step); return store.purchaseOrders(tenantId, begin, step, q, status);
    }
    public PurchaseOrderDetailView purchaseOrder(String tenantId, String id) {
        checkId(id); return store.purchaseOrder(tenantId, id);
    }
    public SupplyDataPageView<PurchaseReturnView> purchaseReturns(String tenantId, int begin, int step, String q, String status) {
        check(begin, step); return store.purchaseReturns(tenantId, begin, step, q, status);
    }
    public PurchaseReturnDetailView purchaseReturn(String tenantId, String id) {
        checkId(id); return store.purchaseReturn(tenantId, id);
    }
    public SupplyDataPageView<WarehousingReceiptView> warehousingReceipts(String tenantId, int begin, int step, String q, String status) {
        check(begin, step); return store.warehousingReceipts(tenantId, begin, step, q, status);
    }
    public WarehousingReceiptDetailView warehousingReceipt(String tenantId, String id) {
        checkId(id); return store.warehousingReceipt(tenantId, id);
    }
    public SupplyDataPageView<WarehouseView> warehouses(String tenantId, int begin, int step, String q, String status) {
        check(begin, step); return store.warehouses(tenantId, begin, step, q, status);
    }
    public SupplyDataPageView<InventoryBalanceView> inventory(String tenantId, int begin, int step, String q, String warehouseCode) {
        check(begin, step); return store.inventory(tenantId, begin, step, q, warehouseCode);
    }

    private static void check(int begin, int step) {
        AuthorizationContext.requirePermission("erp:supply:read");
        if (begin < 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "begin不能小于0", List.of());
        if (step < 1 || step > 1000) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "step必须在1到1000之间", List.of());
        }
    }

    private static void checkId(String id) {
        AuthorizationContext.requirePermission("erp:supply:read");
        if (id == null || id.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "详情ID不能为空", List.of());
        }
    }
}
