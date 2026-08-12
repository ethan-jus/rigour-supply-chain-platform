package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbSupplyChainApi;
import com.rigour.integration.api.v1.model.DhbInventoryQueryCommand;
import com.rigour.integration.api.v1.model.DhbInventoryView;
import com.rigour.integration.api.v1.model.DhbPurchaseOrderView;
import com.rigour.integration.api.v1.model.DhbPurchaseReturnView;
import com.rigour.integration.api.v1.model.DhbSupplierView;
import com.rigour.integration.api.v1.model.DhbSupplyPageQueryCommand;
import com.rigour.integration.api.v1.model.DhbSupplyPageView;
import com.rigour.integration.api.v1.model.DhbWarehouseView;
import com.rigour.integration.api.v1.model.DhbWarehousingReceiptView;
import com.rigour.integration.application.service.dhb.DhbSupplyChainService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration 供应链内部 HTTP 边界。 */
@RestController
@RequestMapping
public final class DhbSupplyChainController implements DhbSupplyChainApi {
    private final DhbSupplyChainService service;

    public DhbSupplyChainController(DhbSupplyChainService service) { this.service = service; }

    /** {@inheritDoc} */
    @Override public DhbSupplyPageView<DhbSupplierView> suppliers(
            @PathVariable("connectorId") UUID connectorId, DhbSupplyPageQueryCommand command) {
        return service.suppliers(connectorId, command);
    }
    /** {@inheritDoc} */
    @Override public DhbSupplyPageView<DhbPurchaseOrderView> purchaseOrders(
            @PathVariable("connectorId") UUID connectorId, DhbSupplyPageQueryCommand command) {
        return service.purchaseOrders(connectorId, command);
    }
    /** {@inheritDoc} */
    @Override public DhbSupplyPageView<DhbPurchaseReturnView> purchaseReturns(
            @PathVariable("connectorId") UUID connectorId, DhbSupplyPageQueryCommand command) {
        return service.purchaseReturns(connectorId, command);
    }
    /** {@inheritDoc} */
    @Override public DhbSupplyPageView<DhbWarehousingReceiptView> warehousingReceipts(
            @PathVariable("connectorId") UUID connectorId, DhbSupplyPageQueryCommand command) {
        return service.warehousingReceipts(connectorId, command);
    }
    /** {@inheritDoc} */
    @Override public DhbSupplyPageView<DhbWarehouseView> warehouses(
            @PathVariable("connectorId") UUID connectorId, DhbSupplyPageQueryCommand command) {
        return service.warehouses(connectorId, command);
    }
    /** {@inheritDoc} */
    @Override public DhbInventoryView inventory(
            @PathVariable("connectorId") UUID connectorId, DhbInventoryQueryCommand command) {
        return service.inventory(connectorId, command);
    }
}
