package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import java.util.Optional;

/** ERP 仓库持久化端口；来源追溯复用已有主数据绑定。 */
public interface ErpInventoryWarehouseStore {
    MasterDataPageView<InternalInventoryWarehouseView> warehouses(
            String tenantId, int begin, int step, WarehouseSearchCriteria criteria);

    Optional<InternalInventoryWarehouseView> warehouse(String tenantId, Long id);

    boolean existsByCode(String tenantId, String warehouseCode);

    InternalInventoryWarehouseView create(String tenantId, String warehouseCode,
                                          InternalInventoryWarehouseCommand command, String actorId);

    InternalInventoryWarehouseView update(String tenantId, Long id,
                                          InternalInventoryWarehouseCommand command, String actorId);

    void delete(String tenantId, Long id, int revision, String actorId);

    /** 仓库列表独立筛选条件。 */
    record WarehouseSearchCriteria(String warehouseCode, String warehouseName, String regionCode,
                                   Boolean defaultFlag, String statusCode, String sourceWarehouseCode) {
        public WarehouseSearchCriteria(String warehouseCode,String warehouseName,String regionCode,Boolean defaultFlag,String statusCode) {
            this(warehouseCode,warehouseName,regionCode,defaultFlag,statusCode,null);
        }
    }
}
