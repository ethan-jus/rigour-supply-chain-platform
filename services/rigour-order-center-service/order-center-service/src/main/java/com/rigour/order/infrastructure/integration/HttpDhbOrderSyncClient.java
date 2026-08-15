package com.rigour.order.infrastructure.integration;

import com.rigour.integration.api.v1.DhbOrderApi;
import com.rigour.integration.api.v1.model.DhbApiModels;
import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.order.api.v1.model.DhbOrderSyncMode;
import com.rigour.order.application.port.out.DhbOrderSyncClient;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.RequestContext;
import com.rigour.shared.context.RequestHeaders;
import com.rigour.shared.context.TrustedContextSigner;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** 通过当前 Integration V1 订单契约查询订货宝，并转换为订单中心本地导入批次。 */
public final class HttpDhbOrderSyncClient implements DhbOrderSyncClient {
    private static final Logger log = LoggerFactory.getLogger(HttpDhbOrderSyncClient.class);
    private static final int PAGE_SIZE = 100;
    private static final ZoneId DHB_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestClient restClient;
    private final TrustedContextSigner signer;
    private final ObjectMapper objectMapper;
    private final URI integrationBaseUri;

    public HttpDhbOrderSyncClient(RestClient.Builder builder, TrustedContextSigner signer,
                                  ObjectMapper objectMapper, String integrationBaseUrl) {
        this.restClient = Objects.requireNonNull(builder, "RestClient.Builder不能为空").build();
        this.signer = Objects.requireNonNull(signer, "TrustedContextSigner不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper不能为空");
        this.integrationBaseUri = baseUri(integrationBaseUrl);
    }

    @Override
    public Collected collect(CallerIdentity caller, UUID connectorId, DhbOrderSyncCommand command) {
        Objects.requireNonNull(caller, "caller不能为空");
        Objects.requireNonNull(connectorId, "connectorId不能为空");
        DhbOrderSyncCommand effective = command == null ? new DhbOrderSyncCommand(null, null) : command;
        return switch (effective.scope()) {
            case ORDER -> collectOrdersOnly(caller, connectorId, effective);
            case RETURN -> collectReturnsOnly(caller, connectorId, effective);
            case SHIPMENT -> collectShipmentsOnly(caller, connectorId, effective);
            case SHIPMENT_LOGISTICS -> collectShipmentLogisticsOnly(caller, connectorId, effective);
            case RECEIPT -> collectReceiptsOnly(caller, connectorId, effective);
            case PAYMENT -> collectPaymentsOnly(caller, connectorId, effective);
            case ALL -> collectAll(caller, connectorId, effective);
        };
    }

    /** 历史聚合同步：保留订单域当前已接入对象的完整调用链。 */
    private Collected collectAll(CallerIdentity caller, UUID connectorId,
                                 DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.OrderItem> orders = new ArrayList<>();
        List<DhbOrderImportBatch.ShipmentItem> shipments = new ArrayList<>();
        List<DhbOrderImportBatch.ShipmentLogisticsItem> shipmentLogistics = new ArrayList<>();
        List<DhbOrderImportBatch.ReturnItem> returns = new ArrayList<>();
        List<DhbOrderImportBatch.FinancialItem> financialDocuments = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        long total = 0;

        long orderExpected = -1;
        Set<String> orderKeys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            requiredResponse(page, "订单列表");
            orderExpected = expectedTotal(orderExpected, page.total(), "订单");
            List<DhbApiModels.OrderView> items = pageItems(page.items(), page.total(), begin, "订单");
            if (items.isEmpty()) break;
            for (DhbApiModels.OrderView summary : items) {
                String orderNumber = required(summaryOrderNumber(summary), "orderNumber");
                requireUnique(orderKeys, orderNumber, "订单");
                DhbApiModels.OrderContentView detail = effective.includeDetails()
                        ? content(caller, connectorId, orderNumber) : null;
                orders.add(order(summary, detail));
                DhbApiModels.WaitShipsView waitShips = waitShips(caller, connectorId, orderNumber);
                shipmentLogistics.add(logisticsSnapshot(orderNumber, waitShips));
            }
            if (begin + items.size() >= orderExpected) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("订单", effective.maxPages());
        }
        requireComplete(orderKeys, orderExpected, "订单");
        total += orderExpected;
        completed.add("ORDER");
        completed.add("SHIPMENT_LOGISTICS");
        if (effective.includeDetails()) completed.add("ORDER_DETAIL");

