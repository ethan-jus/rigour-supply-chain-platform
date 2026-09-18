package com.rigour.integration.api.v1;

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
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 订货宝订单域 V1 HTTP 契约；查询、明细和第一阶段手动同步均归订单域。 */
public interface DhbOrderApi {

    String BASE_PATH = "/api/v1/integration/dhb/orders";
    String QUERY_PATH = BASE_PATH + "/{connectorId}/query";
    String CONTENT_PATH = BASE_PATH + "/{connectorId}/{orderNumber}/content";
    /** Integration 出库/发货单列表，内部调用订货宝 getShipsList。 */
    String SHIPMENT_QUERY_PATH = BASE_PATH + "/{connectorId}/shipments/query";
    /** Integration 出库/发货单详情，内部调用订货宝 getShipsContent。 */
    String SHIPMENT_CONTENT_PATH = BASE_PATH + "/{connectorId}/shipments/{shipmentNumber}/content";
    String WAIT_SHIPS_PATH = BASE_PATH + "/{connectorId}/{orderNumber}/wait-ships";
    /** Integration 退货单列表，内部调用订货宝 getReturnsList。 */
    String RETURN_QUERY_PATH = BASE_PATH + "/{connectorId}/returns/query";
    /** Integration 退货单明细，内部调用订货宝 getReturnsContent。 */
    String RETURN_CONTENT_PATH = BASE_PATH + "/{connectorId}/returns/{returnNumber}/content";
    /** Integration 收款单列表，内部调用订货宝 getReceiptsList。 */
    String RECEIPT_QUERY_PATH = BASE_PATH + "/{connectorId}/receipts/query";
    /** Integration 付款单列表，内部调用订货宝 getPaymentList。 */
    String PAYMENT_QUERY_PATH = BASE_PATH + "/{connectorId}/payments/query";
    String SYNC_RUN_PATH = BASE_PATH + "/sync-tasks/{taskId}/run";
    String MIRRORS_PATH = BASE_PATH + "/mirrors";

    @PostMapping(QUERY_PATH)
    OrderPageView queryOrders(@PathVariable("connectorId") UUID connectorId,
                              @RequestBody(required = false) OrderQueryCommand command);

    @PostMapping(CONTENT_PATH)
    OrderContentView orderContent(@PathVariable("connectorId") UUID connectorId,
                                  @PathVariable("orderNumber") String orderNumber,
                                  @RequestBody(required = false) OrderContentCommand command);

    /** 查询出库/发货单列表；status、typeId等参数沿用订货宝官方值。 */
    @PostMapping(SHIPMENT_QUERY_PATH)
    ShipmentPageView queryShipments(@PathVariable("connectorId") UUID connectorId,
                                    @RequestBody(required = false) ShipmentQueryCommand command);

    /** 查询指定出库/发货单详情；shipmentNumber 对应订货宝 ships_num。 */
    @PostMapping(SHIPMENT_CONTENT_PATH)
    ShipmentContentView shipmentContent(@PathVariable("connectorId") UUID connectorId,
                                        @PathVariable("shipmentNumber") String shipmentNumber);

    /** 查询指定订货单的已出库/已发货及待出库物流数据，对应订货宝getWaitShips。 */
    @PostMapping(WAIT_SHIPS_PATH)
    WaitShipsView waitShips(@PathVariable("connectorId") UUID connectorId,
                            @PathVariable("orderNumber") String orderNumber);

    /** 查询退货单列表；status、isApi等参数沿用订货宝官方值。 */
    @PostMapping(RETURN_QUERY_PATH)
    ReturnPageView queryReturns(@PathVariable("connectorId") UUID connectorId,
                                @RequestBody(required = false) ReturnQueryCommand command);

    /** 查询指定退货单明细；returnNumber对应订货宝 returnsSn。 */
    @PostMapping(RETURN_CONTENT_PATH)
    ReturnContentView returnContent(@PathVariable("connectorId") UUID connectorId,
                                    @PathVariable("returnNumber") String returnNumber);

    /** 查询收款单列表；查询条件对应订货宝 getReceiptsList。 */
    @PostMapping(RECEIPT_QUERY_PATH)
    ReceiptPageView queryReceipts(@PathVariable("connectorId") UUID connectorId,
                                  @RequestBody(required = false) ReceiptQueryCommand command);

    /** 查询付款单列表；查询条件对应订货宝 getPaymentList。 */
    @PostMapping(PAYMENT_QUERY_PATH)
    PaymentPageView queryPayments(@PathVariable("connectorId") UUID connectorId,
                                  @RequestBody(required = false) PaymentQueryCommand command);

    @PostMapping(SYNC_RUN_PATH)
    SyncRunView runOrderPull(@PathVariable("taskId") UUID taskId,
                             @RequestBody(required = false) SyncRunCommand command);

    @GetMapping(MIRRORS_PATH)
    List<OrderMirrorView> orderMirrors(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset);
}
