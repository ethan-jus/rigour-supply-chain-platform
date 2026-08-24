package com.rigour.erp.api.v1;

import com.rigour.erp.api.v1.model.InternalInventoryWarehouseCommand;
import com.rigour.erp.api.v1.model.InternalInventoryWarehouseView;
import com.rigour.erp.api.v1.model.MasterDataPageView;
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 仓库维护接口；仓库归属地区用于客户地区和库存履约关联。 */
public interface ErpInventoryWarehouseApi {
    String BASE_PATH = "/api/v1/erp/inventory-warehouses";

    @GetMapping(BASE_PATH)
    ApiResponse<MasterDataPageView<InternalInventoryWarehouseView>> warehouses(
            @RequestParam(defaultValue = "0") int begin,
            @RequestParam(defaultValue = "20") int step,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String warehouseName,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) Boolean defaultFlag,
            @RequestParam(required = false) String statusCode);

    @GetMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalInventoryWarehouseView> warehouse(@PathVariable("id") Long id);

    @PostMapping(BASE_PATH)
    ApiResponse<InternalInventoryWarehouseView> createWarehouse(@RequestBody InternalInventoryWarehouseCommand command);

    @PutMapping(BASE_PATH + "/{id}")
    ApiResponse<InternalInventoryWarehouseView> updateWarehouse(
            @PathVariable("id") Long id,
            @RequestBody InternalInventoryWarehouseCommand command);

    @DeleteMapping(BASE_PATH + "/{id}")
    ApiResponse<Void> deleteWarehouse(@PathVariable("id") Long id, @RequestParam int revision);
}