        long shipmentExpected = -1;
        Set<String> shipmentKeys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ShipmentPageView page = queryShipments(caller, connectorId, effective, begin);
            requiredResponse(page, "发货单列表");
            shipmentExpected = expectedTotal(shipmentExpected, page.total(), "发货单");
            List<DhbApiModels.ShipmentView> items = pageItems(page.items(), page.total(), begin, "发货单");
            if (items.isEmpty()) break;
            for (DhbApiModels.ShipmentView summary : items) {
                String shipmentNumber = required(summaryShipmentNumber(summary), "shipmentNumber");
                requireUnique(shipmentKeys, shipmentNumber, "发货单");
                DhbApiModels.ShipmentContentView detail = effective.includeDetails()
                        ? shipmentContent(caller, connectorId, shipmentNumber) : null;
                shipments.add(shipment(summary, detail));
            }
            if (begin + items.size() >= shipmentExpected) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("发货单", effective.maxPages());
        }
        requireComplete(shipmentKeys, shipmentExpected, "发货单");
        total += shipmentExpected;
        completed.add("SHIPMENT");
        if (effective.includeDetails()) completed.add("SHIPMENT_DETAIL");

        long returnExpected = -1;
        Set<String> returnKeys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ReturnPageView page = queryReturns(caller, connectorId, effective, begin);
            requiredResponse(page, "退货单列表");
            returnExpected = expectedTotal(returnExpected, page.total(), "退货单");
            List<DhbApiModels.ReturnView> items = pageItems(page.items(), page.total(), begin, "退货单");
            if (items.isEmpty()) break;
            for (DhbApiModels.ReturnView summary : items) {
                String returnNumber = required(summaryReturnNumber(summary), "returnNumber");
                requireUnique(returnKeys, returnNumber, "退货单");
                DhbApiModels.ReturnContentView detail = effective.includeDetails()
                        ? returnContent(caller, connectorId, returnNumber) : null;
                returns.add(returnItem(summary, detail));
            }
            if (begin + items.size() >= returnExpected) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("退货单", effective.maxPages());
        }
        requireComplete(returnKeys, returnExpected, "退货单");
        total += returnExpected;
        completed.add("RETURN");
        if (effective.includeDetails()) completed.add("RETURN_DETAIL");

        long receiptExpected = -1;
        Set<String> receiptKeys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ReceiptPageView page = queryReceipts(caller, connectorId, effective, begin);
            requiredResponse(page, "收款单列表");
            receiptExpected = expectedTotal(receiptExpected, page.total(), "收款单");
            List<DhbApiModels.ReceiptView> items = pageItems(page.items(), page.total(), begin, "收款单");
            if (items.isEmpty()) break;
            for (DhbApiModels.ReceiptView item : items) {
                requireUnique(receiptKeys, required(defaultText(item.receiptNumber(), item.sourceId()),
                        "receiptNumber"), "收款单");
                financialDocuments.add(receipt(item));
            }
            if (begin + items.size() >= receiptExpected) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("收款单", effective.maxPages());
        }
        requireComplete(receiptKeys, receiptExpected, "收款单");
        total += receiptExpected;
        completed.add("RECEIPT");

        if (effective.mode() == DhbOrderSyncMode.FULL) {
            long paymentExpected = -1;
            Set<String> paymentKeys = new LinkedHashSet<>();
            for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
                int begin = pageNumber * PAGE_SIZE;
                DhbApiModels.PaymentPageView page = queryPayments(caller, connectorId, effective, begin);
                requiredResponse(page, "付款单列表");
                paymentExpected = expectedTotal(paymentExpected, page.total(), "付款单");
                List<DhbApiModels.PaymentView> items = pageItems(page.items(), page.total(), begin, "付款单");
                if (items.isEmpty()) break;
                for (DhbApiModels.PaymentView item : items) {
                    requireUnique(paymentKeys, required(defaultText(item.paymentNumber(), item.sourceId()),
                            "paymentNumber"), "付款单");
                    financialDocuments.add(payment(item));
                }
                if (begin + items.size() >= paymentExpected) break;
                if (pageNumber + 1 >= effective.maxPages()) throw maxPages("付款单", effective.maxPages());
            }
            requireComplete(paymentKeys, paymentExpected, "付款单");
            total += paymentExpected;
        } else {
            log.debug("订货宝增量同步跳过付款单；付款单将在下一次FULL对账中重新核对");
        }
        completed.add("PAYMENT");

        DhbOrderImportBatch batch = new DhbOrderImportBatch(
                orders, shipments, shipmentLogistics, returns, financialDocuments);
        return new Collected(UUID.randomUUID(), "ORDER_DOMAIN", total, completed, batch);
    }

    /** 订单页同步：只访问订货宝订单列表和可选的订单详情接口。 */
    private Collected collectOrdersOnly(CallerIdentity caller, UUID connectorId,
                                        DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.OrderItem> orders = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        long total = -1;
        Set<String> keys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            requiredResponse(page, "订单列表");
            total = expectedTotal(total, page.total(), "订单");
            List<DhbApiModels.OrderView> items = pageItems(page.items(), page.total(), begin, "订单");
            if (items.isEmpty()) break;
            for (DhbApiModels.OrderView summary : items) {
                String orderNumber = required(summaryOrderNumber(summary), "orderNumber");
                requireUnique(keys, orderNumber, "订单");
                DhbApiModels.OrderContentView detail = effective.includeDetails()
                        ? content(caller, connectorId, orderNumber) : null;
                orders.add(order(summary, detail));
            }
            if (begin + items.size() >= total) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("订单", effective.maxPages());
        }
        requireComplete(keys, total, "订单");
        completed.add("ORDER");
        if (effective.includeDetails()) completed.add("ORDER_DETAIL");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(orders, null, null, null, null);
        return new Collected(UUID.randomUUID(), "ORDER", total, completed, batch);
    }

    /** 退货页同步：只访问订货宝退货单列表和可选的退货单详情接口。 */
    private Collected collectReturnsOnly(CallerIdentity caller, UUID connectorId,
                                         DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.ReturnItem> returns = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        long total = -1;
        Set<String> keys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ReturnPageView page = queryReturns(caller, connectorId, effective, begin);
            requiredResponse(page, "退货单列表");
            total = expectedTotal(total, page.total(), "退货单");
            List<DhbApiModels.ReturnView> items = pageItems(page.items(), page.total(), begin, "退货单");
            if (items.isEmpty()) break;
            for (DhbApiModels.ReturnView summary : items) {
                String returnNumber = required(summaryReturnNumber(summary), "returnNumber");
                requireUnique(keys, returnNumber, "退货单");
                DhbApiModels.ReturnContentView detail = effective.includeDetails()
                        ? returnContent(caller, connectorId, returnNumber) : null;
                returns.add(returnItem(summary, detail));
            }
            if (begin + items.size() >= total) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("退货单", effective.maxPages());
        }
        requireComplete(keys, total, "退货单");
        completed.add("RETURN");
        if (effective.includeDetails()) completed.add("RETURN_DETAIL");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, null, null, returns, null);
        return new Collected(UUID.randomUUID(), "RETURN", total, completed, batch);
    }

    /** 出库/发货页同步：只访问订货宝出库/发货单列表和可选详情。 */
    private Collected collectShipmentsOnly(CallerIdentity caller, UUID connectorId,
                                           DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.ShipmentItem> shipments = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        long total = -1;
        Set<String> keys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ShipmentPageView page = queryShipments(caller, connectorId, effective, begin);
            requiredResponse(page, "发货单列表");
            total = expectedTotal(total, page.total(), "发货单");
            List<DhbApiModels.ShipmentView> items = pageItems(page.items(), page.total(), begin, "发货单");
            if (items.isEmpty()) break;
            for (DhbApiModels.ShipmentView summary : items) {
                String shipmentNumber = required(summaryShipmentNumber(summary), "shipmentNumber");
                requireUnique(keys, shipmentNumber, "发货单");
                DhbApiModels.ShipmentContentView detail = effective.includeDetails()
                        ? shipmentContent(caller, connectorId, shipmentNumber) : null;
                shipments.add(shipment(summary, detail));
            }
            if (begin + items.size() >= total) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("发货单", effective.maxPages());
        }
        requireComplete(keys, total, "发货单");
        completed.add("SHIPMENT");
        if (effective.includeDetails()) completed.add("SHIPMENT_DETAIL");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, shipments, null, null, null);
        return new Collected(UUID.randomUUID(), "SHIPMENT", total, completed, batch);
    }

    /** 物流页同步：先通过订单列表发现订单号，再只落库getWaitShips物流快照。 */
    private Collected collectShipmentLogisticsOnly(CallerIdentity caller, UUID connectorId,
                                                   DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.ShipmentLogisticsItem> shipmentLogistics = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        long total = -1;
        Set<String> keys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            requiredResponse(page, "订单列表");
            total = expectedTotal(total, page.total(), "物流订单");
            List<DhbApiModels.OrderView> items = pageItems(page.items(), page.total(), begin, "物流订单");
            if (items.isEmpty()) break;
            for (DhbApiModels.OrderView summary : items) {
                String orderNumber = required(summaryOrderNumber(summary), "orderNumber");
                requireUnique(keys, orderNumber, "物流订单");
                shipmentLogistics.add(logisticsSnapshot(orderNumber,
                        waitShips(caller, connectorId, orderNumber)));
            }
            if (begin + items.size() >= total) break;
            if (pageNumber + 1 >= effective.maxPages()) throw maxPages("物流订单", effective.maxPages());
        }
        requireComplete(keys, total, "物流订单");
        completed.add("SHIPMENT_LOGISTICS");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, null, shipmentLogistics, null, null);
        return new Collected(UUID.randomUUID(), "SHIPMENT_LOGISTICS", total, completed, batch);
    }

    /** 收款页同步：只访问订货宝收款单列表。 */
    private Collected collectReceiptsOnly(CallerIdentity caller, UUID connectorId,
                                          DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.FinancialItem> financialDocuments = new ArrayList<>();
        long total = collectFinancialPage(caller, connectorId, effective, financialDocuments, true);
        Set<String> completed = new LinkedHashSet<>();
        completed.add("RECEIPT");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, null, null, null, financialDocuments);
        return new Collected(UUID.randomUUID(), "RECEIPT", total, completed, batch);
    }

    /** 付款页同步：只访问订货宝付款单列表。 */
    private Collected collectPaymentsOnly(CallerIdentity caller, UUID connectorId,
                                          DhbOrderSyncCommand effective) {
        List<DhbOrderImportBatch.FinancialItem> financialDocuments = new ArrayList<>();
        long total = collectFinancialPage(caller, connectorId, effective, financialDocuments, false);
        Set<String> completed = new LinkedHashSet<>();
        completed.add("PAYMENT");
        DhbOrderImportBatch batch = new DhbOrderImportBatch(null, null, null, null, financialDocuments);
        return new Collected(UUID.randomUUID(), "PAYMENT", total, completed, batch);
    }

    private long collectFinancialPage(CallerIdentity caller, UUID connectorId,
                                      DhbOrderSyncCommand effective,
                                      List<DhbOrderImportBatch.FinancialItem> financialDocuments,
                                      boolean receipts) {
        long total = -1;
        Set<String> keys = new LinkedHashSet<>();
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            if (receipts) {
                DhbApiModels.ReceiptPageView page = queryReceipts(caller, connectorId, effective, begin);
                requiredResponse(page, "收款单列表");
                total = expectedTotal(total, page.total(), "收款单");
                List<DhbApiModels.ReceiptView> items = pageItems(page.items(), page.total(), begin, "收款单");
                if (items.isEmpty()) break;
                for (DhbApiModels.ReceiptView item : items) {
                    requireUnique(keys, required(defaultText(item.receiptNumber(), item.sourceId()),
                            "receiptNumber"), "收款单");
                    financialDocuments.add(receipt(item));
                }
                if (begin + items.size() >= total) break;
                if (pageNumber + 1 >= effective.maxPages()) throw maxPages("收款单", effective.maxPages());
            } else {
                DhbApiModels.PaymentPageView page = queryPayments(caller, connectorId, effective, begin);
                requiredResponse(page, "付款单列表");
                total = expectedTotal(total, page.total(), "付款单");
                List<DhbApiModels.PaymentView> items = pageItems(page.items(), page.total(), begin, "付款单");
                if (items.isEmpty()) break;
                for (DhbApiModels.PaymentView item : items) {
                    requireUnique(keys, required(defaultText(item.paymentNumber(), item.sourceId()),
                            "paymentNumber"), "付款单");
                    financialDocuments.add(payment(item));
                }
                if (begin + items.size() >= total) break;
                if (pageNumber + 1 >= effective.maxPages()) throw maxPages("付款单", effective.maxPages());
            }
        }
        requireComplete(keys, total, receipts ? "收款单" : "付款单");
        return total;
    }

    private DhbApiModels.OrderPageView query(CallerIdentity caller, UUID connectorId,
                                             DhbOrderSyncCommand command, int begin) {
        DhbApiModels.OrderQueryCommand request = new DhbApiModels.OrderQueryCommand(
                begin, PAGE_SIZE, "all", null, null, incrementalFrom(command), incrementalTo(command),
                "all", "all", null, null);
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.QUERY_PATH)
                .buildAndExpand(connectorId)
                .encode()
                .toUri();
        return post(caller, uri, request, DhbApiModels.OrderPageView.class);
    }

    private DhbApiModels.OrderContentView content(CallerIdentity caller, UUID connectorId,
                                                   String orderNumber) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.CONTENT_PATH)
                .buildAndExpand(connectorId, orderNumber)
                .encode()
                .toUri();
        return requiredResponse(post(caller, uri, new DhbApiModels.OrderContentCommand(false, false),
                DhbApiModels.OrderContentView.class), "订单详情");
    }

    /** 通过Integration调用订货宝getShipsList，查询独立出库/发货单。 */
    private DhbApiModels.ShipmentPageView queryShipments(CallerIdentity caller, UUID connectorId,
                                                          DhbOrderSyncCommand command, int begin) {
        DhbApiModels.ShipmentQueryCommand request = new DhbApiModels.ShipmentQueryCommand(
                begin, PAGE_SIZE, null, "F,T", null, null, null,
                incrementalFrom(command), incrementalTo(command), null, null, null);
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.SHIPMENT_QUERY_PATH)
                .buildAndExpand(connectorId)
                .encode()
                .toUri();
        return post(caller, uri, request, DhbApiModels.ShipmentPageView.class);
    }

    /** 通过Integration调用订货宝getShipsContent，补齐独立出库/发货单详情。 */
    private DhbApiModels.ShipmentContentView shipmentContent(CallerIdentity caller, UUID connectorId,
                                                               String shipmentNumber) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.SHIPMENT_CONTENT_PATH)
                .buildAndExpand(connectorId, shipmentNumber)
                .encode()
                .toUri();
        return requiredResponse(post(caller, uri, Map.of(), DhbApiModels.ShipmentContentView.class),
                "发货单详情");
    }

    /** 通过Integration调用订货宝getWaitShips，查询指定订单的出库/发货物流。 */
    private DhbApiModels.WaitShipsView waitShips(CallerIdentity caller, UUID connectorId,
                                                  String orderNumber) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.WAIT_SHIPS_PATH)
                .buildAndExpand(connectorId, orderNumber)
                .encode()
                .toUri();
        return requiredResponse(post(caller, uri, Map.of(), DhbApiModels.WaitShipsView.class),
                "订单物流详情");
    }

    /** 通过Integration调用订货宝getReturnsList，查询退货单列表。 */
    private DhbApiModels.ReturnPageView queryReturns(CallerIdentity caller, UUID connectorId,
                                                      DhbOrderSyncCommand command, int begin) {
        DhbApiModels.ReturnQueryCommand request = new DhbApiModels.ReturnQueryCommand(
                begin, PAGE_SIZE, null, "All", null, null,
                incrementalFrom(command), incrementalTo(command), null, null);
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.RETURN_QUERY_PATH)
                .buildAndExpand(connectorId)
                .encode()
                .toUri();
        return post(caller, uri, request, DhbApiModels.ReturnPageView.class);
    }

    /** 通过Integration调用订货宝getReturnsContent，补齐退货单商品明细。 */
    private DhbApiModels.ReturnContentView returnContent(CallerIdentity caller, UUID connectorId,
                                                          String returnNumber) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.RETURN_CONTENT_PATH)
                .buildAndExpand(connectorId, returnNumber)
                .encode()
                .toUri();
        return requiredResponse(post(caller, uri, Map.of(), DhbApiModels.ReturnContentView.class),
                "退货单详情");
    }

    /** 通过Integration调用订货宝getReceiptsList，查询收款单列表。 */
    private DhbApiModels.ReceiptPageView queryReceipts(CallerIdentity caller, UUID connectorId,
                                                        DhbOrderSyncCommand command, int begin) {
        DhbApiModels.ReceiptQueryCommand request = new DhbApiModels.ReceiptQueryCommand(
                null, begin, PAGE_SIZE, null, null, incrementalFrom(command), "all");
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.RECEIPT_QUERY_PATH)
                .buildAndExpand(connectorId)
                .encode()
                .toUri();
        return post(caller, uri, request, DhbApiModels.ReceiptPageView.class);
    }

    /** 通过Integration调用订货宝getPaymentList，查询付款单列表。 */
    private DhbApiModels.PaymentPageView queryPayments(CallerIdentity caller, UUID connectorId,
                                                        DhbOrderSyncCommand command, int begin) {
        DhbApiModels.PaymentQueryCommand request = new DhbApiModels.PaymentQueryCommand(
                null, begin, PAGE_SIZE, null, null, "all");
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.PAYMENT_QUERY_PATH)
                .buildAndExpand(connectorId)
                .encode()
                .toUri();
        return post(caller, uri, request, DhbApiModels.PaymentPageView.class);
    }

    private <T> T post(CallerIdentity caller, URI uri, Object body, Class<T> responseType) {
        long startedAt = System.nanoTime();
        Map<String, String> context = contextHeaders(caller);
        TrustedContextSigner.SignedContext signed = signer.sign(
                "POST", uri.getRawPath(), uri.getRawQuery(), context);
        context.put(RequestHeaders.CONTEXT_KEY_ID, signed.keyId());
        context.put(RequestHeaders.CONTEXT_TIMESTAMP, signed.timestamp());
        context.put(RequestHeaders.CONTEXT_SIGNATURE, signed.signature());
        String requestId = requestId();
        log.info("订单中心调用Integration method=POST path={} params={} requestId={}",
                uri.getRawPath(), safeRequestBody(body), requestId);
        try {
            T result = restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> context.forEach(headers::set))
                    .header(RequestHeaders.REQUEST_ID, requestId)
                    .body(body)
                    .retrieve()
                    .body(responseType);
            log.info("订单中心调用Integration完成 method=POST path={} elapsedMs={}",
                    uri.getRawPath(), elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            log.warn("订单中心调用Integration失败 method=POST path={} elapsedMs={} reason={}",
                    uri.getRawPath(), elapsedMillis(startedAt), exception.getMessage());
            throw exception;
        }
    }

    private String safeRequestBody(Object body) {
        if (body == null) return "-";
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException ignored) {
            return body.getClass().getSimpleName();
        }
    }

    private static long elapsedMillis(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String summaryOrderNumber(DhbApiModels.OrderView summary) {
        return defaultText(summary.orderNumber(), first(map(summary.sourceFields()),
                "OrderSN", "orders_num", "orderNumber", "sourceId"));
    }

    private static String summaryShipmentNumber(DhbApiModels.ShipmentView summary) {
        return defaultText(summary.shipmentNumber(), first(map(summary.sourceFields()),
                "ships_num", "ShipsNum", "shipmentNumber"));
    }

    private static String summaryReturnNumber(DhbApiModels.ReturnView summary) {
        return defaultText(summary.returnNumber(), first(map(summary.sourceFields()),
                "ReturnsSN", "returnsSn", "returnNumber", "return_no"));
    }

    private DhbOrderImportBatch.OrderItem order(DhbApiModels.OrderView summary,
                                                 DhbApiModels.OrderContentView detail) {
        Map<String, Object> list = map(summary.sourceFields());
        Map<String, Object> content = detail == null ? Map.of() : map(detail.sourceFields());
        String rawList = json(list);
        String rawDetail = detail == null ? null : json(content);
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("list", list);
        if (detail != null) revision.put("detail", content);
        String effectiveRaw = json(revision);
        return new DhbOrderImportBatch.OrderItem(
                required(defaultText(first(content, list, "OrderSN", "orders_num", "orderNumber", "sourceId"),
                        summary.orderNumber()), "orderNumber"),
                defaultText(first(content, list, "OrderStatus", "order_status", "status"), summary.status()),
                defaultText(first(content, list, "OrderPayStatus", "PayStatus", "pay_status", "paymentStatus"),
                        summary.paymentStatus()),
                first(content, list, "OrderType", "order_type", "TypeName"),
                firstDecimal(content, list, summary.amount(), "OrderTotal", "order_total", "Total", "amount"),
                firstInstant(content, list, summary.createdAt(), "OrderDate", "order_date", "createdAt"),
                firstInstant(content, list, summary.updatedAt(), "UpdateDate", "OrderUpdateTime", "OrderUpdateDate",
                        "update_date", "updatedAt"),
                first(content, list, "UpdateDate", "OrderUpdateTime", "OrderUpdateDate", "update_date", "updatedAt"),
                first(content, list, "DeliveryDate", "SendDate", "delivery_date"),
                first(content, list, "OrderRemark", "Remark", "remark"),
                defaultText(first(content, list, "ClientNO", "ClientNum", "client_num", "customerNumber"),
                        summary.customerNumber()),
                first(content, list, "ClientGUID", "ClientGuid", "client_guid"),
                first(content, list, "ClientName", "ClientCompanyName", "client_name"),
                first(content, list, "OrderReceiveName", "Consignee", "ReceiverName", "LinkMan"),
                first(content, list, "OrderReceiveCompany", "CompanyName", "ReceiverCompany"),
                first(content, list, "OrderReceivePhone", "Mobile", "Phone", "ReceiverPhone"),
                first(content, list, "OrderReceiveAdd", "OrderReceiveAddTwo", "Address", "ReceiverAddress"),
                first(content, list, "Province", "province"),
                first(content, list, "City", "city"),
                first(content, list, "District", "Area", "district"),
                first(content, list, "OrderApi", "ApiStatus", "apiStatus", "api_status"),
                first(content, list, "OrderException", "OrderExceptionStatus", "ExceptionStatus",
                        "exceptionStatus", "exception_status"),
                first(content, list, "OrderSendType", "SendType", "send_type"),
                first(content, list, "lastOrderAt", "LastOrderAt", "LastOrderDate", "last_order_at"),
                first(content, list, "SourceDevice", "Device", "device"),
                first(content, list, "IsAdminOrder", "AdminOrder", "admin_order"),
                first(content, list, "SplitType", "splitType", "split_type"),
                first(content, list, "SplitTypeName", "split_type_name"),
                detail == null ? List.of() : orderLines(content),
                detail == null ? List.of() : orderShipments(content),
                rawList, rawDetail, sha256(effectiveRaw), detail != null,
                first(content, list, "ClientTypeName", "client_type_name"),
                first(content, list, "ClientAreaName", "client_area_name"),
                first(content, list, "AdminUser", "admin_user"),
                first(content, list, "OperationName", "operation_name"),
                first(content, list, "StaffName", "staff_name"),
                first(content, list, "StaffMobile", "staff_mobile"),
                assistantNames(content),
                first(content, list, "OrderAuditTime", "audit_at"),
                first(content, list, "PayForm", "pay_form", "settlement_method"),
                firstDecimal(content, list, null, "GoodsWeight", "goods_weight"),
                firstDecimal(content, list, null, "Taxation", "taxation", "tax_amount"),
                firstDecimal(content, list, null, "DiscountPrice", "discount_price"),
                firstDecimal(content, list, null, "DiscountTotal", "discount_total"),
                firstDecimal(content, list, null, "OrderFreight", "order_freight", "freight_amount"),
                firstDecimal(content, list, null, "ApplyTotal", "apply_total"),
                firstDecimal(content, list, null, "CouponDiscountedAmount", "coupon_discounted_amount"),
                jsonValue(content, "ClientRemark", "client_remark"),
                first(content, list, "internalComm", "InternalComm", "internal_comment"),
                nestedText(content, "Invoice", "invoice_title"),
                nestedText(content, "Invoice", "invoice_content"),
                nestedText(content, "Invoice", "bank"),
                nestedText(content, "Invoice", "bank_account"),
                nestedText(content, "Invoice", "taxpayer_number"),
                first(content, list, "ClientTag", "ClientTagName", "CustomerTag", "CustomerTagName", "customer_tag"),
                defaultText(nestedText(content, "Invoice", "InvoiceType", "invoice_type", "TypeName", "type_name"),
                        first(content, list, "InvoiceType", "invoice_type", "InvoiceTypeName", "invoice_type_name")));
    }

    private DhbOrderImportBatch.ShipmentItem shipment(DhbApiModels.ShipmentView summary,
                                                       DhbApiModels.ShipmentContentView detail) {
        Map<String, Object> list = map(summary.sourceFields());
        Map<String, Object> content = detail == null ? Map.of() : map(detail.sourceFields());
        Map<String, Object> merged = new LinkedHashMap<>(list);
        merged.putAll(content);
        String shipmentNumber = defaultText(first(merged, "ships_num", "ShipsNum"), summary.shipmentNumber());
        String warehouseNumber = defaultText(first(merged, "stock_num", "StockNum"), summary.warehouseNumber());
        List<DhbOrderImportBatch.ShipmentLineItem> lines = detail == null
                ? List.of() : shipmentLines(merged, warehouseNumber);
        String rawJson = json(merged);
        return new DhbOrderImportBatch.ShipmentItem(
                defaultText(first(merged, "ships_id", "ShipsId"), summary.sourceId()),
                shipmentNumber,
                defaultText(first(merged, "orders_num", "OrdersNum"), summary.orderNumber()),
                defaultText(first(merged, "status", "Status"), summary.status()),
                defaultText(first(merged, "status_name", "StatusName"), summary.statusName()),
                defaultText(first(merged, "type_id", "TypeId"), summary.typeId()),
                defaultText(first(merged, "type_name", "TypeName"), summary.typeName()),
                defaultText(first(merged, "client_num", "ClientNum"), summary.customerNumber()),
                defaultText(first(merged, "client_name", "ClientName"), summary.customerName()),
                defaultText(first(merged, "client_guid", "ClientGuid"), summary.customerGuid()),
                warehouseNumber,
                defaultText(first(merged, "stock_name", "StockName"), summary.warehouseName()),
                defaultText(first(merged, "stock_guid", "StockGuid"), summary.warehouseGuid()),
                firstInstant(merged, Map.of(), summary.shipmentAt(), "ships_date", "ShipsDate"),
                defaultText(first(merged, "logistics_name", "LogisticsName"), summary.logisticsName()),
                defaultText(first(merged, "express_num", "ExpressNum"), summary.trackingNumber()),
                first(merged, "remark", "Remark"),
                firstInstant(merged, Map.of(), summary.createdAt(), "create_date", "CreateDate"),
                firstInstant(merged, Map.of(), summary.updatedAt(), "update_date", "UpdateDate"),
                lines, rawJson, sha256(rawJson), detail != null);
    }

    private DhbOrderImportBatch.ReturnItem returnItem(DhbApiModels.ReturnView summary,
                                                       DhbApiModels.ReturnContentView detail) {
        Map<String, Object> list = map(summary.sourceFields());
        Map<String, Object> content = detail == null ? Map.of() : map(detail.sourceFields());
        Map<String, Object> merged = new LinkedHashMap<>(list);
        merged.putAll(content);
        DhbApiModels.ReturnView effective = detail == null || detail.summary() == null
                ? summary : detail.summary();
        String returnNumber = defaultText(first(merged, "ReturnsSN", "returnsSn", "returnNumber", "return_no"),
                defaultText(effective.returnNumber(), effective.sourceId()));
        String orderNumber = consistentOrderNumber(merged, effective, detail);
        List<DhbOrderImportBatch.ReturnLineItem> lines = detail == null
                ? List.of() : returnLines(detail.lines());
        Map<String, Object> revision = new LinkedHashMap<>(merged);
        if (detail != null) {
            revision.put("detail", content);
            revision.put("lines", detail.lines());
        }
        String rawJson = json(revision);
        return new DhbOrderImportBatch.ReturnItem(
                required(returnNumber, "returnNumber"),
                orderNumber,
                defaultText(first(merged, "ReturnsStatus", "return_status", "status"), effective.status()),
                defaultText(first(merged, "StaffName", "staffName"), effective.staffName()),
                firstDecimal(merged, Map.of(), effective.returnAmount(),
                        "ReturnsTotal", "return_amount", "returnAmount"),
                firstDecimal(merged, Map.of(), effective.settlementAmount(),
                        "ReturnsDiscountTotal", "settlement_amount", "settlementAmount"),
                firstInstant(merged, Map.of(), effective.returnedAt(),
                        "ReturnsDate", "returns_date", "returnedAt"),
                firstInstant(merged, Map.of(), effective.updatedAt(),
                        "ReturnsUpdateDate", "update_date", "updatedAt"),
                defaultText(first(merged, "ReturnsReason", "reason"), effective.reason()),
                defaultText(first(merged, "ClientNum", "client_num", "customerNumber"), effective.customerNumber()),
                defaultText(first(merged, "ClientGUID", "ClientGuid", "client_guid", "customerGuid"),
                        effective.customerGuid()),
                defaultText(first(merged, "ReturnsConsignee", "consignee"), effective.consignee()),
                defaultText(first(merged, "ReturnsPhone", "phone", "Mobile"), effective.phone()),
                defaultText(first(merged, "ReturnsAddress", "address"), effective.address()),
                defaultText(first(merged, "ReturnsSendCompany", "logistics_company", "logisticsCompany"),
                        effective.logisticsCompany()),
                defaultText(first(merged, "ReturnsSendNo", "logistics_no", "logisticsNumber"),
                        effective.logisticsNumber()),
                defaultText(first(merged, "ReturnsType", "return_type", "returnType"), effective.returnType()),
                defaultText(first(merged, "ReturnsSendMode", "delivery_mode", "deliveryMode"),
                        effective.deliveryMode()),
                lines, rawJson, sha256(rawJson), detail != null);
    }

    private List<DhbOrderImportBatch.ReturnLineItem> returnLines(List<DhbApiModels.ReturnLineView> source) {
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        return source.stream().map(line -> {
            String normalized = normalizedReturnLine(line);
            int ordinal = occurrences.merge(normalized, 1, Integer::sum) - 1;
            return returnLine(line, normalized, ordinal);
        }).toList();
    }

    private DhbOrderImportBatch.ReturnLineItem returnLine(DhbApiModels.ReturnLineView line,
                                                           String normalized, int ordinal) {
        return new DhbOrderImportBatch.ReturnLineItem(
                "RETURN-LINE-" + sha256(normalized) + "#" + ordinal,
                line.productGuid(), line.skuNumber(), line.productCode(), line.productName(),
                line.quantity(), line.confirmedQuantity(), line.unitPrice(), line.confirmedPrice(), line.unit(),
                defaultText(line.warehouseNumber(), line.warehouseGuid()), line.warehouseName(), line.remark());
    }

    private String consistentOrderNumber(Map<String, Object> merged, DhbApiModels.ReturnView effective,
                                         DhbApiModels.ReturnContentView detail) {
        Set<String> values = new LinkedHashSet<>();
        addIfPresent(values, first(merged, "OrdersNum", "orders_num", "orderNumber"));
        addIfPresent(values, effective.orderNumber());
        if (detail != null) {
            collectTextValues(detail.sourceFields(), values, "OrdersNum", "orders_num", "orderNumber");
            if (detail.summary() != null) {
                collectTextValues(detail.summary().sourceFields(), values,
                        "OrdersNum", "orders_num", "orderNumber");
            }
        }
        if (values.size() > 1) {
            throw new IllegalStateException("订货宝退货单关联订单号不一致: " + values);
        }
        return values.stream().findFirst().orElse(null);
    }

    private String normalizedReturnLine(DhbApiModels.ReturnLineView line) {
        return String.join("\u001f", normalized(line.productGuid()), normalized(line.skuNumber()),
                normalized(line.productCode()), normalized(line.productName()), normalized(line.quantity()),
                normalized(line.confirmedQuantity()), normalized(line.unitPrice()),
                normalized(line.confirmedPrice()), normalized(line.unit()), normalized(line.warehouseNumber()),
                normalized(line.warehouseName()), normalized(line.remark()));
    }

    private DhbOrderImportBatch.FinancialItem receipt(DhbApiModels.ReceiptView item) {
        Map<String, Object> fields = map(item.sourceFields());
        String rawJson = json(fields);
        String documentNo = defaultText(first(fields, "ReceiptsNum", "receiptNumber"),
                defaultText(item.receiptNumber(), item.sourceId()));
        return new DhbOrderImportBatch.FinancialItem(
                "RECEIPT",
                required(documentNo, "receiptNumber"),
                null,
                defaultText(first(fields, "OrdersNum", "orderNumber"), item.orderNumber()),
                defaultText(first(fields, "ClientNum", "customerNumber"), item.customerNumber()),
                defaultText(first(fields, "ClientGUID", "ClientGuid", "customerGuid"), item.customerGuid()),
                defaultText(first(fields, "IncexpId", "businessType"), item.businessType()),
                defaultText(first(fields, "TypeId", "paymentMethod"), item.paymentMethod()),
                firstDecimal(fields, Map.of(), item.amount(), "Amount", "amount"),
                defaultText(first(fields, "Status", "status"), item.status()),
                firstInstant(fields, Map.of(), item.transactionAt(), "ReceiptsDate", "transactionAt"),
                firstInstant(fields, Map.of(), item.createdAt(), "CreateDate", "createdAt"),
                firstInstant(fields, Map.of(), item.updatedAt(), "UpdateDate", "updatedAt"),
                defaultText(first(fields, "SerialNumber", "serialNumber"), item.serialNumber()),
                defaultText(first(fields, "AccountName", "accountName"), item.accountName()),
                defaultText(first(fields, "BankName", "bankName"), item.bankName()),
                defaultText(first(fields, "AccountNumber", "accountNumber"), item.accountNumber()),
                defaultText(first(fields, "Remark", "remark"), item.remark()), rawJson, sha256(rawJson));
    }

    private DhbOrderImportBatch.FinancialItem payment(DhbApiModels.PaymentView item) {
        Map<String, Object> fields = map(item.sourceFields());
        String rawJson = json(fields);
        String documentNo = defaultText(first(fields, "PaymentNum", "paymentNumber"),
                defaultText(item.paymentNumber(), item.sourceId()));
        return new DhbOrderImportBatch.FinancialItem(
                "PAYMENT",
                required(documentNo, "paymentNumber"),
                defaultText(first(fields, "ReceiptsNum", "receiptNumber"), item.receiptNumber()),
                defaultText(first(fields, "OrdersNum", "orderNumber"), item.orderNumber()),
                defaultText(first(fields, "ClientNum", "customerNumber"), item.customerNumber()),
                defaultText(first(fields, "ClientGUID", "ClientGuid", "customerGuid"), item.customerGuid()),
                defaultText(first(fields, "IncexpId", "businessType"), item.businessType()),
                defaultText(first(fields, "TypeId", "paymentMethod"), item.paymentMethod()),
                firstDecimal(fields, Map.of(), item.amount(), "Amount", "amount"),
                defaultText(first(fields, "Status", "status"), item.status()),
                firstInstant(fields, Map.of(), item.transactionAt(), "ReceiptsDate", "transactionAt"),
                firstInstant(fields, Map.of(), item.createdAt(), "CreateDate", "createdAt"),
                null,
                defaultText(first(fields, "SerialNumber", "serialNumber"), item.serialNumber()),
                defaultText(first(fields, "AccountName", "accountName"), item.accountName()),
                defaultText(first(fields, "BankName", "bankName"), item.bankName()),
                defaultText(first(fields, "AccountNumber", "accountNumber"), item.accountNumber()),
                defaultText(first(fields, "Remark", "remark"), item.remark()), rawJson, sha256(rawJson));
    }

    private static List<DhbOrderImportBatch.ShipmentLineItem> shipmentLines(
            Map<String, Object> content, String warehouseNumber) {
        List<Map<String, Object>> rows = rows(content, "list", "List");
        List<DhbOrderImportBatch.ShipmentLineItem> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            Map<String, Object> orderInfo = nestedMap(row, "orders_list_info", "OrdersListInfo");
            result.add(new DhbOrderImportBatch.ShipmentLineItem(
                    stableLineId(index, row, "ships_list_id", "ShipsListId", "id"),
                    first(row, "goods_guid", "GoodsGuid", "goods_id", "GoodsId"),
                    first(row, "options_goods_num", "OptionsGoodsNum"),
                    first(row, "goods_num", "GoodsNum"),
                    first(row, "goods_name", "GoodsName"),
                    decimal(firstObject(row, "ships_number", "ShipsNumber")),
                    firstDecimal(row, orderInfo, null, "order_units_price", "orders_price", "OrdersPrice"),
                    firstDecimal(orderInfo, row, null, "actual_amount", "ActualAmount"),
                    first(orderInfo, "order_units_name", "base_units_name", "OrdersUnits"),
                    warehouseNumber,
                    first(row, "remark", "Remark")));
        }
        return List.copyOf(result);
    }

    private DhbOrderImportBatch.ShipmentLogisticsItem logisticsSnapshot(
            String orderNumber, DhbApiModels.WaitShipsView waitShips) {
        String rawJson = json(waitShips.sourceFields());
        List<DhbOrderImportBatch.ShipmentLogisticsRecord> shipped = waitShips.shipped().stream()
                .map(item -> new DhbOrderImportBatch.ShipmentLogisticsRecord(
                        item.sourceId(), item.shipmentNo(), item.status(), item.logisticsName(),
                        item.logisticsCode(), item.trackingNo(), item.shipmentAt(), item.stockUpAt(),
                        item.warehouseNo(), item.warehouseName(), item.lines().stream().map(line ->
                                new DhbOrderImportBatch.ShipmentLogisticsLineItem(
                                        "SHIPPED", line.sourceLineId(), line.orderLineId(), line.productId(),
                                        line.skuNo(), line.listType(), line.productCode(), line.productName(),
                                        line.specification(), line.unit(), line.containerUnit(),
                                        line.conversionNumber(), line.quantity(), line.remark(),
                                        item.warehouseNo(), item.warehouseName())).toList()))
                .toList();
        List<DhbOrderImportBatch.WaitStockItem> waitStock = waitShips.waitStock().stream()
                .map(item -> new DhbOrderImportBatch.WaitStockItem(
                        "WAIT_STOCK", item.sourceLineId(), item.productId(), item.skuNo(), item.listType(),
                        item.productCode(), item.productName(), item.specification(), item.unit(),
                        item.containerUnit(), item.conversionNumber(), item.warehouseNo(), item.warehouseName(),
                        item.orderedQuantity(), item.stockedQuantity(), item.realStock(), item.waitQuantity(),
                        item.remark()))
                .toList();
        return new DhbOrderImportBatch.ShipmentLogisticsItem(
                required(orderNumber, "orderNumber"), shipped, waitStock, rawJson, sha256(rawJson));
    }

    private static List<DhbOrderImportBatch.OrderLineItem> orderLines(Map<String, Object> content) {
        List<Map<String, Object>> rows = rows(content,
                "OrderProduct", "OrderProducts", "OrderGoods", "Goods", "Products", "list", "body");
        List<DhbOrderImportBatch.OrderLineItem> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            result.add(new DhbOrderImportBatch.OrderLineItem(
                    stableLineId(index, row, "orders_list_id", "OrderListId", "id"),
                    first(row, "Guid", "TrueGuid", "GoodsGuid", "goods_guid"),
                    first(row, "options_goods_num", "OptionsGoodsNum", "skuNo", "SkuNo", "sku_no"),
                    first(row, "OptionsGoodsNo", "options_goods_no"),
                    first(row, "options_barcode", "Barcode", "BarCode", "barcode"),
                    first(row, "Name", "GoodsName", "ProductName"),
                    first(row, "Coding", "GoodsCoding", "ProductCode"),
                    first(row, "multiFirst", "Spec1", "SpecificationFirst"),
                    first(row, "multiSecond", "Spec2", "SpecificationSecond"),
                    first(row, "multiName", "SpecName", "SpecificationName"),
                    decimal(firstObject(row, "ContentPrice", "order_units_price", "Price", "OrderPrice", "UnitPrice")),
                    decimal(firstObject(row, "ContentNumber", "order_units_number", "Number", "OrderNumber", "Quantity")),
                    decimal(firstObject(row, "ActualAmount", "Total", "OrderTotal", "Amount")),
                    first(row, "order_units_name", "base_units_name", "Units", "UnitsName", "Unit", "unit_name"),
                    first(row, "remark", "Remark"),
                    decimal(firstObject(row, "ContentPurchasePrice", "PurchasePrice", "purchase_price")),
                    decimal(firstObject(row, "ConversionNumber", "conversion_number")),
                    decimal(firstObject(row, "OfferPrice", "offer_price")),
                    decimal(firstObject(row, "ActualAmount", "actual_amount")),
                    decimal(firstObject(row, "GoodsWeight", "goods_weight")),
                    first(row, "isPre", "IsPre", "pre_sale"),
                    first(row, "conType", "ConType", "content_type"),
                    first(row, "InvoiceTax", "invoice_tax"),
                    decimal(firstObject(row, "ContentPercent", "content_percent"))));
        }
        return List.copyOf(result);
    }

    private static List<DhbOrderImportBatch.OrderShipmentItem> orderShipments(Map<String, Object> content) {
        return rows(content, "Ships", "ships").stream()
                .map(row -> new DhbOrderImportBatch.OrderShipmentItem(
                        first(row, "ships_num", "ShipsNum"), first(row, "status", "Status"),
                        first(row, "ships_date", "ShipsDate"), first(row, "stock_up_time", "StockUpTime")))
                .toList();
    }

    private String assistantNames(Map<String, Object> content) {
        Object value = firstObject(content, "AssistStaff", "assistStaff", "assistant_sales_persons");
        if (value instanceof Map<?, ?> map) {
            return first(stringMap(map), "StaffName", "staffName", "name");
        }
        if (!(value instanceof Iterable<?> iterable)) return text(value);
        List<String> names = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                String name = first(stringMap(map), "StaffName", "staffName", "name");
                if (name != null) names.add(name);
            } else if (text(item) != null) {
                names.add(text(item));
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    private String nestedText(Map<String, Object> root, String objectKey, String... keys) {
        return first(nestedMap(root, objectKey), keys);
    }

    private String jsonValue(Map<String, Object> root, String... keys) {
        Object value = firstObject(root, keys);
        if (value == null) return null;
        if (value instanceof String string) return string.isBlank() ? null : string;
        return json(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("订货宝订单原始数据序列化失败", exception);
        }
    }

    private static Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : new LinkedHashMap<>(value);
    }

    private static List<Map<String, Object>> rows(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            Object value = firstObject(root, key);
            if (value instanceof Iterable<?> iterable) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : iterable) {
                    if (item instanceof Map<?, ?> map) {
                        result.add(stringMap(map));
                    }
                }
                return List.copyOf(result);
            }
        }
        return List.of();
    }

    private static Map<String, Object> nestedMap(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            Object value = firstObject(root, key);
            if (value instanceof Map<?, ?> map) return stringMap(map);
        }
        return Map.of();
    }

    private static String stableLineId(int index, Map<String, Object> row, String... keys) {
        String explicit = first(row, keys);
        if (explicit != null) return explicit;
        String business = first(row, "Guid", "TrueGuid", "OptionsGoodsNum", "Coding", "Name");
        return (business == null ? "LINE" : business) + "#" + index;
    }

    private static String first(Map<String, Object> preferred, Map<String, Object> fallback, String... keys) {
        for (String key : keys) {
            String value = text(firstObject(preferred, key));
            if (value != null) return value;
            value = text(firstObject(fallback, key));
            if (value != null) return value;
        }
        return null;
    }

    private static String first(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            String value = text(firstObject(row, key));
            if (value != null) return value;
        }
        return null;
    }

    private static Object firstObject(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object direct = row.get(key);
            if (direct != null) return direct;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
            }
        }
        return null;
    }

    private static BigDecimal firstDecimal(Map<String, Object> preferred, Map<String, Object> fallback,
                                           BigDecimal typed, String... keys) {
        BigDecimal value = decimal(firstObject(preferred, keys));
        if (value != null) return value;
        value = decimal(firstObject(fallback, keys));
        return value == null ? typed : value;
    }

    private static Instant firstInstant(Map<String, Object> preferred, Map<String, Object> fallback,
                                        Instant typed, String... keys) {
        Instant value = instant(firstObject(preferred, keys));
        if (value != null) return value;
        value = instant(firstObject(fallback, keys));
        return value == null ? typed : value;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).strip();
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private static String defaultText(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String normalized(Object value) {
        String text = text(value);
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.strip());
    }

    private static void collectTextValues(Object value, Set<String> values, String... keys) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                for (String expected : keys) {
                    if (key.equalsIgnoreCase(expected)) {
                        addIfPresent(values, text(entry.getValue()));
                        break;
                    }
                }
                collectTextValues(entry.getValue(), values, keys);
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) collectTextValues(item, values, keys);
        }
    }

    private static <T> T requiredResponse(T value, String objectName) {
        if (value == null) throw new IllegalStateException("订货宝" + objectName + "响应为空");
        return value;
    }

    private static long expectedTotal(long expected, long actual, String objectName) {
        if (actual < 0) throw new IllegalStateException("订货宝" + objectName + "分页total不能为负数");
        if (expected >= 0 && expected != actual) {
            throw new IllegalStateException("订货宝" + objectName + "分页total不一致 expected="
                    + expected + " actual=" + actual);
        }
        return actual;
    }

    private static <T> List<T> pageItems(List<T> items, long total, int begin, String objectName) {
        if (items == null) throw new IllegalStateException("订货宝" + objectName + "分页items为空");
        if (begin > total || begin + items.size() > total) {
            throw new IllegalStateException("订货宝" + objectName + "分页数量越界 begin=" + begin
                    + " size=" + items.size() + " total=" + total);
        }
        if (items.isEmpty() && begin < total) {
            throw new IllegalStateException("订货宝" + objectName + "分页提前返回空页 begin=" + begin
                    + " total=" + total);
        }
        return items;
    }

    private static void requireUnique(Set<String> keys, String key, String objectName) {
        if (!keys.add(key)) throw new IllegalStateException("订货宝" + objectName + "分页出现重复业务键=" + key);
    }

    private static void requireComplete(Set<String> keys, long expected, String objectName) {
        if (expected < 0) throw new IllegalStateException("订货宝" + objectName + "未收到分页响应");
        if (keys.size() != expected) {
            throw new IllegalStateException("订货宝" + objectName + "去重后数量不完整 expected="
                    + expected + " distinct=" + keys.size());
        }
    }

    private static IllegalStateException maxPages(String objectName, int maxPages) {
        return new IllegalStateException("订货宝" + objectName + "同步达到maxPages=" + maxPages
                + "，但供应商仍有后续数据；本次不推进增量游标");
    }

    private static Instant incrementalFrom(DhbOrderSyncCommand command) {
        return command.mode() == DhbOrderSyncMode.INCREMENTAL ? command.updatedFrom() : null;
    }

    private static Instant incrementalTo(DhbOrderSyncCommand command) {
        return command.mode() == DhbOrderSyncMode.INCREMENTAL ? command.updatedTo() : null;
    }

    private static BigDecimal decimal(Object value) {
        String valueText = text(value);
        if (valueText == null) return null;
        try {
            return new BigDecimal(valueText.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            long raw = number.longValue();
            return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        }
        String valueText = text(value);
        if (valueText == null || "0000-00-00 00:00:00".equals(valueText)) return null;
        try {
            long raw = Long.parseLong(valueText);
            return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
        } catch (NumberFormatException ignored) {
            try {
                return Instant.parse(valueText);
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(valueText.replace('T', ' '), DATE_TIME)
                            .atZone(DHB_ZONE).toInstant();
                } catch (DateTimeParseException ignoredFinally) {
                    return null;
                }
            }
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalStateException("订货宝回执缺少" + field);
        return value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private static Map<String, String> contextHeaders(CallerIdentity caller) {
        Map<String, String> headers = new LinkedHashMap<>();
        put(headers, RequestHeaders.PRINCIPAL_SCOPE, caller.principalScope());
        put(headers, RequestHeaders.PRINCIPAL_ID, caller.principalId());
        put(headers, RequestHeaders.TENANT_ID, caller.tenantId());
        put(headers, RequestHeaders.USER_ID, caller.userId());
        put(headers, RequestHeaders.PLATFORM_USER_ID, caller.platformUserId());
        put(headers, RequestHeaders.SESSION_ID, caller.sessionId());
        put(headers, RequestHeaders.SESSION_VERSION, caller.sessionVersion());
        put(headers, RequestHeaders.USER_SECURITY_VERSION, caller.userSecurityVersion());
        put(headers, RequestHeaders.TENANT_POLICY_VERSION, caller.tenantPolicyVersion());
        put(headers, RequestHeaders.ROLES, joined(caller.roles()));
        put(headers, RequestHeaders.PERMISSIONS, joined(caller.permissions()));
        return headers;
    }

    private static void put(Map<String, String> target, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(name, String.valueOf(value));
    }

    private static String joined(Set<String> values) {
        return values == null || values.isEmpty() ? null : String.join(",", new TreeSet<>(values));
    }

    private static URI baseUri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Integration地址不能为空");
        URI uri = URI.create(value.strip().replaceAll("/+$", "") + "/");
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Integration地址必须使用http或https");
        }
        return uri;
    }

    private static String requestId() {
        String value = RequestContext.getRequestId();
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
