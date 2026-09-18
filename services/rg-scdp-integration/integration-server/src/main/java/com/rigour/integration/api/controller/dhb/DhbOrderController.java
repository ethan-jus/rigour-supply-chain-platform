package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.api.v1.DhbOrderApi;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.PaymentPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.PaymentQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ReceiptPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReceiptQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ReturnQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ShipmentQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.WaitShipsView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.integration.application.service.dhb.DhbIntegrationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝订单域 HTTP 边界；订单镜像仍是 Integration 技术投影。 */
@RestController
@RequestMapping(DhbOrderApi.BASE_PATH)
public final class DhbOrderController implements DhbOrderApi {

    private final DhbIntegrationService service;

    public DhbOrderController(DhbIntegrationService service) {
        this.service = service;
    }

    @Override
    @PostMapping("/{connectorId}/query")
    public OrderPageView queryOrders(@PathVariable("connectorId") UUID connectorId,
                                     @RequestBody(required = false) OrderQueryCommand command) {
        return service.orders(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/{orderNumber}/content")
    public OrderContentView orderContent(@PathVariable("connectorId") UUID connectorId,
                                         @PathVariable("orderNumber") String orderNumber,
                                         @RequestBody(required = false) OrderContentCommand command) {
        return service.orderContent(connectorId, orderNumber, command);
    }

    @Override
    @PostMapping("/{connectorId}/shipments/query")
    public ShipmentPageView queryShipments(@PathVariable("connectorId") UUID connectorId,
                                           @RequestBody(required = false) ShipmentQueryCommand command) {
        return service.shipments(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/shipments/{shipmentNumber}/content")
    public ShipmentContentView shipmentContent(@PathVariable("connectorId") UUID connectorId,
                                                @PathVariable("shipmentNumber") String shipmentNumber) {
        return service.shipmentContent(connectorId, shipmentNumber);
    }

    @Override
    @PostMapping("/{connectorId}/{orderNumber}/wait-ships")
    public WaitShipsView waitShips(@PathVariable("connectorId") UUID connectorId,
                                   @PathVariable("orderNumber") String orderNumber) {
        return service.waitShips(connectorId, orderNumber);
    }

    @Override
    @PostMapping("/{connectorId}/returns/query")
    public ReturnPageView queryReturns(@PathVariable("connectorId") UUID connectorId,
                                       @RequestBody(required = false) ReturnQueryCommand command) {
        return service.returns(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/returns/{returnNumber}/content")
    public ReturnContentView returnContent(@PathVariable("connectorId") UUID connectorId,
                                           @PathVariable("returnNumber") String returnNumber) {
        return service.returnContent(connectorId, returnNumber);
    }

    @Override
    @PostMapping("/{connectorId}/receipts/query")
    public ReceiptPageView queryReceipts(@PathVariable("connectorId") UUID connectorId,
                                         @RequestBody(required = false) ReceiptQueryCommand command) {
        return service.receipts(connectorId, command);
    }

    @Override
    @PostMapping("/{connectorId}/payments/query")
    public PaymentPageView queryPayments(@PathVariable("connectorId") UUID connectorId,
                                         @RequestBody(required = false) PaymentQueryCommand command) {
        return service.payments(connectorId, command);
    }

    @Override
    @PostMapping("/sync-tasks/{taskId}/run")
    public SyncRunView runOrderPull(@PathVariable("taskId") UUID taskId,
                                    @RequestBody(required = false) SyncRunCommand command) {
        return service.runOrderPull(taskId, command);
    }

    @Override
    @GetMapping("/mirrors")
    public List<OrderMirrorView> orderMirrors(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return service.orderMirrors(limit, offset);
    }
}
