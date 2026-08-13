package com.rigour.erp.application.port.out;

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
import com.rigour.erp.domain.model.supply.InventoryBalance;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ERP 供应链 MyBatis 持久化端口；实现必须在每条查询中绑定 tenantId。 */
public interface SupplyDataStore {
    SupplyDataPageView<SupplierView> suppliers(String tenantId, int begin, int step, String query, String status);
    SupplyDataPageView<PurchaseOrderView> purchaseOrders(String tenantId, int begin, int step, String query, String status);
    PurchaseOrderDetailView purchaseOrder(String tenantId, String id);
    SupplyDataPageView<PurchaseReturnView> purchaseReturns(String tenantId, int begin, int step, String query, String status);
    PurchaseReturnDetailView purchaseReturn(String tenantId, String id);
    SupplyDataPageView<WarehousingReceiptView> warehousingReceipts(String tenantId, int begin, int step, String query, String status);
    WarehousingReceiptDetailView warehousingReceipt(String tenantId, String id);
    SupplyDataPageView<WarehouseView> warehouses(String tenantId, int begin, int step, String query, String status);
    SupplyDataPageView<InventoryBalanceView> inventory(String tenantId, int begin, int step, String query, String warehouseCode);

    List<String> sourceProductCodes(String tenantId);
    UUID startRun(String tenantId, UUID connectorId, UUID actorId, SupplyDataObjectType type, int maxPages);
    /** 定时同步使用同一批次与互斥锁模型，但在批次中记录 SCHEDULED 触发方式。 */
    default UUID startScheduledRun(String tenantId, UUID connectorId, UUID actorId,
                                   SupplyDataObjectType type, int maxPages) {
        return startRun(tenantId, connectorId, actorId, type, maxPages);
    }
    ImportResult importSupplier(String tenantId, UUID runId, Supplier item);
    ImportResult importPurchaseOrder(String tenantId, UUID runId, PurchaseOrder item);
    ImportResult importPurchaseReturn(String tenantId, UUID runId, PurchaseReturn item);
    ImportResult importWarehousingReceipt(String tenantId, UUID runId, WarehousingReceipt item);
    ImportResult importWarehouse(String tenantId, UUID runId, Warehouse item);
    ImportResult importInventory(String tenantId, UUID runId, InventoryBalance item);
    /** 仅在完整且无拒绝记录的全量快照后标记来源存在/缺失，不删除业务记录。 */
    void reconcileSourcePresence(String tenantId, UUID runId, Map<String, Set<String>> seenSourceIds);
    void completeRun(String tenantId, UUID runId, RunStatistics statistics);
    void failRun(String tenantId, UUID runId, RunStatistics statistics, RuntimeException error);

    record ImportResult(long created, long changed, long duplicates, long rejected) {
        public static ImportResult oneCreated() { return new ImportResult(1, 0, 0, 0); }
        public static ImportResult oneChanged() { return new ImportResult(0, 1, 0, 0); }
        public static ImportResult oneDuplicate() { return new ImportResult(0, 0, 1, 0); }
        public static ImportResult oneRejected() { return new ImportResult(0, 0, 0, 1); }
    }
    /** 本批次交给 ERP 导入流程并完成统计的记录总数。 */
    record RunStatistics(long fetched, long created, long changed, long duplicates,
                         long rejected, int pages) { }
}
