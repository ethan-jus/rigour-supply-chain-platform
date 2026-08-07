package com.rigour.order.infrastructure.integration;

import com.rigour.integration.api.v1.DhbOrderApi;
import com.rigour.integration.api.v1.model.DhbApiModels;
import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
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

        boolean truncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total += page.total();
            for (DhbApiModels.OrderView summary : page.items()) {
                DhbApiModels.OrderContentView detail = effective.includeDetails()
                        ? content(caller, connectorId, summary.orderNumber()) : null;
                orders.add(order(summary, detail));
                DhbApiModels.WaitShipsView waitShips = waitShips(caller, connectorId, summary.orderNumber());
                shipmentLogistics.add(logisticsSnapshot(summary.orderNumber(), waitShips));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
            truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }

        if (truncatedByMaxPages) {
            throw new IllegalStateException("订货宝订单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
        completed.add("ORDER");
        completed.add("SHIPMENT_LOGISTICS");
        if (effective.includeDetails()) completed.add("ORDER_DETAIL");

        boolean shipmentsTruncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ShipmentPageView page = queryShipments(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total += page.total();
            for (DhbApiModels.ShipmentView summary : page.items()) {
                DhbApiModels.ShipmentContentView detail = effective.includeDetails()
                        ? shipmentContent(caller, connectorId, summary.shipmentNumber()) : null;
                shipments.add(shipment(summary, detail));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= page.total()) break;
            shipmentsTruncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }

        if (shipmentsTruncatedByMaxPages) {
            throw new IllegalStateException("订货宝出库/发货单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
        completed.add("SHIPMENT");
        if (effective.includeDetails()) completed.add("SHIPMENT_DETAIL");

        boolean returnsTruncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ReturnPageView page = queryReturns(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total += page.total();
            for (DhbApiModels.ReturnView summary : page.items()) {
                DhbApiModels.ReturnContentView detail = effective.includeDetails()
                        ? returnContent(caller, connectorId, summary.returnNumber()) : null;
                returns.add(returnItem(summary, detail));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= page.total()) break;
            returnsTruncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }

        if (returnsTruncatedByMaxPages) {
            throw new IllegalStateException("订货宝退货单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
        completed.add("RETURN");
        if (effective.includeDetails()) completed.add("RETURN_DETAIL");

        boolean receiptsTruncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ReceiptPageView page = queryReceipts(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total += page.total();
            page.items().stream().map(this::receipt).forEach(financialDocuments::add);
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= page.total()) break;
            receiptsTruncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }

        if (receiptsTruncatedByMaxPages) {
            throw new IllegalStateException("订货宝收款单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
        completed.add("RECEIPT");

        boolean paymentsTruncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.PaymentPageView page = queryPayments(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total += page.total();
            page.items().stream().map(this::payment).forEach(financialDocuments::add);
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= page.total()) break;
            paymentsTruncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }

        if (paymentsTruncatedByMaxPages) {
            throw new IllegalStateException("订货宝付款单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
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
        long total = 0;
        boolean truncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total = page.total();
            for (DhbApiModels.OrderView summary : page.items()) {
                DhbApiModels.OrderContentView detail = effective.includeDetails()
                        ? content(caller, connectorId, summary.orderNumber()) : null;
                orders.add(order(summary, detail));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
            truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }
        if (truncatedByMaxPages) {
            throw new IllegalStateException("订货宝订单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
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
        long total = 0;
        boolean truncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ReturnPageView page = queryReturns(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total = page.total();
            for (DhbApiModels.ReturnView summary : page.items()) {
                DhbApiModels.ReturnContentView detail = effective.includeDetails()
                        ? returnContent(caller, connectorId, summary.returnNumber()) : null;
                returns.add(returnItem(summary, detail));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
            truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }
        if (truncatedByMaxPages) {
            throw new IllegalStateException("订货宝退货单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
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
        long total = 0;
        boolean truncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.ShipmentPageView page = queryShipments(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total = page.total();
            for (DhbApiModels.ShipmentView summary : page.items()) {
                DhbApiModels.ShipmentContentView detail = effective.includeDetails()
                        ? shipmentContent(caller, connectorId, summary.shipmentNumber()) : null;
                shipments.add(shipment(summary, detail));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
            truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }
        if (truncatedByMaxPages) {
            throw new IllegalStateException("订货宝出库/发货单同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
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
        long total = 0;
        boolean truncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            DhbApiModels.OrderPageView page = query(caller, connectorId, effective, begin);
            if (page == null || page.items() == null || page.items().isEmpty()) break;
            if (pageNumber == 0) total = page.total();
            for (DhbApiModels.OrderView summary : page.items()) {
                shipmentLogistics.add(logisticsSnapshot(summary.orderNumber(),
                        waitShips(caller, connectorId, summary.orderNumber())));
            }
            if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
            truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
        }
        if (truncatedByMaxPages) {
            throw new IllegalStateException("订货宝物流同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续订单；本次不推进增量游标");
        }
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
        long total = 0;
        boolean truncatedByMaxPages = false;
        for (int pageNumber = 0; pageNumber < effective.maxPages(); pageNumber++) {
            int begin = pageNumber * PAGE_SIZE;
            if (receipts) {
                DhbApiModels.ReceiptPageView page = queryReceipts(caller, connectorId, effective, begin);
                if (page == null || page.items() == null || page.items().isEmpty()) break;
                if (pageNumber == 0) total = page.total();
                page.items().stream().map(this::receipt).forEach(financialDocuments::add);
                if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
                truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
            } else {
                DhbApiModels.PaymentPageView page = queryPayments(caller, connectorId, effective, begin);
                if (page == null || page.items() == null || page.items().isEmpty()) break;
                if (pageNumber == 0) total = page.total();
                page.items().stream().map(this::payment).forEach(financialDocuments::add);
                if (page.items().size() < PAGE_SIZE || begin + page.items().size() >= total) break;
                truncatedByMaxPages = pageNumber + 1 >= effective.maxPages();
            }
        }
        if (truncatedByMaxPages) {
            throw new IllegalStateException((receipts ? "订货宝收款单" : "订货宝付款单")
                    + "同步达到maxPages=" + effective.maxPages()
                    + "，但供应商仍有后续数据；本次不推进增量游标");
        }
        return total;
    }

    private DhbApiModels.OrderPageView query(CallerIdentity caller, UUID connectorId,
                                             DhbOrderSyncCommand command, int begin) {
        DhbApiModels.OrderQueryCommand request = new DhbApiModels.OrderQueryCommand(
                begin, PAGE_SIZE, null, null, null, command.updatedFrom(), command.updatedTo(),
                null, null, null, null);
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
        return post(caller, uri, new DhbApiModels.OrderContentCommand(false, false),
                DhbApiModels.OrderContentView.class);
    }

    /** 通过Integration调用订货宝getShipsList，查询独立出库/发货单。 */
    private DhbApiModels.ShipmentPageView queryShipments(CallerIdentity caller, UUID connectorId,
                                                          DhbOrderSyncCommand command, int begin) {
        DhbApiModels.ShipmentQueryCommand request = new DhbApiModels.ShipmentQueryCommand(
                begin, PAGE_SIZE, null, "F,T", null, null, null,
                command.updatedFrom(), command.updatedTo(), null, null, null);
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
        return post(caller, uri, Map.of(), DhbApiModels.ShipmentContentView.class);
    }

    /** 通过Integration调用订货宝getWaitShips，查询指定订单的出库/发货物流。 */
    private DhbApiModels.WaitShipsView waitShips(CallerIdentity caller, UUID connectorId,
                                                  String orderNumber) {
        URI uri = UriComponentsBuilder.fromUri(integrationBaseUri)
                .path(DhbOrderApi.WAIT_SHIPS_PATH)
                .buildAndExpand(connectorId, orderNumber)
                .encode()
                .toUri();
        return post(caller, uri, Map.of(), DhbApiModels.WaitShipsView.class);
    }

    /** 通过Integration调用订货宝getReturnsList，查询退货单列表。 */
    private DhbApiModels.ReturnPageView queryReturns(CallerIdentity caller, UUID connectorId,
                                                      DhbOrderSyncCommand command, int begin) {
        DhbApiModels.ReturnQueryCommand request = new DhbApiModels.ReturnQueryCommand(
                begin, PAGE_SIZE, null, "F,T", null, null,
                command.updatedFrom(), command.updatedTo(), null, null);
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
        return post(caller, uri, Map.of(), DhbApiModels.ReturnContentView.class);
    }

    /** 通过Integration调用订货宝getReceiptsList，查询收款单列表。 */
    private DhbApiModels.ReceiptPageView queryReceipts(CallerIdentity caller, UUID connectorId,
                                                        DhbOrderSyncCommand command, int begin) {
        DhbApiModels.ReceiptQueryCommand request = new DhbApiModels.ReceiptQueryCommand(
                null, begin, PAGE_SIZE, null, null, command.updatedFrom(), null);
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
                null, begin, PAGE_SIZE, command.updatedFrom(), command.updatedTo(), null);
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

    private DhbOrderImportBatch.OrderItem order(DhbApiModels.OrderView summary,
                                                 DhbApiModels.OrderContentView detail) {
        Map<String, Object> list = map(summary.sourceFields());
        Map<String, Object> content = detail == null ? Map.of() : map(detail.sourceFields());
        String rawList = json(list);
        String rawDetail = detail == null ? null : json(content);
        String effectiveRaw = rawDetail == null ? rawList : rawDetail;
        return new DhbOrderImportBatch.OrderItem(
                required(defaultText(first(content, list, "OrderSN", "orders_num", "orderNumber", "sourceId"),
                        summary.orderNumber()), "orderNumber"),
                defaultText(first(content, list, "OrderStatus", "order_status", "status"), summary.status()),
                defaultText(first(content, list, "PayStatus", "pay_status", "paymentStatus"), summary.paymentStatus()),
                first(content, list, "OrderType", "order_type", "TypeName"),
                firstDecimal(content, list, summary.amount(), "OrderTotal", "order_total", "Total", "amount"),
                firstInstant(content, list, summary.createdAt(), "OrderDate", "order_date", "createdAt"),
                firstInstant(content, list, summary.updatedAt(), "OrderUpdateDate", "update_date", "updatedAt"),
                first(content, list, "OrderUpdateDate", "update_date", "updatedAt"),
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
                first(content, list, "ExceptionStatus", "exceptionStatus", "exception_status"),
                first(content, list, "SendType", "send_type"),
                first(content, list, "LastOrderDate", "last_order_at"),
                first(content, list, "SourceDevice", "Device", "device"),
                first(content, list, "IsAdminOrder", "AdminOrder", "admin_order"),
                first(content, list, "SplitType", "splitType", "split_type"),
                first(content, list, "SplitTypeName", "split_type_name"),
                detail == null ? List.of() : orderLines(content),
                detail == null ? List.of() : orderShipments(content),
                rawList, rawDetail, sha256(effectiveRaw), detail != null);
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
        List<DhbOrderImportBatch.ReturnLineItem> lines = detail == null
                ? List.of() : detail.lines().stream().map(this::returnLine).toList();
        String rawJson = json(merged);
        return new DhbOrderImportBatch.ReturnItem(
                required(returnNumber, "returnNumber"),
                defaultText(first(merged, "OrdersNum", "orders_num", "orderNumber"), effective.orderNumber()),
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

    private DhbOrderImportBatch.ReturnLineItem returnLine(DhbApiModels.ReturnLineView line) {
        return new DhbOrderImportBatch.ReturnLineItem(
                line.sourceId(), line.productGuid(), line.skuNumber(), line.productCode(), line.productName(),
                line.quantity(), line.confirmedQuantity(), line.unitPrice(), line.confirmedPrice(), line.unit(),
                defaultText(line.warehouseNumber(), line.warehouseGuid()), line.warehouseName(), line.remark());
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
                    first(row, "remark", "Remark")));
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
