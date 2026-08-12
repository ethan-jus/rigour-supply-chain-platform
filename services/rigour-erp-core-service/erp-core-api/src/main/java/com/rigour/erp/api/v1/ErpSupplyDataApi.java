package com.rigour.erp.api.v1;

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
import com.rigour.shared.core.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** ERP 采购与库存 V1 契约；查询只读取 ERP 本地表。 */
public interface ErpSupplyDataApi {
    String BASE_PATH = "/api/v1/erp";

    /** 分页查询 ERP 本地供应商档案；银行账号按当前内部使用要求返回完整值。 */
    @GetMapping(BASE_PATH + "/suppliers")
    ApiResponse<SupplyDataPageView<SupplierView>> suppliers(@RequestParam int begin, @RequestParam int step,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) String status);

    /** 分页查询 ERP 本地采购单投影。 */
    @GetMapping(BASE_PATH + "/purchase-orders")
    ApiResponse<SupplyDataPageView<PurchaseOrderView>> purchaseOrders(@RequestParam int begin, @RequestParam int step,
                                                             @RequestParam(required = false) String q,
                                                             @RequestParam(required = false) String status);

    /** 查询 ERP 本地采购单详情及明细。 */
    @GetMapping(BASE_PATH + "/purchase-orders/{id}")
    ApiResponse<PurchaseOrderDetailView> purchaseOrder(@PathVariable String id);

    /** 分页查询 ERP 本地采购退货单投影。 */
    @GetMapping(BASE_PATH + "/purchase-returns")
    ApiResponse<SupplyDataPageView<PurchaseReturnView>> purchaseReturns(@RequestParam int begin, @RequestParam int step,
                                                               @RequestParam(required = false) String q,
                                                               @RequestParam(required = false) String status);

    /** 查询 ERP 本地采购退货单详情及明细。 */
    @GetMapping(BASE_PATH + "/purchase-returns/{id}")
    ApiResponse<PurchaseReturnDetailView> purchaseReturn(@PathVariable String id);

    /** 分页查询 ERP 本地入库单投影。 */
    @GetMapping(BASE_PATH + "/warehousing-receipts")
    ApiResponse<SupplyDataPageView<WarehousingReceiptView>> warehousingReceipts(
            @RequestParam int begin, @RequestParam int step,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status);

    /** 查询 ERP 本地入库单详情、明细及关联采购单。 */
    @GetMapping(BASE_PATH + "/warehousing-receipts/{id}")
    ApiResponse<WarehousingReceiptDetailView> warehousingReceipt(@PathVariable String id);

    /** 分页查询 ERP 本地仓库档案。 */
    @GetMapping(BASE_PATH + "/warehouses")
    ApiResponse<SupplyDataPageView<WarehouseView>> warehouses(@RequestParam int begin, @RequestParam int step,
                                                     @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String status);

    /** 按仓库和商品条件分页查询 ERP 本地库存余额快照。 */
    @GetMapping(BASE_PATH + "/inventory-balances")
    ApiResponse<SupplyDataPageView<InventoryBalanceView>> inventory(@RequestParam int begin, @RequestParam int step,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) String warehouseCode);

}
