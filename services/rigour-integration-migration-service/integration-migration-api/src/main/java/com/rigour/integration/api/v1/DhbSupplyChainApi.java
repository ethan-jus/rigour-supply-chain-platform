package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbInventoryQueryCommand;
import com.rigour.integration.api.v1.model.DhbInventoryView;
import com.rigour.integration.api.v1.model.DhbPurchaseOrderView;
import com.rigour.integration.api.v1.model.DhbPurchaseReturnView;
import com.rigour.integration.api.v1.model.DhbSupplierView;
import com.rigour.integration.api.v1.model.DhbSupplyPageQueryCommand;
import com.rigour.integration.api.v1.model.DhbSupplyPageView;
import com.rigour.integration.api.v1.model.DhbWarehouseView;
import com.rigour.integration.api.v1.model.DhbWarehousingReceiptView;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 订货宝采购与库存域 V1 归一化契约；调用方不能接触订货宝 f/v 报文与凭据。 */
public interface DhbSupplyChainApi {
    String BASE_PATH = "/api/v1/integration/dhb/supply-chain";

    /** 分页获取并归一化订货宝供应商；地址、联系方式、税号和银行账号按当前内部使用要求返回完整值。 */
    @PostMapping(BASE_PATH + "/{connectorId}/suppliers/query")
    DhbSupplyPageView<DhbSupplierView> suppliers(@PathVariable UUID connectorId,
                                                 @RequestBody(required = false) DhbSupplyPageQueryCommand command);

    /** 分页获取采购单列表，并逐单合并 getPurchaseContent 明细。 */
    @PostMapping(BASE_PATH + "/{connectorId}/purchase-orders/query")
    DhbSupplyPageView<DhbPurchaseOrderView> purchaseOrders(
            @PathVariable UUID connectorId,
            @RequestBody(required = false) DhbSupplyPageQueryCommand command);

    /** 分页获取采购退货列表，并逐单合并退货明细。 */
    @PostMapping(BASE_PATH + "/{connectorId}/purchase-returns/query")
    DhbSupplyPageView<DhbPurchaseReturnView> purchaseReturns(
            @PathVariable UUID connectorId,
            @RequestBody(required = false) DhbSupplyPageQueryCommand command);

    /** 分页获取入库单列表，并逐单合并入库明细。 */
    @PostMapping(BASE_PATH + "/{connectorId}/warehousing-receipts/query")
    DhbSupplyPageView<DhbWarehousingReceiptView> warehousingReceipts(
            @PathVariable UUID connectorId,
            @RequestBody(required = false) DhbSupplyPageQueryCommand command);

    /** 分页获取并归一化订货宝仓库档案。 */
    @PostMapping(BASE_PATH + "/{connectorId}/warehouses/query")
    DhbSupplyPageView<DhbWarehouseView> warehouses(
            @PathVariable UUID connectorId,
            @RequestBody(required = false) DhbSupplyPageQueryCommand command);

    /** 按商品编码批量获取订货宝库存，单次最多 100 个编码。 */
    @PostMapping(BASE_PATH + "/{connectorId}/inventory/query")
    DhbInventoryView inventory(@PathVariable UUID connectorId,
                               @RequestBody DhbInventoryQueryCommand command);
}
