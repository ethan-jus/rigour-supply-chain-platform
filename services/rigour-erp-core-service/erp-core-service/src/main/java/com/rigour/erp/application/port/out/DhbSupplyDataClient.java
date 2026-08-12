package com.rigour.erp.application.port.out;

import com.rigour.erp.domain.model.supply.InventoryBalance;
import com.rigour.erp.domain.model.supply.PurchaseOrder;
import com.rigour.erp.domain.model.supply.PurchaseReturn;
import com.rigour.erp.domain.model.supply.Supplier;
import com.rigour.erp.domain.model.supply.SupplyDataObjectType;
import com.rigour.erp.domain.model.supply.Warehouse;
import com.rigour.erp.domain.model.supply.WarehousingReceipt;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.UUID;

/** ERP 读取 Integration 归一化供应链数据的出站端口。 */
public interface DhbSupplyDataClient {
    Collected collect(CallerIdentity caller, UUID connectorId, SupplyDataObjectType objectType,
                      int maxPages, List<String> inventoryGoodsCodes);

    record Collected(SupplyDataObjectType objectType, long total, int pages,
                     List<Supplier> suppliers, List<PurchaseOrder> purchaseOrders,
                     List<PurchaseReturn> purchaseReturns,
                     List<WarehousingReceipt> warehousingReceipts,
                     List<Warehouse> warehouses, List<InventoryBalance> inventoryBalances) {
        public Collected {
            suppliers = suppliers == null ? List.of() : List.copyOf(suppliers);
            purchaseOrders = purchaseOrders == null ? List.of() : List.copyOf(purchaseOrders);
            purchaseReturns = purchaseReturns == null ? List.of() : List.copyOf(purchaseReturns);
            warehousingReceipts = warehousingReceipts == null ? List.of() : List.copyOf(warehousingReceipts);
            warehouses = warehouses == null ? List.of() : List.copyOf(warehouses);
            inventoryBalances = inventoryBalances == null ? List.of() : List.copyOf(inventoryBalances);
        }
    }
}
