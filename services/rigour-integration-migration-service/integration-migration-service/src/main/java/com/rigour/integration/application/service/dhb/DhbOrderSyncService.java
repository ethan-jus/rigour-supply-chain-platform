package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.OrderDetail;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbClient.Payment;
import com.rigour.integration.application.port.out.DhbClient.PaymentQuery;
import com.rigour.integration.application.port.out.DhbClient.Receipt;
import com.rigour.integration.application.port.out.DhbClient.ReceiptQuery;
import com.rigour.integration.application.port.out.DhbClient.Shipment;
import com.rigour.integration.application.port.out.DhbClient.ShipmentDetail;
import com.rigour.integration.application.port.out.DhbClient.ShipmentQuery;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.application.port.out.DhbSyncStore;
import com.rigour.integration.application.port.out.DhbSyncStore.DeadLetterWrite;
import com.rigour.integration.application.port.out.DhbSyncStore.ExternalObjectMapping;
import com.rigour.integration.application.port.out.DhbSyncStore.ExternalObjectMappingWrite;
import com.rigour.integration.application.port.out.DhbSyncStore.RawObjectPersistResult;
import com.rigour.integration.application.port.out.DhbSyncStore.ReconciliationCaseWrite;
import com.rigour.integration.application.port.out.ErpStockOutProjectionClient;
import com.rigour.integration.application.port.out.DhbSyncStore.SyncRunStarted;
import com.rigour.integration.application.port.out.DhbSyncStore.SyncTaskContext;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient;
import com.rigour.integration.application.port.out.IamDhbStaffSyncClient.ResolvedStaff;
import com.rigour.integration.application.port.out.OrderSalesOrderProjectionClient;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalGenericStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionCommand;
import com.rigour.erp.api.v1.model.ExternalTransferStockOutProjectionLineCommand;
import com.rigour.erp.api.v1.model.InternalStockOutOrderDetailView;
import com.rigour.erp.api.v1.model.InternalTransferOrderDetailView;
import com.rigour.order.api.v1.model.FundDocumentCommand;
import com.rigour.order.api.v1.model.FundDocumentDetailView;
import com.rigour.order.api.v1.model.SalesOrderCommand;
import com.rigour.order.api.v1.model.SalesOrderDetailView;
import com.rigour.order.api.v1.model.SalesOrderLineCommand;
import com.rigour.order.api.v1.model.SalesOrderLineView;
import com.rigour.order.api.v1.model.SalesPaymentRecordCommand;
import com.rigour.order.api.v1.model.SalesPaymentRecordDetailView;
import com.rigour.order.api.v1.model.SalesShipmentCommand;
import com.rigour.order.api.v1.model.SalesShipmentDetailView;
import com.rigour.order.api.v1.model.SalesShipmentLineCommand;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.settings.client.BusinessDictionaryBatchClient;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Audit;
import com.rigour.settings.client.BusinessDictionaryBatchClient.Observation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订货宝订单同步用例。
 *
 * <p>订货宝只作为来源系统：订单列表和详情先落 Integration Raw Landing，再通过统一外部对象映射
 * 定位客户、商品和规格，最终调用 Order 自研销售订单 API。订货宝原始状态、同步批次和 Raw 字段
 * 不写入销售订单业务主表。</p>
 */
public final class DhbOrderSyncService {

    private static final Logger log = LoggerFactory.getLogger(DhbOrderSyncService.class);
    private static final InheritableThreadLocal<ConcurrentMap<MappingLookupKey, Optional<ExternalObjectMapping>>>
            MAPPING_LOOKUP_CACHE = new InheritableThreadLocal<>();
    private static final UUID SERVICE_PRINCIPAL_ID =
            UUID.fromString("019fb700-0000-7000-8000-00000000d0b0");
    private static final Set<String> DOMAIN_PERMISSIONS = Set.of(
            "order:read", "order:write", "iam:staff:read", "erp:supply:read", "erp:supply:write");
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_PAGES = 100;
    private static final int DEFAULT_DETAIL_CONCURRENCY = 3;
    private static final int MAX_DETAIL_CONCURRENCY = 16;
    private static final String SOURCE_SYSTEM_DINGHUOBAO = "DINGHUOBAO";
    private static final String SOURCE_OBJECT_SALES_ORDER = "SALES_ORDER";
    private static final String SOURCE_OBJECT_SALES_PAYMENT = "SALES_PAYMENT";
    private static final String SOURCE_OBJECT_FUND_RECEIPT = "FUND_RECEIPT";
    private static final String SOURCE_OBJECT_FUND_PAYMENT = "FUND_PAYMENT";
    private static final String SOURCE_OBJECT_SALES_SHIPMENT = "SALES_SHIPMENT";
    private static final String SOURCE_OBJECT_ERP_STOCK_OUT = "ERP_STOCK_OUT";
    private static final String SOURCE_OBJECT_ERP_TRANSFER_ORDER = "ERP_TRANSFER_ORDER";
    private static final String RAW_OBJECT_ORDER_DETAIL = "ORDER_DETAIL";
    private static final String RAW_OBJECT_RECEIPT = "RECEIPT";
    private static final String RAW_OBJECT_PAYMENT = "PAYMENT";
    private static final String RAW_OBJECT_SHIPMENT_DETAIL = "SHIPMENT_CONTENT";
    private static final Pattern INTERNAL_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final DateTimeFormatter D_HMS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SOURCE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<Observation> PRODUCT_UNIT_DICTIONARY_ITEMS = List.of(
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "BOX", "箱"),
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "BUCKET", "桶"),
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "PORTION", "份"),
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "SET", "套"),
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "BED", "床"),
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "PAIR", "副"),
            new Observation("PRODUCT_UNIT", "orderLine.unitCode", "PIECE", "件"));
    private static final Map<String, String> UNIT_ALIASES = Map.ofEntries(
            Map.entry("箱", "BOX"),
            Map.entry("桶", "BUCKET"),
            Map.entry("份", "PORTION"),
            Map.entry("套", "SET"),
            Map.entry("床", "BED"),
            Map.entry("副", "PAIR"),
            Map.entry("件", "PIECE"));

    private final DhbSyncStore store;
    private final DhbClient client;
    private final OrderSalesOrderProjectionClient orderProjectionClient;
    private final ErpStockOutProjectionClient erpStockOutProjectionClient;
    private final IamDhbStaffSyncClient iamStaffClient;
    private final BusinessDictionaryBatchClient dictionaryClient;
    private final int detailConcurrency;

    public DhbOrderSyncService(DhbSyncStore store, DhbClient client,
                               OrderSalesOrderProjectionClient orderProjectionClient,
                               IamDhbStaffSyncClient iamStaffClient) {
        this(store, client, orderProjectionClient, null, iamStaffClient, DEFAULT_DETAIL_CONCURRENCY);
    }

    public DhbOrderSyncService(DhbSyncStore store, DhbClient client,
                               OrderSalesOrderProjectionClient orderProjectionClient,
                               IamDhbStaffSyncClient iamStaffClient,
                               int detailConcurrency) {
        this(store, client, orderProjectionClient, null, iamStaffClient, null, detailConcurrency);
    }

    public DhbOrderSyncService(DhbSyncStore store, DhbClient client,
                               OrderSalesOrderProjectionClient orderProjectionClient,
                               ErpStockOutProjectionClient erpStockOutProjectionClient,
                               IamDhbStaffSyncClient iamStaffClient,
                               int detailConcurrency) {
        this(store, client, orderProjectionClient, erpStockOutProjectionClient,
                iamStaffClient, null, detailConcurrency);
    }

    public DhbOrderSyncService(DhbSyncStore store, DhbClient client,
                               OrderSalesOrderProjectionClient orderProjectionClient,
                               ErpStockOutProjectionClient erpStockOutProjectionClient,
                               IamDhbStaffSyncClient iamStaffClient,
                               BusinessDictionaryBatchClient dictionaryClient,
                               int detailConcurrency) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.orderProjectionClient = Objects.requireNonNull(orderProjectionClient,
                "orderProjectionClient cannot be null");
        this.erpStockOutProjectionClient = erpStockOutProjectionClient;
        this.iamStaffClient = Objects.requireNonNull(iamStaffClient, "iamStaffClient cannot be null");
        this.dictionaryClient = dictionaryClient;
        this.detailConcurrency = normalizeDetailConcurrency(detailConcurrency);
    }

    public SyncRunView runOrderPull(CallerIdentity caller, UUID taskId, SyncRunCommand command) {
        return runOrderPull(caller, taskId, command, null);
    }

    public SyncRunView runOrderPull(CallerIdentity caller, UUID taskId, SyncRunCommand command,
                                    Integer maxPages) {
        Objects.requireNonNull(caller, "caller cannot be null");
        Objects.requireNonNull(taskId, "taskId cannot be null");

        SyncTaskContext task = store.loadTask(caller.tenantId(), taskId);
        validateTask(task);
        Window window = resolveWindow(command);
        int pageSize = resolvePageSize(task, command);
        int pageLimit = resolveMaxPages(maxPages);
        Instant windowFrom = windowFrom(window);
        Instant windowTo = windowTo(window);
        SyncRunStarted started = store.beginRun(caller.tenantId(), caller.userId(), taskId,
                windowFrom, windowTo);
        store.recordSyncLog(caller.tenantId(), taskId, started.runId(), "INFO",
                "订货宝订单同步开始：Raw落库、映射校验、投影到Order销售订单 detailConcurrency="
                        + detailConcurrency, null);
        ensureProductUnitDictionary(caller.tenantId(), taskId, started.runId());

        Counts counts = new Counts();
        Map<String, StaffProjection> staffCache = new ConcurrentHashMap<>();
        Map<String, Object> sourceOrderLocks = new ConcurrentHashMap<>();
        MAPPING_LOOKUP_CACHE.set(new ConcurrentHashMap<>());
        try {
            PageRequest pageRequest = PageRequest.first(pageSize);
            int pages = 0;
            while (true) {
                Page<OrderSummary> page = client.getOrders(task.connector(), new OrderQuery(
                        pageRequest, "all", null, timeWindow(window),
                        "all", "all", null, null));
                pages++;
                counts.fetched += page.items().size();
                store.persistOrderPage(caller.tenantId(), taskId, started.runId(),
                        page.items(), Instant.now());
                counts.addAll(projectDetails("order-detail", page.items(),
                        order -> projectOrder(caller, task, started.runId(), order, staffCache)));
                if (!page.hasNext() || pages >= pageLimit) {
                    break;
                }
                pageRequest = page.nextRequest();
            }
            syncShipments(caller, task, started.runId(), window, pageSize, pageLimit, counts,
                    staffCache, sourceOrderLocks);
            syncReceipts(caller, task, started.runId(), window, pageSize, pageLimit, counts);
            syncPayments(caller, task, started.runId(), window, pageSize, pageLimit, counts);

            String status = counts.rejected == 0 ? "SUCCEEDED" : "PARTIAL";
            String errorCode = counts.rejected == 0 ? null : "DHB_ORDER_PROJECTION_PARTIAL";
            String errorMessage = counts.rejected == 0 ? null
                    : "部分订货宝订单缺少客户/商品/SKU映射或无法映射到自研销售订单，checkpoint 未推进";
            store.recordSyncLog(caller.tenantId(), taskId, started.runId(),
                    counts.rejected == 0 ? "INFO" : "WARN",
                    "订货宝订单同步结束 status=" + status + " fetched=" + counts.fetched
                            + " accepted=" + counts.accepted + " duplicate=" + counts.duplicate
                            + " rejected=" + counts.rejected,
                    errorCode);
            store.finishRun(caller.tenantId(), caller.userId(), taskId, started.runId(),
                    windowFrom, windowTo, status, counts.fetched, counts.accepted,
                    counts.duplicate, counts.rejected,
                    windowTo == null ? null : windowTo.toString(), errorCode, errorMessage);
            return new SyncRunView(started.runId(), taskId, status, windowFrom, windowTo,
                    counts.fetched, counts.accepted, counts.duplicate, counts.rejected,
                    errorCode, errorMessage);
        } catch (RuntimeException error) {
            String errorCode = "DHB_SYNC_FAILED";
            String errorMessage = safeMessage(error);
            try {
                store.recordSyncLog(caller.tenantId(), taskId, started.runId(), "ERROR",
                        "订货宝订单同步失败：" + errorMessage, errorCode);
                store.finishRun(caller.tenantId(), caller.userId(), taskId, started.runId(),
                        windowFrom, windowTo, "FAILED", counts.fetched, counts.accepted,
                        counts.duplicate, counts.rejected, null, errorCode, errorMessage);
            } catch (RuntimeException persistError) {
                log.error("订货宝同步失败后无法写入同步批次 tenantId={} taskId={} runId={}",
                        caller.tenantId(), taskId, started.runId(), persistError);
            }
            throw error;
        } finally {
            MAPPING_LOOKUP_CACHE.remove();
        }
    }

    private void ensureProductUnitDictionary(UUID tenantId, UUID taskId, UUID runId) {
        if (dictionaryClient == null) return;
        try {
            Audit audit = dictionaryClient.sync(BusinessDictionaryBatchClient.serviceCaller(
                    "rigour-integration-migration-service", "DHB_ORDER_DICTIONARY_SYNC", tenantId),
                    "ORDER", PRODUCT_UNIT_DICTIONARY_ITEMS);
            if (audit.unmapped() > 0) {
                store.recordSyncLog(tenantId, taskId, runId, "WARN",
                        "订货宝订单同步字典补齐存在未解析项 count=" + audit.unmapped()
                                + " issues=" + audit.issues(), "DHB_ORDER_DICTIONARY_SYNC_PARTIAL");
            } else {
                store.recordSyncLog(tenantId, taskId, runId, "INFO",
                        "订货宝订单同步字典补齐完成 revisions=" + audit.revisions(), null);
            }
        } catch (RuntimeException error) {
            store.recordSyncLog(tenantId, taskId, runId, "WARN",
                    "订货宝订单同步字典补齐不可用 reason=" + safeMessage(error),
                    "DHB_ORDER_DICTIONARY_SYNC_UNAVAILABLE");
        }
    }

    private ProjectionOutcome projectOrder(CallerIdentity caller, SyncTaskContext task,
                                           UUID runId, OrderSummary summary,
                                           Map<String, StaffProjection> staffCache) {
        String sourceOrderNo = firstNonBlank(summary == null ? null : summary.orderNumber(),
                summary == null ? null : summary.sourceId(),
                first(map(summary == null ? null : summary.attributes()), "OrderSN", "orders_num"));
        if (blank(sourceOrderNo)) {
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "WARN",
                    "订货宝订单缺少订单号，已拒绝投影", "DHB_ORDER_NO_MISSING");
            return ProjectionOutcome.REJECTED;
        }

        RawObjectPersistResult raw = null;
        try {
            OrderDetail detail = client.getOrderContent(task.connector(), sourceOrderNo, false, false);
            Map<String, Object> payload = combinedPayload(summary, detail);
            raw = store.persistRawObject(caller.tenantId(), task.connectorId(), runId,
                    RAW_OBJECT_ORDER_DETAIL, sourceOrderNo,
                    summary == null || summary.updatedAt() == null ? null : summary.updatedAt().toString(),
                    summary == null ? null : summary.updatedAt(), payload, Instant.now());
            ExternalObjectMapping existing = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                    SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
            SalesOrderDetailView current = existing == null || existing.internalObjectId() == null
                    ? null
                    : orderProjectionClient.salesOrder(orderServiceCaller(caller.tenantId()),
                    existing.internalObjectId());
            PreparedSalesOrder prepared = prepareSalesOrder(
                    caller.tenantId(), task.connectorId(), sourceOrderNo, summary, detail,
                    staffCache);
            if (existing != null && existing.internalObjectId() != null
                    && Objects.equals(existing.payloadChecksum(), raw.payloadChecksum())
                    && projectionComplete(current, prepared.command())) {
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "DEBUG",
                        "订货宝订单未变化，跳过业务投影 orderNo=" + sourceOrderNo, null);
                return ProjectionOutcome.DUPLICATE;
            }

            SalesOrderDetailView projected = upsertSalesOrder(caller.tenantId(), existing, current, prepared);
            store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                    new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_SALES_ORDER,
                            sourceOrderNo, sourceOrderNo, "ORDER", "SALES_ORDER", projected.id(),
                            projected.orderNo(), "ACTIVE", runId, Instant.now(), raw.payloadChecksum(),
                            null, "订货宝订单已投影到自研销售订单"));
            store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                    "订货宝订单已投影到Order销售订单 sourceOrderNo=" + sourceOrderNo
                            + " salesOrderNo=" + projected.orderNo(),
                    null);
            return ProjectionOutcome.ACCEPTED;
        } catch (ProjectionRejected rejected) {
            recordRejected(caller, task, runId, raw, sourceOrderNo, rejected.code(),
                    rejected.getMessage(), rejected.checkType(), rejected.expected(), rejected.actual());
            return ProjectionOutcome.REJECTED;
        } catch (RuntimeException error) {
            recordRejected(caller, task, runId, raw, sourceOrderNo, "DHB_ORDER_PROJECTION_FAILED",
                    safeMessage(error), "PROJECTION", Map.of("sourceOrderNo", sourceOrderNo),
                    Map.of("error", safeMessage(error)));
            return ProjectionOutcome.REJECTED;
        }
    }

    private void syncReceipts(CallerIdentity caller, SyncTaskContext task, UUID runId,
                              Window window, int pageSize, int pageLimit, Counts counts) {
        PageRequest pageRequest = PageRequest.first(pageSize);
        int pages = 0;
        while (true) {
            Page<Receipt> page = client.getReceipts(task.connector(),
                    new ReceiptQuery(pageRequest, null, timeWindow(window),
                            window == null ? null : window.from(), "all"));
            pages++;
            counts.fetched += page.items().size();
            for (Receipt receipt : page.items()) {
                counts.add(projectReceipt(caller, task, runId, receipt));
            }
            if (!page.hasNext() || pages >= pageLimit) {
                break;
            }
            pageRequest = page.nextRequest();
        }
    }

    private void syncPayments(CallerIdentity caller, SyncTaskContext task, UUID runId,
                              Window window, int pageSize, int pageLimit, Counts counts) {
        PageRequest pageRequest = PageRequest.first(pageSize);
        int pages = 0;
        while (true) {
            Page<Payment> page = client.getPayments(task.connector(),
                    new PaymentQuery(pageRequest, null, timeWindow(window), "all"));
            pages++;
            counts.fetched += page.items().size();
            for (Payment payment : page.items()) {
                counts.add(projectPayment(caller, task, runId, payment));
            }
            if (!page.hasNext() || pages >= pageLimit) {
                break;
            }
            pageRequest = page.nextRequest();
        }
    }

    private void syncShipments(CallerIdentity caller, SyncTaskContext task, UUID runId,
                               Window window, int pageSize, int pageLimit, Counts counts,
                               Map<String, StaffProjection> staffCache,
                               Map<String, Object> sourceOrderLocks) {
        PageRequest pageRequest = PageRequest.first(pageSize);
        int pages = 0;
        while (true) {
            Page<Shipment> page = client.getShipments(task.connector(),
                    new ShipmentQuery(pageRequest, null, null, null,
                            timeWindow(window), null, null, null, null));
            pages++;
            counts.fetched += page.items().size();
            counts.addAll(projectDetails("shipment-detail", page.items(),
                    shipment -> projectShipment(caller, task, runId, shipment,
                            staffCache, sourceOrderLocks)));
            if (!page.hasNext() || pages >= pageLimit) {
                break;
            }
            pageRequest = page.nextRequest();
        }
    }

    private ProjectionOutcome projectShipment(CallerIdentity caller, SyncTaskContext task,
                                              UUID runId, Shipment summary,
                                              Map<String, StaffProjection> staffCache,
                                              Map<String, Object> sourceOrderLocks) {
        String sourceShipmentNo = firstNonBlank(
                summary == null ? null : summary.shipmentNumber(),
                summary == null ? null : summary.sourceId(),
                first(map(summary == null ? null : summary.attributes()), "ShipsNum", "ships_num"));
        if (blank(sourceShipmentNo)) {
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "WARN",
                    "订货宝发货单缺少单号，已拒绝投影", "DHB_SHIPMENT_NO_MISSING");
            return ProjectionOutcome.REJECTED;
        }

        RawObjectPersistResult raw = null;
        String rejectedSourceObjectType = SOURCE_OBJECT_SALES_SHIPMENT;
        try {
            ShipmentDetail detail = client.getShipmentContent(task.connector(), sourceShipmentNo);
            Map<String, Object> payload = combinedShipmentPayload(summary, detail);
            raw = store.persistRawObject(caller.tenantId(), task.connectorId(), runId,
                    RAW_OBJECT_SHIPMENT_DETAIL, sourceShipmentNo,
                    summary == null || summary.updatedAt() == null ? null : summary.updatedAt().toString(),
                    summary == null ? null : summary.updatedAt(), payload, Instant.now());
            String stockOutTypeCode = stockOutTypeCode(summary, payload);
            ExternalObjectMapping existingStockOut = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                    SOURCE_OBJECT_ERP_STOCK_OUT, sourceShipmentNo);
            if ("TRANSFER".equals(stockOutTypeCode)) {
                rejectedSourceObjectType = SOURCE_OBJECT_ERP_STOCK_OUT;
                ProjectedTransferStockOut projected = projectExternalTransferStockOut(caller, task, runId, raw,
                        existingStockOut, prepareTransferStockOut(caller.tenantId(), task.connectorId(),
                                sourceShipmentNo, summary, detail, payload));
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                        "订货宝调拨出库已投影到ERP调拨单和出库单 shipmentNo=" + sourceShipmentNo
                                + " transferNo=" + projected.transferNo()
                                + " stockOutNo=" + projected.stockOutNo(),
                        null);
                return projected.duplicate() ? ProjectionOutcome.DUPLICATE : ProjectionOutcome.ACCEPTED;
            }
            if (!"SALES".equals(stockOutTypeCode)) {
                rejectedSourceObjectType = SOURCE_OBJECT_ERP_STOCK_OUT;
                ProjectedStockOut projected = projectExternalGenericStockOut(caller, task, runId, raw,
                        existingStockOut, prepareGenericStockOut(caller.tenantId(), task.connectorId(),
                                sourceShipmentNo, stockOutTypeCode, summary, detail, payload));
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                        "订货宝非销售出库已投影到ERP统一出库单 shipmentNo=" + sourceShipmentNo
                                + " stockOutTypeCode=" + stockOutTypeCode
                                + " stockOutNo=" + projected.stockOutNo(),
                        null);
                return projected.duplicate() ? ProjectionOutcome.DUPLICATE : ProjectionOutcome.ACCEPTED;
            }

            ExternalObjectMapping existing = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                    SOURCE_OBJECT_SALES_SHIPMENT, sourceShipmentNo);
            SalesShipmentDetailView current = existing == null || existing.internalObjectId() == null
                    ? null
                    : orderProjectionClient.salesShipment(orderServiceCaller(caller.tenantId()),
                    existing.internalObjectId());
            if (existing != null && existing.internalObjectId() != null
                    && existingStockOut != null && existingStockOut.internalObjectId() != null
                    && Objects.equals(existing.payloadChecksum(), raw.payloadChecksum())
                    && shipmentProjectionComplete(current)) {
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "DEBUG",
                        "订货宝发货单未变化，跳过业务投影 shipmentNo=" + sourceShipmentNo, null);
                return ProjectionOutcome.DUPLICATE;
            }

            String sourceOrderNo = shipmentSourceOrderNo(summary, payload);
            ensureSalesOrderMapping(caller, task, runId, sourceOrderNo, staffCache, sourceOrderLocks);
            PreparedSalesShipment prepared = prepareSalesShipment(caller.tenantId(), task.connectorId(),
                    sourceShipmentNo, summary, detail, payload);
            ProjectedStockOut projectedStockOut = projectExternalStockOut(caller, task, runId, raw,
                    existingStockOut, prepareSalesStockOut(sourceShipmentNo, prepared));
            prepared = prepared.withStockOut(projectedStockOut);
            SalesShipmentDetailView projected =
                    upsertSalesShipment(caller.tenantId(), existing, current, prepared);
            store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                    new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_SALES_SHIPMENT,
                            sourceShipmentNo, sourceShipmentNo, "ORDER", "SALES_SHIPMENT", projected.id(),
                            projected.shipmentNo(), "ACTIVE", runId, Instant.now(), raw.payloadChecksum(),
                            null, "订货宝发货单已投影到自研销售发货单"));
            store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                    "订货宝发货单已投影到Order销售发货 shipmentNo=" + sourceShipmentNo
                            + " salesShipmentNo=" + projected.shipmentNo()
                            + " stockOutNo=" + projectedStockOut.stockOutNo(),
                    null);
            return ProjectionOutcome.ACCEPTED;
        } catch (ProjectionRejected rejected) {
            recordRejected(caller, task, runId, raw, rejectedSourceObjectType, sourceShipmentNo, rejected.code(),
                    rejected.getMessage(), rejected.checkType(), rejected.expected(), rejected.actual());
            return ProjectionOutcome.REJECTED;
        } catch (RuntimeException error) {
            recordRejected(caller, task, runId, raw, rejectedSourceObjectType, sourceShipmentNo,
                    "DHB_SHIPMENT_PROJECTION_FAILED",
                    safeMessage(error), "PROJECTION", Map.of("sourceShipmentNo", sourceShipmentNo),
                    Map.of("error", safeMessage(error)));
            return ProjectionOutcome.REJECTED;
        }
    }

    private ProjectionOutcome projectReceipt(CallerIdentity caller, SyncTaskContext task,
                                             UUID runId, Receipt receipt) {
        Map<String, Object> attributes = map(receipt == null ? null : receipt.attributes());
        String sourceReceiptNo = firstNonBlank(
                receipt == null ? null : receipt.receiptNumber(),
                receipt == null ? null : receipt.sourceId(),
                first(attributes, "ReceiptsNum", "receipts_num", "receiptNumber"));
        if (blank(sourceReceiptNo)) {
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "WARN",
                    "订货宝收款单缺少单号，已拒绝投影", "DHB_RECEIPT_NO_MISSING");
            return ProjectionOutcome.REJECTED;
        }

        RawObjectPersistResult raw = null;
        try {
            raw = store.persistRawObject(caller.tenantId(), task.connectorId(), runId,
                    RAW_OBJECT_RECEIPT, sourceReceiptNo,
                    receipt == null || receipt.updatedAt() == null ? null : receipt.updatedAt().toString(),
                    receipt == null ? null : receipt.updatedAt(), attributes, Instant.now());
            if (receiptCancelled(receipt == null ? null : receipt.status())) {
                projectFundReceipt(caller, task, runId, sourceReceiptNo, receipt, attributes, raw);
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "DEBUG",
                        "订货宝收款单已取消，仅保留资金收款单 receiptNo=" + sourceReceiptNo, null);
                return ProjectionOutcome.ACCEPTED;
            }
            projectFundReceipt(caller, task, runId, sourceReceiptNo, receipt, attributes, raw);
            String sourceOrderNo = receiptSourceOrderNo(receipt, attributes);
            ExternalObjectMapping orderMapping = blank(sourceOrderNo) ? null
                    : store.findActiveMapping(caller.tenantId(), task.connectorId(),
                    SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
            if (orderMapping == null || orderMapping.internalObjectId() == null) {
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                        "订货宝收款单已投影到资金收款单，未关联销售订单，跳过销售回款核销 receiptNo="
                                + sourceReceiptNo + " sourceOrderNo=" + sourceOrderNo,
                        "DHB_RECEIPT_ORDER_MAPPING_SKIPPED");
                return ProjectionOutcome.ACCEPTED;
            }
            ExternalObjectMapping existing = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                    SOURCE_OBJECT_SALES_PAYMENT, sourceReceiptNo);
            SalesPaymentRecordDetailView current = existing == null || existing.internalObjectId() == null
                    ? null
                    : orderProjectionClient.salesPayment(orderServiceCaller(caller.tenantId()),
                    existing.internalObjectId());
            if (existing != null && existing.internalObjectId() != null
                    && Objects.equals(existing.payloadChecksum(), raw.payloadChecksum())
                    && paymentProjectionComplete(current)) {
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "DEBUG",
                        "订货宝收款单未变化，跳过业务投影 receiptNo=" + sourceReceiptNo, null);
                return ProjectionOutcome.DUPLICATE;
            }

            PreparedSalesPayment prepared = prepareSalesPayment(caller.tenantId(), task.connectorId(),
                    sourceReceiptNo, receipt, attributes);
            SalesPaymentRecordDetailView projected =
                    upsertSalesPayment(caller.tenantId(), existing, current, prepared);
            store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                    new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_SALES_PAYMENT,
                            sourceReceiptNo, sourceReceiptNo, "ORDER", "SALES_PAYMENT", projected.id(),
                            projected.paymentNo(), "ACTIVE", runId, Instant.now(), raw.payloadChecksum(),
                            null, "订货宝收款单已投影到自研销售回款记录"));
            store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                    "订货宝收款单已投影到Order销售回款 receiptNo=" + sourceReceiptNo
                            + " paymentNo=" + projected.paymentNo(),
                    null);
            return ProjectionOutcome.ACCEPTED;
        } catch (ProjectionRejected rejected) {
            recordPaymentRejected(caller, task, runId, raw, sourceReceiptNo, rejected.code(),
                    rejected.getMessage(), rejected.checkType(), rejected.expected(), rejected.actual());
            return ProjectionOutcome.REJECTED;
        } catch (RuntimeException error) {
            recordPaymentRejected(caller, task, runId, raw, sourceReceiptNo, "DHB_RECEIPT_PROJECTION_FAILED",
                    safeMessage(error), "PROJECTION", Map.of("sourceReceiptNo", sourceReceiptNo),
                    Map.of("error", safeMessage(error)));
            return ProjectionOutcome.REJECTED;
        }
    }

    private ProjectionOutcome projectPayment(CallerIdentity caller, SyncTaskContext task,
                                             UUID runId, Payment payment) {
        Map<String, Object> attributes = map(payment == null ? null : payment.attributes());
        String sourcePaymentNo = firstNonBlank(
                payment == null ? null : payment.paymentNumber(),
                payment == null ? null : payment.sourceId(),
                first(attributes, "PaymentNum", "payment_num", "paymentNumber"));
        if (blank(sourcePaymentNo)) {
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "WARN",
                    "订货宝付款单缺少单号，已拒绝投影", "DHB_PAYMENT_NO_MISSING");
            return ProjectionOutcome.REJECTED;
        }

        RawObjectPersistResult raw = null;
        try {
            Instant sourceUpdatedAt = firstNonNull(
                    payment == null ? null : payment.transactionAt(),
                    payment == null ? null : payment.createdAt());
            raw = store.persistRawObject(caller.tenantId(), task.connectorId(), runId,
                    RAW_OBJECT_PAYMENT, sourcePaymentNo,
                    sourceUpdatedAt == null ? null : sourceUpdatedAt.toString(),
                    sourceUpdatedAt, attributes, Instant.now());
            ExternalObjectMapping existing = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                    SOURCE_OBJECT_FUND_PAYMENT, sourcePaymentNo);
            FundDocumentDetailView current = existing == null || existing.internalObjectId() == null
                    ? null
                    : orderProjectionClient.fundDocument(orderServiceCaller(caller.tenantId()),
                    existing.internalObjectId());
            if (existing != null && existing.internalObjectId() != null
                    && Objects.equals(existing.payloadChecksum(), raw.payloadChecksum())
                    && fundDocumentProjectionComplete(current, "PAYMENT")) {
                store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
                store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "DEBUG",
                        "订货宝付款单未变化，跳过业务投影 paymentNo=" + sourcePaymentNo, null);
                return ProjectionOutcome.DUPLICATE;
            }

            PreparedFundDocument prepared = preparePaymentFundDocument(caller.tenantId(), task.connectorId(),
                    sourcePaymentNo, payment, attributes);
            FundDocumentDetailView projected =
                    upsertFundDocument(caller.tenantId(), existing, current, prepared);
            store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                    new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_FUND_PAYMENT,
                            sourcePaymentNo, sourcePaymentNo, "ORDER", "FUND_DOCUMENT", projected.id(),
                            projected.documentNo(), "ACTIVE", runId, Instant.now(), raw.payloadChecksum(),
                            null, "订货宝付款单已投影到自研资金付款单"));
            store.markRawProcessed(caller.tenantId(), raw.rawLandingId());
            store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "INFO",
                    "订货宝付款单已投影到Order资金付款单 paymentNo=" + sourcePaymentNo
                            + " documentNo=" + projected.documentNo(),
                    null);
            return ProjectionOutcome.ACCEPTED;
        } catch (ProjectionRejected rejected) {
            recordFundPaymentRejected(caller, task, runId, raw, sourcePaymentNo, rejected.code(),
                    rejected.getMessage(), rejected.checkType(), rejected.expected(), rejected.actual());
            return ProjectionOutcome.REJECTED;
        } catch (RuntimeException error) {
            recordFundPaymentRejected(caller, task, runId, raw, sourcePaymentNo, "DHB_PAYMENT_PROJECTION_FAILED",
                    safeMessage(error), "PROJECTION", Map.of("sourcePaymentNo", sourcePaymentNo),
                    Map.of("error", safeMessage(error)));
            return ProjectionOutcome.REJECTED;
        }
    }

    private SalesOrderDetailView upsertSalesOrder(UUID tenantId, ExternalObjectMapping existing,
                                                  SalesOrderDetailView current,
                                                  PreparedSalesOrder prepared) {
        CallerIdentity serviceCaller = orderServiceCaller(tenantId);
        if (current == null && existing != null && existing.internalObjectId() != null) {
            current = orderProjectionClient.salesOrder(serviceCaller, existing.internalObjectId());
        }
        if (current != null) {
            if ("CANCELLED".equalsIgnoreCase(current.orderStatusCode())) {
                if (prepared.cancelled()) {
                    return current;
                }
                throw new ProjectionRejected("DHB_ORDER_ALREADY_CANCELLED",
                        "已取消的我方销售订单不能被订货宝来源重新打开",
                        "STATUS", Map.of("sourceStatus", prepared.sourceStatus()),
                        Map.of("salesOrderStatus", current.orderStatusCode()));
            }
            if (!salesOrderDraft(current)) {
                if (prepared.cancelled()) {
                    return orderProjectionClient.cancelSalesOrder(serviceCaller, current.id(), current.revision());
                }
                return current;
            }
        }
        SalesOrderCommand command = withRevision(prepared.command(),
                current == null ? null : current.revision());
        SalesOrderDetailView saved = current == null
                ? orderProjectionClient.createSalesOrder(serviceCaller, command)
                : orderProjectionClient.updateSalesOrder(serviceCaller, current.id(), command);
        if (prepared.cancelled() && !"CANCELLED".equalsIgnoreCase(saved.orderStatusCode())) {
            saved = orderProjectionClient.cancelSalesOrder(serviceCaller, saved.id(), saved.revision());
        }
        return saved;
    }

    private void ensureSalesOrderMapping(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                         String sourceOrderNo,
                                         Map<String, StaffProjection> staffCache,
                                         Map<String, Object> sourceOrderLocks) {
        if (blank(sourceOrderNo)) {
            return;
        }
        String lockKey = sourceOrderNo.strip();
        Object lock = sourceOrderLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            ensureSalesOrderMappingLocked(caller, task, runId, lockKey, staffCache);
        }
    }

    private void ensureSalesOrderMappingLocked(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                               String sourceOrderNo,
                                               Map<String, StaffProjection> staffCache) {
        ExternalObjectMapping existing = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
        if (existing != null && existing.internalObjectId() != null) {
            return;
        }
        ProjectionOutcome outcome = projectOrder(caller, task, runId,
                new OrderSummary(sourceOrderNo, sourceOrderNo, null, null, null, null,
                        null, null, Map.of("OrderSN", sourceOrderNo)),
                staffCache);
        if (outcome == ProjectionOutcome.REJECTED) {
            throw new ProjectionRejected("DHB_SHIPMENT_ORDER_MAPPING_MISSING",
                    "订货宝发货单对应销售订单补同步失败，需先修复父订单",
                    "MAPPING", Map.of("required", "SALES_ORDER mapping"),
                    Map.of("sourceOrderNo", sourceOrderNo));
        }
    }

    private SalesPaymentRecordDetailView upsertSalesPayment(
            UUID tenantId, ExternalObjectMapping existing,
            SalesPaymentRecordDetailView current, PreparedSalesPayment prepared) {
        CallerIdentity serviceCaller = orderServiceCaller(tenantId);
        if (current == null && existing != null && existing.internalObjectId() != null) {
            current = orderProjectionClient.salesPayment(serviceCaller, existing.internalObjectId());
        }
        SalesPaymentRecordCommand command = withRevision(prepared.command(),
                current == null ? null : current.revision());
        return current == null
                ? orderProjectionClient.createSalesPayment(serviceCaller, command)
                : orderProjectionClient.updateSalesPayment(serviceCaller, current.id(), command);
    }

    private FundDocumentDetailView upsertFundDocument(
            UUID tenantId, ExternalObjectMapping existing,
            FundDocumentDetailView current, PreparedFundDocument prepared) {
        CallerIdentity serviceCaller = orderServiceCaller(tenantId);
        if (current == null && existing != null && existing.internalObjectId() != null) {
            current = orderProjectionClient.fundDocument(serviceCaller, existing.internalObjectId());
        }
        FundDocumentCommand command = withRevision(prepared.command(),
                current == null ? null : current.revision());
        return current == null
                ? orderProjectionClient.createFundDocument(serviceCaller, command)
                : orderProjectionClient.updateFundDocument(serviceCaller, current.id(), command);
    }

    private SalesShipmentDetailView upsertSalesShipment(
            UUID tenantId, ExternalObjectMapping existing,
            SalesShipmentDetailView current, PreparedSalesShipment prepared) {
        CallerIdentity serviceCaller = orderServiceCaller(tenantId);
        if (current == null && existing != null && existing.internalObjectId() != null) {
            current = orderProjectionClient.salesShipment(serviceCaller, existing.internalObjectId());
        }
        SalesShipmentCommand command = withRevision(prepared.command(),
                current == null ? null : current.revision());
        return current == null
                ? orderProjectionClient.createSalesShipment(serviceCaller, command)
                : orderProjectionClient.updateSalesShipment(serviceCaller, current.id(), command);
    }

    private static boolean projectionComplete(SalesOrderDetailView current, SalesOrderCommand expected) {
        if (current == null
                || current.customerId() == null
                || blank(current.customerNameSnapshot())
                || current.orderDate() == null
                || current.lines() == null
                || current.lines().isEmpty()
                || current.lines().stream().anyMatch(line ->
                line.productId() == null
                        || line.productVariantId() == null
                        || blank(line.unitCode())
                        || line.quantity() == null
                        || line.quantity().compareTo(BigDecimal.ZERO) <= 0
                        || line.unitPrice() == null)) {
            return false;
        }
        if (expected == null) return true;
        if (!blank(expected.regionCode())
                && !Objects.equals(current.regionCode(), expected.regionCode())) {
            return false;
        }
        if (!blank(expected.ownerStaffCode())
                && !Objects.equals(current.ownerStaffCode(), expected.ownerStaffCode())) {
            return false;
        }
        List<SalesOrderLineCommand> expectedLines = expected.lines() == null ? List.of() : expected.lines();
        if (expectedLines.isEmpty()) return true;
        if (!sameDecimal(current.totalQuantity(), sumLineQuantity(expectedLines))) return false;
        if (current.lines().size() != expectedLines.size()) return false;
        for (int i = 0; i < expectedLines.size(); i++) {
            if (!lineMatches(current.lines().get(i), expectedLines.get(i))) return false;
        }
        return true;
    }

    private static boolean lineMatches(SalesOrderLineView current, SalesOrderLineCommand expected) {
        return current != null
                && expected != null
                && Objects.equals(current.productId(), expected.productId())
                && Objects.equals(current.productVariantId(), expected.productVariantId())
                && Objects.equals(current.productCodeSnapshot(), expected.productCodeSnapshot())
                && Objects.equals(current.skuCodeSnapshot(), expected.skuCodeSnapshot())
                && sameDecimal(current.quantity(), expected.quantity())
                && sameDecimal(current.unitPrice(), expected.unitPrice());
    }

    private static BigDecimal sumLineQuantity(List<SalesOrderLineCommand> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrderLineCommand line : lines) {
            if (line != null && line.quantity() != null) total = total.add(line.quantity());
        }
        return total;
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    private static boolean paymentProjectionComplete(SalesPaymentRecordDetailView current) {
        return current != null
                && current.orderId() != null
                && !blank(current.paymentNo())
                && current.paymentTime() != null
                && current.paidAmount() != null
                && current.paidAmount().compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean fundDocumentProjectionComplete(FundDocumentDetailView current, String directionCode) {
        return current != null
                && !blank(current.documentNo())
                && Objects.equals(current.directionCode(), directionCode)
                && current.occurredTime() != null
                && !blank(current.documentStatusCode())
                && current.amount() != null
                && current.amount().compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean shipmentProjectionComplete(SalesShipmentDetailView current) {
        return current != null
                && current.salesOrderId() != null
                && !blank(current.shipmentNo())
                && current.lines() != null
                && !current.lines().isEmpty()
                && current.lines().stream().allMatch(line ->
                line.productId() != null
                        && line.productVariantId() != null
                        && line.shippedQuantity() != null
                        && line.shippedQuantity().compareTo(BigDecimal.ZERO) > 0);
    }

    private PreparedSalesOrder prepareSalesOrder(UUID tenantId, UUID connectorId,
                                                 String sourceOrderNo, OrderSummary summary,
                                                 OrderDetail detail,
                                                 Map<String, StaffProjection> staffCache) {
        Map<String, Object> list = map(summary == null ? null : summary.attributes());
        Map<String, Object> content = map(detail == null ? null : detail.attributes());
        String sourceStatus = firstNonBlank(
                detail == null ? null : detail.status(),
                first(content, list, "OrderStatus", "order_status", "status"),
                summary == null ? null : summary.status());
        List<String> customerSourceIds = candidates(content, list,
                "ClientGUID", "clientGUID", "ClientGuid", "clientGuid", "client_guid",
                "ClientNO", "clientNO", "ClientNum", "clientNum", "client_num",
                "ClientID", "clientID", "ClientId", "clientId", "customerNumber",
                "customerNo", "CustomerNo", "customerCode", "CustomerCode",
                "clientAccount", "ClientAccount", "ClientName", "ClientCompanyName");
        ExternalObjectMapping customer = mappingAny(tenantId, connectorId, List.of("CUSTOMER"),
                customerSourceIds,
                sourceOrderNo, "客户");
        List<SalesOrderLineCommand> lines = salesOrderLines(tenantId, connectorId, sourceOrderNo, content);
        boolean cancelled = isCancelled(sourceStatus);
        List<String> sourceStaffIds = candidates(content, list,
                "StaffID", "StaffId", "staffID", "staffId", "staff_id",
                "SalesmanID", "SalesmanId", "salesmanID", "salesmanId", "salesman_id",
                "BusinessStaffID", "BusinessStaffId", "businessStaffID", "businessStaffId",
                "business_staff_id", "AdminID", "AdminId", "adminID", "adminId", "admin_id",
                "UserID", "UserId", "userID", "userId", "user_id",
                "OperatorID", "OperatorId", "operatorID", "operatorId", "operator_id");
        List<String> sourceStaffNames = candidates(content, list,
                "StaffName", "staffName", "staff_name", "OperationName", "operationName",
                "operation_name", "SalesmanName", "salesmanName", "salesman_name",
                "BusinessStaffName", "businessStaffName", "business_staff_name",
                "AdminName", "adminName", "admin_name", "UserName", "userName", "user_name",
                "OperatorName", "operatorName", "operator_name");
        StaffProjection owner = ownerStaff(tenantId, connectorId, sourceStaffIds,
                sourceStaffNames, staffCache);
        String regionCode = customerAreaCode(tenantId, connectorId, content, list);
        SalesOrderCommand command = new SalesOrderCommand(
                requiredInternalId(customer, sourceOrderNo, "客户"),
                SOURCE_SYSTEM_DINGHUOBAO,
                sourceOrderNo,
                customer.internalObjectNo(),
                firstNonBlank(first(content, list, "ClientName", "ClientCompanyName", "client_name"),
                        customer.sourceObjectNo(), customer.internalObjectNo(), "订货宝客户"),
                first(content, list, "OrderReceiveName", "Consignee", "ReceiverName", "LinkMan"),
                first(content, list, "OrderReceivePhone", "Mobile", "Phone", "ReceiverPhone"),
                regionCode,
                null,
                owner.staffName(),
                owner.staffCode(),
                owner.staffName(),
                firstInstant(content, list, summary == null ? null : summary.createdAt(),
                        "OrderDate", "order_date", "createdAt"),
                null,
                null,
                null,
                null,
                first(content, list, "OrderRemark", "Remark", "remark"),
                lines,
                !cancelled && shouldSubmit(sourceStatus),
                null);
        return new PreparedSalesOrder(sourceOrderNo, sourceStatus, command, cancelled);
    }

    private String customerAreaCode(UUID tenantId, UUID connectorId,
                                    Map<String, Object> content, Map<String, Object> list) {
        List<String> sourceIds = candidates(content, list,
                "ClientArea", "clientArea", "client_area",
                "ClientAreaID", "ClientAreaId", "clientAreaID", "clientAreaId", "client_area_id",
                "ClientAreaGuid", "ClientAreaGUID", "clientAreaGuid", "client_area_guid",
                "AreaID", "AreaId", "areaID", "areaId", "area_id",
                "AreaCode", "areaCode", "area_code",
                "RegionCode", "regionCode", "region_code",
                "ClientAreaName", "clientAreaName", "client_area_name",
                "AreaName", "areaName", "area_name",
                "RegionName", "regionName", "region_name", "Region", "region");
        ExternalObjectMapping area = optionalMappingAny(tenantId, connectorId,
                List.of("CUSTOMER_AREA"), sourceIds);
        return area == null ? null
                : firstNonBlank(area.internalObjectNo(), area.sourceObjectNo(), area.sourceObjectId());
    }

    private PreparedSalesPayment prepareSalesPayment(UUID tenantId, UUID connectorId,
                                                     String sourceReceiptNo, Receipt receipt,
                                                     Map<String, Object> attributes) {
        String sourceOrderNo = receiptSourceOrderNo(receipt, attributes);
        if (blank(sourceOrderNo)) {
            throw new ProjectionRejected("DHB_RECEIPT_ORDER_NO_MISSING",
                    "订货宝收款单缺少销售订单号，不能生成销售回款记录",
                    "DETAIL", Map.of("required", "OrdersNum"),
                    Map.of("sourceReceiptNo", sourceReceiptNo));
        }
        ExternalObjectMapping orderMapping = store.findActiveMapping(tenantId, connectorId,
                SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
        if (orderMapping == null || orderMapping.internalObjectId() == null) {
            throw new ProjectionRejected("DHB_RECEIPT_ORDER_MAPPING_MISSING",
                    "订货宝收款单找不到对应销售订单映射，需先同步订单",
                    "MAPPING", Map.of("required", "SALES_ORDER mapping"),
                    Map.of("sourceReceiptNo", sourceReceiptNo, "sourceOrderNo", sourceOrderNo));
        }
        BigDecimal amount = receipt == null ? null : receipt.amount();
        if (amount == null) amount = decimal(firstObject(attributes, "Amount", "amount", "PaidAmount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProjectionRejected("DHB_RECEIPT_AMOUNT_INVALID",
                    "订货宝收款单金额为空或小于等于0，不能生成销售回款记录",
                    "DETAIL", Map.of("required", "Amount > 0"),
                    Map.of("sourceReceiptNo", sourceReceiptNo));
        }
        Instant paymentTime = firstNonNull(
                receipt == null ? null : receipt.transactionAt(),
                receipt == null ? null : receipt.createdAt(),
                receipt == null ? null : receipt.updatedAt(),
                Instant.now());
        SalesPaymentRecordCommand command = new SalesPaymentRecordCommand(
                orderMapping.internalObjectId(),
                null,
                null,
                paymentTime,
                paymentMethodCode(receipt, attributes),
                amount,
                List.of(),
                receiptRemark(receipt, attributes),
                null);
        return new PreparedSalesPayment(sourceReceiptNo, sourceOrderNo, command);
    }

    private FundDocumentDetailView projectFundReceipt(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                                      String sourceReceiptNo, Receipt receipt,
                                                      Map<String, Object> attributes,
                                                      RawObjectPersistResult raw) {
        ExternalObjectMapping existing = store.findActiveMapping(caller.tenantId(), task.connectorId(),
                SOURCE_OBJECT_FUND_RECEIPT, sourceReceiptNo);
        FundDocumentDetailView current = existing == null || existing.internalObjectId() == null
                ? null
                : orderProjectionClient.fundDocument(orderServiceCaller(caller.tenantId()),
                existing.internalObjectId());
        if (existing != null && existing.internalObjectId() != null
                && Objects.equals(existing.payloadChecksum(), raw.payloadChecksum())
                && fundDocumentProjectionComplete(current, "RECEIPT")) {
            return current;
        }
        PreparedFundDocument prepared = prepareReceiptFundDocument(caller.tenantId(), task.connectorId(),
                sourceReceiptNo, receipt, attributes);
        FundDocumentDetailView projected =
                upsertFundDocument(caller.tenantId(), existing, current, prepared);
        store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_FUND_RECEIPT,
                        sourceReceiptNo, sourceReceiptNo, "ORDER", "FUND_DOCUMENT", projected.id(),
                        projected.documentNo(), "ACTIVE", runId, Instant.now(), raw.payloadChecksum(),
                        null, "订货宝收款单已投影到自研资金收款单"));
        return projected;
    }

    private PreparedFundDocument prepareReceiptFundDocument(UUID tenantId, UUID connectorId,
                                                           String sourceReceiptNo, Receipt receipt,
                                                           Map<String, Object> attributes) {
        String sourceOrderNo = receiptSourceOrderNo(receipt, attributes);
        ExternalObjectMapping orderMapping = blank(sourceOrderNo) ? null
                : store.findActiveMapping(tenantId, connectorId, SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
        BigDecimal amount = receipt == null ? null : receipt.amount();
        if (amount == null) amount = decimal(firstObject(attributes, "Amount", "amount", "PaidAmount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProjectionRejected("DHB_RECEIPT_AMOUNT_INVALID",
                    "订货宝收款单金额为空或小于等于0，不能生成资金收款单",
                    "DETAIL", Map.of("required", "Amount > 0"),
                    Map.of("sourceReceiptNo", sourceReceiptNo));
        }
        Instant occurredTime = firstNonNull(
                receipt == null ? null : receipt.transactionAt(),
                receipt == null ? null : receipt.createdAt(),
                receipt == null ? null : receipt.updatedAt(),
                Instant.now());
        String customerNo = firstNonBlank(
                receipt == null ? null : receipt.customerNumber(),
                first(attributes, "ClientNum", "clientNum", "ClientNO", "ClientNo", "customerNumber"));
        String customerName = first(attributes, "ClientName", "ClientCompanyName", "CustomerName", "customerName");
        FundDocumentCommand command = new FundDocumentCommand(
                "RECEIPT",
                orderMapping == null ? null : orderMapping.internalObjectId(),
                sourceOrderNo,
                null,
                customerNo,
                customerName,
                "CUSTOMER",
                customerNo,
                customerName,
                null,
                null,
                occurredTime,
                paymentMethodCode(receipt, attributes),
                receiptFundBusinessTypeCode(receipt == null ? null : receipt.businessType(),
                        attributes, sourceOrderNo),
                fundDocumentStatusCode(receipt == null ? null : receipt.status(), attributes),
                amount,
                List.of(),
                receiptRemark(receipt, attributes),
                null);
        return new PreparedFundDocument(sourceReceiptNo, sourceOrderNo, command);
    }

    private PreparedFundDocument preparePaymentFundDocument(UUID tenantId, UUID connectorId,
                                                           String sourcePaymentNo, Payment payment,
                                                           Map<String, Object> attributes) {
        String sourceOrderNo = paymentSourceOrderNo(payment, attributes);
        ExternalObjectMapping orderMapping = blank(sourceOrderNo) ? null
                : store.findActiveMapping(tenantId, connectorId, SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
        BigDecimal amount = payment == null ? null : payment.amount();
        if (amount == null) amount = decimal(firstObject(attributes, "Amount", "amount", "PaidAmount"));
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ProjectionRejected("DHB_PAYMENT_AMOUNT_INVALID",
                    "订货宝付款单金额为空或小于等于0，不能生成资金付款单",
                    "DETAIL", Map.of("required", "Amount > 0"),
                    Map.of("sourcePaymentNo", sourcePaymentNo));
        }
        Instant occurredTime = firstNonNull(
                payment == null ? null : payment.transactionAt(),
                payment == null ? null : payment.createdAt(),
                Instant.now());
        String customerNo = firstNonBlank(
                payment == null ? null : payment.customerNumber(),
                first(attributes, "ClientNum", "clientNum", "ClientNO", "ClientNo", "customerNumber"));
        String customerName = first(attributes, "ClientName", "ClientCompanyName", "CustomerName", "customerName");
        FundDocumentCommand command = new FundDocumentCommand(
                "PAYMENT",
                orderMapping == null ? null : orderMapping.internalObjectId(),
                sourceOrderNo,
                null,
                customerNo,
                customerName,
                "CUSTOMER",
                customerNo,
                customerName,
                null,
                null,
                occurredTime,
                paymentMethodCode(payment, attributes),
                paymentFundBusinessTypeCode(payment == null ? null : payment.businessType(), attributes),
                fundDocumentStatusCode(payment == null ? null : payment.status(), attributes),
                amount,
                List.of(),
                paymentRemark(payment, attributes),
                null);
        return new PreparedFundDocument(sourcePaymentNo, sourceOrderNo, command);
    }

    private PreparedSalesShipment prepareSalesShipment(UUID tenantId, UUID connectorId,
                                                       String sourceShipmentNo, Shipment summary,
                                                       ShipmentDetail detail,
                                                       Map<String, Object> payload) {
        Map<String, Object> list = map(payload == null ? null : mapObject(payload.get("list")));
        Map<String, Object> content = map(payload == null ? null : mapObject(payload.get("detail")));
        String sourceOrderNo = shipmentSourceOrderNo(summary, payload);
        if (blank(sourceOrderNo)) {
            throw new ProjectionRejected("DHB_SHIPMENT_ORDER_NO_MISSING",
                    "订货宝发货单缺少销售订单号，不能生成销售发货单",
                    "DETAIL", Map.of("required", "OrdersNum"),
                    Map.of("sourceShipmentNo", sourceShipmentNo));
        }
        ExternalObjectMapping orderMapping = store.findActiveMapping(tenantId, connectorId,
                SOURCE_OBJECT_SALES_ORDER, sourceOrderNo);
        if (orderMapping == null || orderMapping.internalObjectId() == null) {
            throw new ProjectionRejected("DHB_SHIPMENT_ORDER_MAPPING_MISSING",
                    "订货宝发货单找不到对应销售订单映射，需先同步订单",
                    "MAPPING", Map.of("required", "SALES_ORDER mapping"),
                    Map.of("sourceShipmentNo", sourceShipmentNo, "sourceOrderNo", sourceOrderNo));
        }
        SalesOrderDetailView salesOrder = orderProjectionClient.salesOrder(
                orderServiceCaller(tenantId), orderMapping.internalObjectId());
        if (salesOrder == null) {
            throw new ProjectionRejected("DHB_SHIPMENT_ORDER_TARGET_MISSING",
                    "订货宝发货单对应的我方销售订单不存在，需修复订单映射",
                    "MAPPING", Map.of("required", "salesOrder"),
                    Map.of("sourceShipmentNo", sourceShipmentNo, "sourceOrderNo", sourceOrderNo,
                            "salesOrderId", orderMapping.internalObjectId()));
        }
        Long warehouseId = warehouseId(tenantId, connectorId, sourceShipmentNo, summary, content, list);
        List<SalesShipmentLineCommand> lines = shipmentLines(tenantId, connectorId,
                sourceShipmentNo, content, salesOrder);
        SalesShipmentCommand command = new SalesShipmentCommand(
                orderMapping.internalObjectId(),
                warehouseId,
                null,
                null,
                shipmentStatusCode(firstNonBlank(summary == null ? null : summary.status(),
                        first(content, list, "Status", "status"))),
                firstNonBlank(summary == null ? null : summary.logisticsName(),
                        first(content, list, "LogisticsName", "logisticsName", "ExpressName")),
                firstNonBlank(summary == null ? null : summary.trackingNumber(),
                        first(content, list, "ExpressNumber", "expressNumber", "LogisticsNumber")),
                firstNonNull(summary == null ? null : summary.shipmentAt(),
                        summary == null ? null : summary.createdAt(),
                        summary == null ? null : summary.updatedAt(),
                        detail == null ? null : instant(firstObject(detail.attributes(), "CreateDate")),
                        Instant.now()),
                lines,
                firstNonBlank(summary == null ? null : summary.remark(),
                        first(content, list, "Remark", "remark")),
                null);
        return new PreparedSalesShipment(sourceShipmentNo, sourceOrderNo, salesOrder, command);
    }

    private PreparedStockOut prepareSalesStockOut(String sourceShipmentNo, PreparedSalesShipment prepared) {
        SalesOrderDetailView salesOrder = prepared.salesOrder();
        SalesShipmentCommand shipment = prepared.command();
        List<ExternalStockOutProjectionLineCommand> lines = shipment.lines().stream()
                .map(line -> new ExternalStockOutProjectionLineCommand(
                        line.salesOrderLineId(),
                        null,
                        line.productId(),
                        line.productVariantId(),
                        line.productCodeSnapshot(),
                        line.skuCodeSnapshot(),
                        line.productNameSnapshot(),
                        line.unitCode(),
                        line.shippedQuantity(),
                        line.remark()))
                .toList();
        ExternalStockOutProjectionCommand command = new ExternalStockOutProjectionCommand(
                SOURCE_SYSTEM_DINGHUOBAO,
                sourceShipmentNo,
                "SALES",
                shipment.warehouseId(),
                salesOrder.id(),
                salesOrder.orderNo(),
                null,
                null,
                salesOrder.customerId(),
                salesOrder.customerNameSnapshot(),
                shipment.shipTime(),
                lines,
                firstNonBlank(shipment.remark(), "订货宝销售出库 " + sourceShipmentNo));
        return new PreparedStockOut(sourceShipmentNo, "SALES", command);
    }

    private PreparedTransferStockOut prepareTransferStockOut(
            UUID tenantId, UUID connectorId, String sourceShipmentNo, Shipment summary,
            ShipmentDetail detail, Map<String, Object> payload) {
        Map<String, Object> list = map(payload == null ? null : mapObject(payload.get("list")));
        Map<String, Object> content = map(payload == null ? null : mapObject(payload.get("detail")));
        Long sourceWarehouseId = warehouseId(tenantId, connectorId, sourceShipmentNo, summary, content, list);
        if (sourceWarehouseId == null) {
            throw new ProjectionRejected("DHB_TRANSFER_SOURCE_WAREHOUSE_MAPPING_MISSING",
                    "订货宝调拨出库缺少来源仓库映射，不能反推我方调拨单",
                    "MAPPING", Map.of("required", "source warehouse mapping"),
                    Map.of("sourceShipmentNo", sourceShipmentNo));
        }
        Long targetWarehouseId = targetWarehouseId(tenantId, connectorId, sourceShipmentNo, content, list);
        if (targetWarehouseId == null) {
            throw new ProjectionRejected("DHB_TRANSFER_TARGET_WAREHOUSE_MAPPING_MISSING",
                    "订货宝调拨出库缺少目标仓库映射，不能反推我方调拨单",
                    "MAPPING", Map.of("required", "target warehouse mapping"),
                    Map.of("sourceShipmentNo", sourceShipmentNo));
        }
        List<ExternalTransferStockOutProjectionLineCommand> lines =
                inventoryStockOutLines(tenantId, connectorId, sourceShipmentNo, content).stream()
                        .map(line -> new ExternalTransferStockOutProjectionLineCommand(
                                line.productId(), line.productVariantId(), line.productCodeSnapshot(),
                                line.skuCodeSnapshot(), line.productNameSnapshot(), line.unitCode(),
                                line.quantity(), line.remark()))
                        .toList();
        ExternalTransferStockOutProjectionCommand command = new ExternalTransferStockOutProjectionCommand(
                SOURCE_SYSTEM_DINGHUOBAO, sourceShipmentNo, sourceWarehouseId, targetWarehouseId,
                shipmentStockOutTime(summary, detail, content, list), lines,
                firstNonBlank(first(content, list, "Remark", "remark"),
                        "订货宝调拨出库 " + sourceShipmentNo));
        return new PreparedTransferStockOut(sourceShipmentNo, command);
    }

    private PreparedGenericStockOut prepareGenericStockOut(
            UUID tenantId, UUID connectorId, String sourceShipmentNo, String stockOutTypeCode,
            Shipment summary, ShipmentDetail detail, Map<String, Object> payload) {
        Map<String, Object> list = map(payload == null ? null : mapObject(payload.get("list")));
        Map<String, Object> content = map(payload == null ? null : mapObject(payload.get("detail")));
        Long warehouseId = warehouseId(tenantId, connectorId, sourceShipmentNo, summary, content, list);
        if (warehouseId == null) {
            throw new ProjectionRejected("DHB_STOCK_OUT_WAREHOUSE_MAPPING_MISSING",
                    "订货宝出库单缺少仓库映射，不能生成ERP出库单",
                    "MAPPING", Map.of("required", "warehouse mapping"),
                    Map.of("sourceShipmentNo", sourceShipmentNo, "stockOutTypeCode", stockOutTypeCode));
        }
        List<ExternalGenericStockOutProjectionLineCommand> lines =
                inventoryStockOutLines(tenantId, connectorId, sourceShipmentNo, content).stream()
                        .map(line -> new ExternalGenericStockOutProjectionLineCommand(
                                line.productId(), line.productVariantId(), line.productCodeSnapshot(),
                                line.skuCodeSnapshot(), line.productNameSnapshot(), line.unitCode(),
                                line.quantity(), line.remark()))
                        .toList();
        ExternalGenericStockOutProjectionCommand command = new ExternalGenericStockOutProjectionCommand(
                SOURCE_SYSTEM_DINGHUOBAO, sourceShipmentNo, stockOutTypeCode, warehouseId,
                shipmentStockOutTime(summary, detail, content, list), lines,
                firstNonBlank(first(content, list, "Remark", "remark"),
                        "订货宝出库 " + sourceShipmentNo));
        return new PreparedGenericStockOut(sourceShipmentNo, stockOutTypeCode, command);
    }

    private ProjectedStockOut projectExternalStockOut(
            CallerIdentity caller, SyncTaskContext task, UUID runId, RawObjectPersistResult raw,
            ExternalObjectMapping existing, PreparedStockOut prepared) {
        if (existing != null && existing.internalObjectId() != null && existing.internalObjectNo() != null) {
            return new ProjectedStockOut(existing.internalObjectId(), existing.internalObjectNo(),
                    prepared.stockOutTypeCode(), true);
        }
        if (erpStockOutProjectionClient == null) {
            throw new ProjectionRejected("DHB_STOCK_OUT_ERP_CLIENT_MISSING",
                    "订货宝出库单需要投影到ERP出库单，但Integration未配置ERP出库客户端",
                    "CONFIG", Map.of("required", "ErpStockOutProjectionClient"),
                    Map.of("sourceShipmentNo", prepared.sourceShipmentNo()));
        }
        InternalStockOutOrderDetailView projected = erpStockOutProjectionClient.confirmExternalStockOut(
                orderServiceCaller(caller.tenantId()), prepared.command());
        store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_ERP_STOCK_OUT,
                        prepared.sourceShipmentNo(), prepared.sourceShipmentNo(), "ERP", "STOCK_OUT_ORDER",
                        projected.id(), projected.stockOutNo(), "ACTIVE", runId, Instant.now(),
                        raw.payloadChecksum(), null, "订货宝出库单已投影到ERP统一出库单"));
        return new ProjectedStockOut(projected.id(), projected.stockOutNo(), projected.stockOutTypeCode(), false);
    }

    private ProjectedTransferStockOut projectExternalTransferStockOut(
            CallerIdentity caller, SyncTaskContext task, UUID runId, RawObjectPersistResult raw,
            ExternalObjectMapping existingStockOut, PreparedTransferStockOut prepared) {
        if (existingStockOut != null
                && existingStockOut.internalObjectId() != null
                && existingStockOut.internalObjectNo() != null) {
            ExternalObjectMapping existingTransfer = store.findActiveMapping(
                    caller.tenantId(), task.connectorId(), SOURCE_OBJECT_ERP_TRANSFER_ORDER,
                    prepared.sourceShipmentNo());
            return new ProjectedTransferStockOut(
                    existingTransfer == null ? null : existingTransfer.internalObjectId(),
                    existingTransfer == null ? null : existingTransfer.internalObjectNo(),
                    existingStockOut.internalObjectId(),
                    existingStockOut.internalObjectNo(),
                    true);
        }
        if (erpStockOutProjectionClient == null) {
            throw new ProjectionRejected("DHB_STOCK_OUT_ERP_CLIENT_MISSING",
                    "订货宝调拨出库需要投影到ERP，但Integration未配置ERP出库客户端",
                    "CONFIG", Map.of("required", "ErpStockOutProjectionClient"),
                    Map.of("sourceShipmentNo", prepared.sourceShipmentNo()));
        }
        InternalTransferOrderDetailView projected =
                erpStockOutProjectionClient.confirmExternalTransferStockOut(
                        orderServiceCaller(caller.tenantId()), prepared.command());
        store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_ERP_TRANSFER_ORDER,
                        prepared.sourceShipmentNo(), prepared.sourceShipmentNo(), "ERP", "TRANSFER_ORDER",
                        projected.id(), projected.transferNo(), "ACTIVE", runId, Instant.now(),
                        raw.payloadChecksum(), null, "订货宝调拨出库已反推ERP调拨单"));
        store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_ERP_STOCK_OUT,
                        prepared.sourceShipmentNo(), prepared.sourceShipmentNo(), "ERP", "STOCK_OUT_ORDER",
                        projected.stockOutOrderId(), projected.stockOutNo(), "ACTIVE", runId, Instant.now(),
                        raw.payloadChecksum(), null, "订货宝调拨出库已投影到ERP统一出库单"));
        return new ProjectedTransferStockOut(projected.id(), projected.transferNo(),
                projected.stockOutOrderId(), projected.stockOutNo(), false);
    }

    private ProjectedStockOut projectExternalGenericStockOut(
            CallerIdentity caller, SyncTaskContext task, UUID runId, RawObjectPersistResult raw,
            ExternalObjectMapping existing, PreparedGenericStockOut prepared) {
        if (existing != null && existing.internalObjectId() != null && existing.internalObjectNo() != null) {
            return new ProjectedStockOut(existing.internalObjectId(), existing.internalObjectNo(),
                    prepared.stockOutTypeCode(), true);
        }
        if (erpStockOutProjectionClient == null) {
            throw new ProjectionRejected("DHB_STOCK_OUT_ERP_CLIENT_MISSING",
                    "订货宝出库单需要投影到ERP出库单，但Integration未配置ERP出库客户端",
                    "CONFIG", Map.of("required", "ErpStockOutProjectionClient"),
                    Map.of("sourceShipmentNo", prepared.sourceShipmentNo()));
        }
        InternalStockOutOrderDetailView projected =
                erpStockOutProjectionClient.confirmExternalGenericStockOut(
                        orderServiceCaller(caller.tenantId()), prepared.command());
        store.upsertExternalObjectMapping(caller.tenantId(), caller.userId(),
                new ExternalObjectMappingWrite(task.connectorId(), SOURCE_OBJECT_ERP_STOCK_OUT,
                        prepared.sourceShipmentNo(), prepared.sourceShipmentNo(), "ERP", "STOCK_OUT_ORDER",
                        projected.id(), projected.stockOutNo(), "ACTIVE", runId, Instant.now(),
                        raw.payloadChecksum(), null, "订货宝出库单已投影到ERP统一出库单"));
        return new ProjectedStockOut(projected.id(), projected.stockOutNo(), projected.stockOutTypeCode(), false);
    }

    private Long warehouseId(UUID tenantId, UUID connectorId, String sourceShipmentNo,
                             Shipment summary, Map<String, Object> content,
                             Map<String, Object> list) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, summary == null ? null : summary.warehouseGuid());
        addCandidate(candidates, summary == null ? null : summary.warehouseNumber());
        candidates.addAll(candidates(content, list, "StockID", "StockId", "stockId",
                "StockGuid", "stockGuid", "StockNum", "stockNum", "StockNO", "stockNo"));
        if (candidates.isEmpty()) return null;
        try {
            return mappingAny(tenantId, connectorId, List.of("WAREHOUSE"), candidates,
                    sourceShipmentNo, "仓库").internalObjectId();
        } catch (ProjectionRejected ignored) {
            return null;
        }
    }

    private Long targetWarehouseId(UUID tenantId, UUID connectorId, String sourceShipmentNo,
                                   Map<String, Object> content, Map<String, Object> list) {
        List<String> candidates = candidates(content, list,
                "TargetStockID", "TargetStockId", "targetStockID", "targetStockId", "target_stock_id",
                "TargetStockGuid", "TargetStockGUID", "targetStockGuid", "target_stock_guid",
                "TargetStockNum", "targetStockNum", "target_stock_num",
                "ToStockID", "ToStockId", "toStockID", "toStockId", "to_stock_id",
                "ToStockGuid", "ToStockGUID", "toStockGuid", "to_stock_guid",
                "ToStockNum", "toStockNum", "to_stock_num",
                "InStockID", "InStockId", "inStockID", "inStockId", "in_stock_id",
                "InStockGuid", "InStockGUID", "inStockGuid", "in_stock_guid",
                "InStockNum", "inStockNum", "in_stock_num",
                "ReceiveStockID", "ReceiveStockId", "receiveStockID", "receiveStockId",
                "ReceiveStockGuid", "ReceiveStockGUID", "receiveStockGuid",
                "ReceiveStockNum", "receiveStockNum");
        if (candidates.isEmpty()) return null;
        try {
            return mappingAny(tenantId, connectorId, List.of("WAREHOUSE"), candidates,
                    sourceShipmentNo, "目标仓库").internalObjectId();
        } catch (ProjectionRejected ignored) {
            return null;
        }
    }

    private List<InventoryStockOutLine> inventoryStockOutLines(
            UUID tenantId, UUID connectorId, String sourceShipmentNo, Map<String, Object> content) {
        List<Map<String, Object>> rows = rows(content, "body", "Body", "Products",
                "OrderProduct", "OrderProducts", "Goods", "list", "details");
        if (rows.isEmpty()) {
            throw new ProjectionRejected("DHB_STOCK_OUT_LINE_MISSING",
                    "订货宝出库单详情缺少商品明细，不能生成ERP出库单",
                    "DETAIL", Map.of("required", "stock out lines"),
                    Map.of("sourceShipmentNo", sourceShipmentNo));
        }
        Map<String, InventoryStockOutLineAccumulator> merged = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            List<String> productSourceIds = candidates(row,
                    "Guid", "TrueGuid", "GoodsGuid", "GoodsGUID", "goods_guid",
                    "goods_id", "GoodsId", "GoodsID", "goodsId",
                    "ProductGuid", "ProductGUID", "productGuid",
                    "ProductID", "ProductId", "productId", "product_id");
            List<String> productCodes = candidates(row,
                    "Coding", "GoodsCoding", "ProductCode", "productCode",
                    "goods_num", "GoodsNo", "GoodsNO", "goodsNo", "goods_no",
                    "ProductNo", "productNo", "product_no", "barcode", "BarCode");
            String productSourceId = productSourceIds.isEmpty() ? null : productSourceIds.getFirst();
            String productCode = productCodes.isEmpty() ? null : productCodes.getFirst();
            List<String> productCandidates = new ArrayList<>();
            productCandidates.addAll(productSourceIds);
            productCandidates.addAll(productCodes);
            ExternalObjectMapping product = mappingAny(tenantId, connectorId,
                    List.of("PRODUCT_SPU", "PRODUCT"), productCandidates,
                    sourceShipmentNo, "商品");
            List<String> skuSources = candidates(row,
                    "OptionsGoodsNo", "options_goods_no", "OptionsGoodsNum", "options_goods_num",
                    "OptionsId", "OptionsID", "optionsId", "optionsID", "options_id",
                    "OptionsGuid", "OptionsGUID", "optionsGuid", "options_guid",
                    "GoodsOptionsId", "GoodsOptionsID", "goodsOptionsId",
                    "SkuId", "SkuID", "skuId", "sku_id", "skuNo", "SkuNo", "sku_no",
                    "SkuCode", "skuCode", "BarCode", "barcode");
            if (skuSources.isEmpty() && (!productSourceIds.isEmpty() || !blank(product.sourceObjectId()))) {
                skuSources = List.of("0");
            }
            List<String> skuCandidates = new ArrayList<>();
            for (String skuSource : skuSources) {
                addNormalizedSkuCandidate(skuCandidates, productSourceId, skuSource);
                addNormalizedSkuCandidate(skuCandidates, product.sourceObjectId(), skuSource);
                addNormalizedSkuCandidate(skuCandidates, product.sourceObjectNo(), skuSource);
                addNormalizedSkuCandidate(skuCandidates, productCode, skuSource);
                addCandidate(skuCandidates, skuSource);
            }
            skuCandidates.addAll(productCodes);
            ExternalObjectMapping sku = mappingAny(tenantId, connectorId,
                    List.of("PRODUCT_SKU", "PRODUCT_VARIANT", "SKU"), skuCandidates,
                    sourceShipmentNo, "商品规格");
            BigDecimal quantity = decimal(firstObject(row, "ShipsNumber", "ships_number",
                    "OutNumber", "outNumber", "ContentNumber", "Number", "Quantity",
                    "quantity", "GoodsNumber"));
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ProjectionRejected("DHB_STOCK_OUT_LINE_QUANTITY_INVALID",
                        "订货宝出库单明细数量为空或小于等于0",
                        "DETAIL", Map.of("required", "quantity > 0"),
                        Map.of("sourceShipmentNo", sourceShipmentNo, "product", productSourceId));
            }
            Long productId = requiredInternalId(product, sourceShipmentNo, "商品");
            Long variantId = requiredInternalId(sku, sourceShipmentNo, "商品规格");
            String sourceUnit = first(row, "order_units_name", "base_units_name", "Units",
                    "UnitsName", "Unit", "unit_name", "UnitName", "unitName");
            InventoryStockOutLine line = new InventoryStockOutLine(
                    productId,
                    variantId,
                    firstNonBlank(product.internalObjectNo(), productCode),
                    firstNonBlank(sku.internalObjectNo(), skuSources.isEmpty() ? null : skuSources.getFirst()),
                    firstNonBlank(first(row, "Name", "GoodsName", "goodsName", "goods_name",
                                    "ProductName", "productName", "product_name"),
                            product.sourceObjectNo(), product.internalObjectNo(), "订货宝商品"),
                    optionalUnitCode(sourceUnit),
                    quantity,
                    first(row, "remark", "Remark"));
            merged.compute(productId + "::" + variantId, (ignored, current) ->
                    current == null ? InventoryStockOutLineAccumulator.from(line) : current.merge(line));
        }
        return merged.values().stream()
                .map(InventoryStockOutLineAccumulator::toLine)
                .toList();
    }

    private List<SalesShipmentLineCommand> shipmentLines(UUID tenantId, UUID connectorId,
                                                        String sourceShipmentNo,
                                                        Map<String, Object> content,
                                                        SalesOrderDetailView salesOrder) {
        List<Map<String, Object>> rows = rows(content, "body", "Body", "Products",
                "OrderProduct", "OrderProducts", "Goods", "list", "details");
        if (rows.isEmpty()) {
            throw new ProjectionRejected("DHB_SHIPMENT_LINE_MISSING",
                    "订货宝发货单详情缺少商品明细，不能生成销售发货单",
                    "DETAIL", Map.of("required", "shipment lines"),
                    Map.of("sourceShipmentNo", sourceShipmentNo));
        }
        Map<String, SalesShipmentLineAccumulator> merged = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            List<String> productSourceIds = candidates(row,
                    "Guid", "TrueGuid", "GoodsGuid", "GoodsGUID", "goods_guid",
                    "goods_id", "GoodsId", "GoodsID", "goodsId",
                    "ProductGuid", "ProductGUID", "productGuid",
                    "ProductID", "ProductId", "productId", "product_id");
            List<String> productCodes = candidates(row,
                    "Coding", "GoodsCoding", "ProductCode", "productCode",
                    "goods_num", "GoodsNo", "GoodsNO", "goodsNo", "goods_no",
                    "ProductNo", "productNo", "product_no", "barcode", "BarCode");
            String productSourceId = productSourceIds.isEmpty() ? null : productSourceIds.getFirst();
            String productCode = productCodes.isEmpty() ? null : productCodes.getFirst();
            List<String> productCandidates = new ArrayList<>();
            productCandidates.addAll(productSourceIds);
            productCandidates.addAll(productCodes);
            ExternalObjectMapping product = mappingAny(tenantId, connectorId,
                    List.of("PRODUCT_SPU", "PRODUCT"), productCandidates,
                    sourceShipmentNo, "商品");
            List<String> skuSources = candidates(row,
                    "OptionsGoodsNo", "options_goods_no", "OptionsGoodsNum", "options_goods_num",
                    "OptionsId", "OptionsID", "optionsId", "optionsID", "options_id",
                    "OptionsGuid", "OptionsGUID", "optionsGuid", "options_guid",
                    "GoodsOptionsId", "GoodsOptionsID", "goodsOptionsId",
                    "SkuId", "SkuID", "skuId", "sku_id", "skuNo", "SkuNo", "sku_no",
                    "SkuCode", "skuCode", "BarCode", "barcode");
            if (skuSources.isEmpty() && (!productSourceIds.isEmpty() || !blank(product.sourceObjectId()))) {
                skuSources = List.of("0");
            }
            List<String> skuCandidates = new ArrayList<>();
            for (String skuSource : skuSources) {
                addNormalizedSkuCandidate(skuCandidates, productSourceId, skuSource);
                addNormalizedSkuCandidate(skuCandidates, product.sourceObjectId(), skuSource);
                addNormalizedSkuCandidate(skuCandidates, product.sourceObjectNo(), skuSource);
                addNormalizedSkuCandidate(skuCandidates, productCode, skuSource);
                addCandidate(skuCandidates, skuSource);
            }
            skuCandidates.addAll(productCodes);
            ExternalObjectMapping sku = mappingAny(tenantId, connectorId,
                    List.of("PRODUCT_SKU", "PRODUCT_VARIANT", "SKU"), skuCandidates,
                    sourceShipmentNo, "商品规格");
            BigDecimal quantity = decimal(firstObject(row, "ShipsNumber", "ships_number",
                    "OutNumber", "outNumber", "ContentNumber", "Number", "Quantity",
                    "quantity", "GoodsNumber"));
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ProjectionRejected("DHB_SHIPMENT_LINE_QUANTITY_INVALID",
                        "订货宝发货单明细数量为空或小于等于0",
                        "DETAIL", Map.of("required", "quantity > 0"),
                        Map.of("sourceShipmentNo", sourceShipmentNo, "product", productSourceId));
            }
            Long productId = requiredInternalId(product, sourceShipmentNo, "商品");
            Long variantId = requiredInternalId(sku, sourceShipmentNo, "商品规格");
            String sourceProductName = first(row, "Name", "GoodsName", "goodsName", "goods_name",
                    "ProductName", "productName", "product_name");
            String sourceSpecification = sourceSpecificationName(row);
            SalesOrderLineView orderLine = salesOrderLine(salesOrder, productId, variantId);
            if (orderLine == null) {
                orderLine = salesOrderLineByProductNameAndSpecification(salesOrder,
                        sourceProductName, sourceSpecification);
            }
            if (orderLine == null) {
                orderLine = salesOrderLineByProductName(salesOrder, sourceProductName);
            }
            Long resolvedProductId = orderLine == null ? productId : orderLine.productId();
            Long resolvedVariantId = orderLine == null ? variantId : orderLine.productVariantId();
            String sourceUnit = first(row, "order_units_name", "base_units_name", "Units",
                    "UnitsName", "Unit", "unit_name", "UnitName", "unitName");
            String lineUnitCode = sourceUnit == null
                    ? orderLine == null ? null : orderLine.unitCode()
                    : unitCode(sourceUnit);
            if (blank(lineUnitCode)) {
                throw new ProjectionRejected("DHB_SHIPMENT_LINE_UNIT_MISSING",
                        "订货宝发货单明细缺少单位，且无法从对应销售订单明细回填",
                        "DETAIL", Map.of("required", "unitCode"),
                        Map.of("sourceShipmentNo", sourceShipmentNo, "productId", productId,
                                "productVariantId", variantId));
            }
            SalesShipmentLineCommand line = new SalesShipmentLineCommand(
                    orderLine == null ? null : orderLine.id(),
                    resolvedProductId,
                    resolvedVariantId,
                    firstNonBlank(orderLine == null ? null : orderLine.productCodeSnapshot(),
                            product.internalObjectNo(), productCode),
                    firstNonBlank(orderLine == null ? null : orderLine.skuCodeSnapshot(),
                            sku.internalObjectNo(), skuSources.isEmpty() ? null : skuSources.getFirst()),
                    firstNonBlank(sourceProductName, product.sourceObjectNo(), product.internalObjectNo(),
                            orderLine == null ? null : orderLine.productNameSnapshot(), "订货宝商品"),
                    firstNonBlank(sourceSpecification,
                            orderLine == null ? null : orderLine.specificationSnapshot()),
                    lineUnitCode,
                    quantity,
                    first(row, "remark", "Remark"));
            merged.compute(resolvedProductId + "::" + resolvedVariantId, (ignored, current) ->
                    current == null ? SalesShipmentLineAccumulator.from(line) : current.merge(line));
        }
        return merged.values().stream().map(SalesShipmentLineAccumulator::toCommand).toList();
    }

    private static SalesOrderLineView salesOrderLine(SalesOrderDetailView order, Long productId, Long variantId) {
        if (order == null || order.lines() == null || productId == null || variantId == null) {
            return null;
        }
        for (SalesOrderLineView line : order.lines()) {
            if (line != null
                    && Objects.equals(line.productId(), productId)
                    && Objects.equals(line.productVariantId(), variantId)) {
                return line;
            }
        }
        return null;
    }

    private static SalesOrderLineView salesOrderLineByProductName(SalesOrderDetailView order,
                                                                  String productName) {
        if (order == null || order.lines() == null || blank(productName)) {
            return null;
        }
        String normalized = normalizeLineName(productName);
        SalesOrderLineView matched = null;
        for (SalesOrderLineView line : order.lines()) {
            if (line == null || blank(line.productNameSnapshot())) continue;
            if (!normalizeLineName(line.productNameSnapshot()).equals(normalized)) continue;
            if (matched != null) return null;
            matched = line;
        }
        return matched;
    }

    private static SalesOrderLineView salesOrderLineByProductNameAndSpecification(
            SalesOrderDetailView order, String productName, String specification) {
        if (order == null || order.lines() == null || blank(productName) || blank(specification)) {
            return null;
        }
        String normalizedProductName = normalizeLineName(productName);
        String normalizedSpecification = normalizeLineName(specification);
        SalesOrderLineView matched = null;
        for (SalesOrderLineView line : order.lines()) {
            if (line == null || blank(line.productNameSnapshot()) || blank(line.specificationSnapshot())) {
                continue;
            }
            if (!normalizeLineName(line.productNameSnapshot()).equals(normalizedProductName)) continue;
            if (!normalizeLineName(line.specificationSnapshot()).equals(normalizedSpecification)) continue;
            if (matched != null) return null;
            matched = line;
        }
        return matched;
    }

    private static String sourceSpecificationName(Map<String, Object> row) {
        String direct = first(row, "multiName", "MultiName", "SpecName", "SpecificationName",
                "specificationName", "goods_options", "GoodsOptions", "OptionsName", "optionsName",
                "options_name");
        if (!blank(direct)) return direct;
        for (Map<String, Object> option : rows(row, "options_info", "OptionsInfo", "optionsInfo",
                "options", "Options")) {
            String value = first(option, "options_name", "OptionsName", "optionName",
                    "name", "Name");
            if (!blank(value)) return value;
        }
        return null;
    }

    private static String normalizeLineName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private StaffProjection ownerStaff(UUID tenantId, UUID connectorId, List<String> sourceStaffIds,
                                       List<String> sourceStaffNames,
                                       Map<String, StaffProjection> staffCache) {
        List<String> cleanedSourceStaffIds = cleanDistinct(sourceStaffIds);
        List<String> cleanedSourceStaffNames = cleanDistinct(sourceStaffNames);
        String cleanedFallbackName = cleanedSourceStaffNames.isEmpty() ? null : cleanedSourceStaffNames.getFirst();
        if (cleanedSourceStaffIds.isEmpty() && cleanedSourceStaffNames.isEmpty()) {
            return new StaffProjection(null, null);
        }
        String cacheKey = connectorId + "::ids=" + String.join("|", cleanedSourceStaffIds)
                + "::names=" + String.join("|", cleanedSourceStaffNames);
        StaffProjection cached = staffCache.get(cacheKey);
        if (cached != null) return cached;
        StaffProjection result = new StaffProjection(null, cleanedFallbackName);
        try {
            List<ResolvedStaff> resolved = iamStaffClient.resolve(orderServiceCaller(tenantId),
                    connectorId.toString(), cleanedSourceStaffIds, cleanedSourceStaffNames);
            if (resolved != null && !resolved.isEmpty()) {
                ResolvedStaff staff = resolved.getFirst();
                result = new StaffProjection(firstNonBlank(staff.staffCode(), null),
                        firstNonBlank(staff.staffName(), cleanedFallbackName));
            }
        } catch (RuntimeException error) {
            log.warn("订货宝订单员工解析失败 tenantId={} connectorId={} sourceStaffIds={} sourceStaffNames={} reason={}",
                    tenantId, connectorId, cleanedSourceStaffIds, cleanedSourceStaffNames, safeMessage(error));
        }
        staffCache.put(cacheKey, result);
        return result;
    }

    private List<SalesOrderLineCommand> salesOrderLines(UUID tenantId, UUID connectorId,
                                                        String sourceOrderNo,
                                                        Map<String, Object> content) {
        List<Map<String, Object>> rows = rows(content,
                "OrderProduct", "OrderProducts", "OrderGoods", "Goods", "Products", "list", "body");
        if (rows.isEmpty()) {
            throw new ProjectionRejected("DHB_ORDER_LINE_MISSING",
                    "订货宝订单详情缺少商品明细，不能生成销售订单",
                    "DETAIL", Map.of("required", "order lines"),
                    Map.of("sourceOrderNo", sourceOrderNo));
        }
        Map<String, SalesOrderLineAccumulator> merged = new LinkedHashMap<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Map<String, Object> row = rows.get(rowIndex);
            List<String> productSourceIds = candidates(row,
                    "Guid", "TrueGuid", "GoodsGuid", "GoodsGUID", "goods_guid",
                    "goods_id", "GoodsId", "GoodsID", "goodsId",
                    "ProductGuid", "ProductGUID", "productGuid",
                    "ProductID", "ProductId", "productId", "product_id");
            List<String> productCodes = candidates(row,
                    "Coding", "GoodsCoding", "ProductCode", "productCode",
                    "goods_num", "GoodsNo", "GoodsNO", "goodsNo", "goods_no",
                    "ProductNo", "productNo", "product_no", "barcode", "BarCode");
            String productSourceId = productSourceIds.isEmpty() ? null : productSourceIds.getFirst();
            String productCode = productCodes.isEmpty() ? null : productCodes.getFirst();
            List<String> productCandidates = new ArrayList<>();
            productCandidates.addAll(productSourceIds);
            productCandidates.addAll(productCodes);
            ExternalObjectMapping product = mappingAny(tenantId, connectorId,
                    List.of("PRODUCT_SPU", "PRODUCT"), productCandidates,
                    sourceOrderNo, "商品");
            List<String> skuSources = candidates(row,
                    "OptionsGoodsNo", "options_goods_no", "OptionsGoodsNum", "options_goods_num",
                    "OptionsId", "OptionsID", "optionsId", "optionsID", "options_id",
                    "OptionsGuid", "OptionsGUID", "optionsGuid", "options_guid",
                    "GoodsOptionsId", "GoodsOptionsID", "goodsOptionsId",
                    "SkuId", "SkuID", "skuId", "sku_id", "skuNo", "SkuNo", "sku_no",
                    "SkuCode", "skuCode", "BarCode", "barcode");
            if (skuSources.isEmpty() && (!productSourceIds.isEmpty() || !blank(product.sourceObjectId()))) {
                skuSources = List.of("0");
            }
            List<String> skuCandidates = new ArrayList<>();
            for (String skuSource : skuSources) {
                addNormalizedSkuCandidate(skuCandidates, productSourceId, skuSource);
                addNormalizedSkuCandidate(skuCandidates, product.sourceObjectId(), skuSource);
                addNormalizedSkuCandidate(skuCandidates, product.sourceObjectNo(), skuSource);
                addNormalizedSkuCandidate(skuCandidates, productCode, skuSource);
                addCandidate(skuCandidates, skuSource);
            }
            skuCandidates.addAll(productCodes);
            ExternalObjectMapping sku = mappingAny(tenantId, connectorId,
                    List.of("PRODUCT_SKU", "PRODUCT_VARIANT", "SKU"), skuCandidates,
                    sourceOrderNo, "商品规格");
            Long productId = requiredInternalId(product, sourceOrderNo, "商品");
            Long variantId = requiredInternalId(sku, sourceOrderNo, "商品规格");
            String duplicateKey = salesOrderLineKey(row, productId, variantId, rowIndex);
            BigDecimal quantity = decimal(firstObject(row, "ContentNumber", "order_units_number",
                    "Number", "OrderNumber", "Quantity", "quantity", "orders_number", "GoodsNumber"));
            if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ProjectionRejected("DHB_ORDER_LINE_QUANTITY_INVALID",
                        "订货宝订单明细数量为空或小于等于0",
                        "DETAIL", Map.of("required", "quantity > 0"),
                        Map.of("sourceOrderNo", sourceOrderNo, "product", productSourceId));
            }
            BigDecimal lineAmount = decimal(firstObject(row,
                    "ContentMoney", "content_money", "LineAmount", "lineAmount", "line_amount",
                    "OrderAmount", "order_amount", "Amount", "amount", "TotalPrice", "totalPrice",
                    "TotalAmount", "totalAmount", "GoodsAmount", "goodsAmount"));
            BigDecimal unitPrice = decimal(firstObject(row, "ContentPrice", "order_units_price",
                    "Price", "OrderPrice", "UnitPrice", "unitPrice", "unit_price",
                    "SalePrice", "salePrice"));
            if (unitPrice == null && lineAmount != null) {
                unitPrice = lineAmount.divide(quantity, 6, RoundingMode.HALF_UP);
            }
            BigDecimal lineDiscountAmount = decimal(firstObject(row,
                    "DiscountMoney", "discountMoney", "discount_money",
                    "DiscountAmount", "discountAmount", "discount_amount"));
            if (lineDiscountAmount == null && unitPrice != null && lineAmount != null) {
                BigDecimal originalLineAmount = quantity.multiply(unitPrice);
                if (originalLineAmount.compareTo(lineAmount) > 0) {
                    lineDiscountAmount = originalLineAmount.subtract(lineAmount);
                }
            }
            SalesOrderLineCommand line = new SalesOrderLineCommand(
                    productId,
                    variantId,
                    firstNonBlank(product.internalObjectNo(), productCode),
                    firstNonBlank(sku.internalObjectNo(), skuSources.isEmpty() ? null : skuSources.getFirst()),
                    firstNonBlank(first(row, "Name", "GoodsName", "goodsName", "goods_name",
                                    "ProductName", "productName", "product_name"),
                            product.sourceObjectNo(), product.internalObjectNo(), "订货宝商品"),
                    first(row, "multiName", "MultiName", "SpecName", "SpecificationName",
                            "specificationName", "goods_options", "GoodsOptions", "OptionsName",
                            "optionsName"),
                    unitCode(first(row, "order_units_name", "base_units_name", "Units",
                            "UnitsName", "Unit", "unit_name", "UnitName", "unitName")),
                    quantity,
                    zeroIfNull(unitPrice),
                    null,
                    zeroIfNull(lineDiscountAmount),
                    first(row, "remark", "Remark"));
            merged.compute(duplicateKey, (ignored, current) ->
                    current == null ? SalesOrderLineAccumulator.from(line) : current.merge(line));
        }
        return merged.values().stream()
                .map(SalesOrderLineAccumulator::toCommand)
                .toList();
    }

    private String salesOrderLineKey(Map<String, Object> row, Long productId, Long variantId,
                                     int rowIndex) {
        String sourceLineId = first(row,
                "orders_list_id", "OrdersListId", "OrdersListID", "orderListId",
                "order_list_id", "OrderProductId", "OrderProductID",
                "OrderGoodsId", "OrderGoodsID", "ContentId", "ContentID",
                "id", "Id", "ID");
        if (!blank(sourceLineId)) {
            return productId + "::" + variantId + "::" + sourceLineId.strip();
        }
        return productId + "::" + variantId + "::row-" + rowIndex;
    }

    private ExternalObjectMapping mapping(UUID tenantId, UUID connectorId, String objectType,
                                          String sourceId, String sourceOrderNo, String label) {
        return mappingAny(tenantId, connectorId, List.of(objectType), sourceId, null,
                sourceOrderNo, label);
    }

    private ExternalObjectMapping mappingAny(UUID tenantId, UUID connectorId, List<String> objectTypes,
                                             String preferredSourceId, String fallbackSourceId,
                                             String sourceOrderNo, String label) {
        List<String> candidates = new ArrayList<>();
        if (!blank(preferredSourceId)) candidates.add(preferredSourceId.strip());
        if (!blank(fallbackSourceId)) candidates.add(fallbackSourceId.strip());
        return mappingAny(tenantId, connectorId, objectTypes, candidates, sourceOrderNo, label);
    }

    private ExternalObjectMapping mappingAny(UUID tenantId, UUID connectorId, List<String> objectTypes,
                                             List<String> sourceIds, String sourceOrderNo,
                                             String label) {
        ExternalObjectMapping mapping = optionalMappingAny(tenantId, connectorId, objectTypes, sourceIds);
        if (mapping != null) return mapping;
        throw new ProjectionRejected("DHB_ORDER_MAPPING_MISSING",
                "订货宝订单缺少" + label + "外部映射，需先完成对应主数据同步",
                "MAPPING", Map.of("required", label + " mapping"),
                Map.of("sourceOrderNo", sourceOrderNo,
                        "sourceIds", sourceIds == null ? List.of() : sourceIds));
    }

    private ExternalObjectMapping optionalMappingAny(UUID tenantId, UUID connectorId,
                                                     List<String> objectTypes,
                                                     List<String> sourceIds) {
        for (String sourceId : sourceIds == null ? List.<String>of() : sourceIds) {
            if (blank(sourceId)) continue;
            for (String objectType : objectTypes) {
                ExternalObjectMapping mapping = cachedActiveMapping(
                        tenantId, connectorId, objectType, sourceId);
                if (mapping != null) return mapping;
            }
        }
        return null;
    }

    private ExternalObjectMapping cachedActiveMapping(UUID tenantId, UUID connectorId,
                                                      String objectType, String sourceId) {
        ConcurrentMap<MappingLookupKey, Optional<ExternalObjectMapping>> cache = MAPPING_LOOKUP_CACHE.get();
        if (cache == null) {
            return store.findActiveMapping(tenantId, connectorId, objectType, sourceId);
        }
        MappingLookupKey key = new MappingLookupKey(tenantId, connectorId, objectType, sourceId);
        return cache.computeIfAbsent(key, ignored -> Optional.ofNullable(
                store.findActiveMapping(tenantId, connectorId, objectType, sourceId))).orElse(null);
    }

    private void recordRejected(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                RawObjectPersistResult raw, String sourceOrderNo, String code,
                                String message, String checkType, Map<String, Object> expected,
                                Map<String, Object> actual) {
        recordRejected(caller, task, runId, raw, SOURCE_OBJECT_SALES_ORDER, sourceOrderNo,
                code, message, checkType, expected, actual);
    }

    private void recordPaymentRejected(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                       RawObjectPersistResult raw, String sourceReceiptNo,
                                       String code, String message, String checkType,
                                       Map<String, Object> expected,
                                       Map<String, Object> actual) {
        recordRejected(caller, task, runId, raw, SOURCE_OBJECT_SALES_PAYMENT, sourceReceiptNo,
                code, message, checkType, expected, actual);
    }

    private void recordFundPaymentRejected(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                           RawObjectPersistResult raw, String sourcePaymentNo,
                                           String code, String message, String checkType,
                                           Map<String, Object> expected,
                                           Map<String, Object> actual) {
        recordRejected(caller, task, runId, raw, SOURCE_OBJECT_FUND_PAYMENT, sourcePaymentNo,
                code, message, checkType, expected, actual);
    }

    private void recordShipmentRejected(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                        RawObjectPersistResult raw, String sourceShipmentNo,
                                        String code, String message, String checkType,
                                        Map<String, Object> expected,
                                        Map<String, Object> actual) {
        recordRejected(caller, task, runId, raw, SOURCE_OBJECT_SALES_SHIPMENT, sourceShipmentNo,
                code, message, checkType, expected, actual);
    }

    private void recordRejected(CallerIdentity caller, SyncTaskContext task, UUID runId,
                                RawObjectPersistResult raw, String sourceObjectType,
                                String sourceObjectNo, String code,
                                String message, String checkType, Map<String, Object> expected,
                                Map<String, Object> actual) {
        if (raw != null) {
            store.markRawFailed(caller.tenantId(), raw.rawLandingId(), code, message);
        }
        store.recordDeadLetter(caller.tenantId(), caller.userId(),
                new DeadLetterWrite(runId, raw == null ? null : raw.rawLandingId(),
                        sourceObjectType, sourceObjectNo, code, message));
        store.recordReconciliationCase(caller.tenantId(), caller.userId(),
                new ReconciliationCaseWrite(runId, sourceObjectType, sourceObjectNo,
                        checkType, expected, actual, "ERROR", message));
        store.recordSyncLog(caller.tenantId(), task.taskId(), runId, "WARN",
                "订货宝对象投影失败 sourceObjectType=" + sourceObjectType
                        + " sourceObjectNo=" + sourceObjectNo + " reason=" + message, code);
    }

    private static Map<String, Object> combinedPayload(OrderSummary summary, OrderDetail detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", summary == null ? Map.of() : map(summary.attributes()));
        payload.put("detail", detail == null ? Map.of() : map(detail.attributes()));
        return payload;
    }

    private static Map<String, Object> combinedShipmentPayload(Shipment summary, ShipmentDetail detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("list", summary == null ? Map.of() : map(summary.attributes()));
        payload.put("detail", detail == null ? Map.of() : map(detail.attributes()));
        return payload;
    }

    private static String shipmentSourceOrderNo(Shipment summary, Map<String, Object> payload) {
        Map<String, Object> list = map(payload == null ? null : mapObject(payload.get("list")));
        Map<String, Object> content = map(payload == null ? null : mapObject(payload.get("detail")));
        return firstNonBlank(
                summary == null ? null : summary.orderNumber(),
                first(content, list, "OrdersNum", "orders_num", "OrderSN", "orderSn", "order_no"));
    }

    private static Instant shipmentStockOutTime(Shipment summary, ShipmentDetail detail,
                                                Map<String, Object> content, Map<String, Object> list) {
        return firstNonNull(
                summary == null ? null : summary.shipmentAt(),
                instant(firstObject(content, "ShipsDate", "shipsDate", "ships_date")),
                instant(firstObject(list, "ShipsDate", "shipsDate", "ships_date")),
                summary == null ? null : summary.createdAt(),
                instant(firstObject(content, "CreateDate", "createDate", "create_date")),
                instant(firstObject(list, "CreateDate", "createDate", "create_date")),
                summary == null ? null : summary.updatedAt(),
                detail == null ? null : instant(firstObject(detail.attributes(), "CreateDate")),
                Instant.now());
    }

    private static String stockOutTypeCode(Shipment summary, Map<String, Object> payload) {
        Map<String, Object> list = map(payload == null ? null : mapObject(payload.get("list")));
        Map<String, Object> content = map(payload == null ? null : mapObject(payload.get("detail")));
        String sourceTypeId = firstNonBlank(
                summary == null ? null : summary.typeId(),
                first(content, list, "TypeID", "TypeId", "typeID", "typeId", "type_id"));
        String sourceTypeName = firstNonBlank(
                summary == null ? null : summary.typeName(),
                first(content, list, "TypeName", "typeName", "type_name", "ShipsTypeName"));
        String normalizedTypeId = sourceTypeId == null ? "" : sourceTypeId.strip();
        if ("-2".equals(normalizedTypeId)) return "PURCHASE_RETURN";
        if ("10".equals(normalizedTypeId)) return "SALES";
        if ("11".equals(normalizedTypeId)) return "INVENTORY_LOSS";
        if ("17".equals(normalizedTypeId)) return "OTHER";
        if ("18".equals(normalizedTypeId)) return "TRANSFER";
        if ("19".equals(normalizedTypeId)) return "JOINT_OPERATION";
        String normalizedTypeName = sourceTypeName == null ? "" : sourceTypeName.strip();
        if (normalizedTypeName.contains("采购退货")) return "PURCHASE_RETURN";
        if (normalizedTypeName.contains("销售")) return "SALES";
        if (normalizedTypeName.contains("盘亏")) return "INVENTORY_LOSS";
        if (normalizedTypeName.contains("其他")) return "OTHER";
        if (normalizedTypeName.contains("调拨")) return "TRANSFER";
        if (normalizedTypeName.contains("联营")) return "JOINT_OPERATION";
        throw new ProjectionRejected("DHB_STOCK_OUT_TYPE_UNSUPPORTED",
                "订货宝出库类型暂不支持，不能投影到ERP出库单",
                "DETAIL", Map.of("supported", "type_id=-2采购退货,10销售出库,11盘亏,17其他,18调拨出库,19联营"),
                Map.of("sourceTypeId", safeValue(sourceTypeId),
                        "sourceTypeName", safeValue(sourceTypeName)));
    }

    private static SalesOrderCommand withRevision(SalesOrderCommand source, Integer revision) {
        return new SalesOrderCommand(source.customerId(), source.sourceSystemCode(), source.sourceOrderNo(),
                source.customerCodeSnapshot(),
                source.customerNameSnapshot(), source.contactNameSnapshot(),
                source.contactPhoneSnapshot(), source.regionCode(), source.ownerSalesUserId(),
                source.ownerSalesName(), source.ownerStaffCode(), source.ownerStaffNameSnapshot(),
                source.orderDate(), source.orderTypeCode(),
                source.paymentMethodCode(), source.discountRate(), source.discountAmount(),
                source.remark(), source.lines(), source.submit(), revision);
    }

    private static SalesPaymentRecordCommand withRevision(
            SalesPaymentRecordCommand source, Integer revision) {
        return new SalesPaymentRecordCommand(source.orderId(), source.collectorStaffCode(),
                source.collectorNameSnapshot(), source.paymentTime(), source.paymentMethodCode(),
                source.paidAmount(), source.voucherKeys(), source.remark(), revision);
    }

    private static FundDocumentCommand withRevision(FundDocumentCommand source, Integer revision) {
        return new FundDocumentCommand(source.directionCode(), source.relatedOrderId(),
                source.salesOrderNoSnapshot(), source.customerId(), source.customerCodeSnapshot(),
                source.customerNameSnapshot(), source.counterpartyTypeCode(), source.counterpartyCodeSnapshot(),
                source.counterpartyNameSnapshot(), source.handlerStaffCode(), source.handlerStaffNameSnapshot(),
                source.occurredTime(), source.settlementMethodCode(), source.businessTypeCode(),
                source.documentStatusCode(), source.amount(), source.voucherKeys(), source.remark(), revision);
    }

    private static SalesShipmentCommand withRevision(SalesShipmentCommand source, Integer revision) {
        return new SalesShipmentCommand(source.salesOrderId(), source.warehouseId(),
                source.stockOutOrderId(), source.stockOutNo(), source.shipmentStatusCode(),
                source.logisticsCompany(), source.trackingNo(), source.shipTime(),
                source.lines(), source.remark(), revision);
    }

    private static SalesShipmentCommand withStockOut(
            SalesShipmentCommand source, ProjectedStockOut stockOut) {
        return new SalesShipmentCommand(source.salesOrderId(), source.warehouseId(),
                stockOut.stockOutOrderId(), stockOut.stockOutNo(), source.shipmentStatusCode(),
                source.logisticsCompany(), source.trackingNo(), source.shipTime(),
                source.lines(), source.remark(), source.revision());
    }

    private static CallerIdentity orderServiceCaller(UUID tenantId) {
        return new CallerIdentity("SERVICE", SERVICE_PRINCIPAL_ID, tenantId, null, null,
                UUID.randomUUID(), 0, 0, 0, Set.of("DHB_ORDER_SYNC_SERVICE"), DOMAIN_PERMISSIONS);
    }

    private static Long requiredInternalId(ExternalObjectMapping mapping,
                                           String sourceOrderNo, String label) {
        if (mapping == null || mapping.internalObjectId() == null || mapping.internalObjectId() < 1) {
            throw new ProjectionRejected("DHB_ORDER_MAPPING_TARGET_MISSING",
                    "订货宝订单" + label + "映射缺少内部对象ID",
                    "MAPPING", Map.of("required", label + " internalObjectId"),
                    Map.of("sourceOrderNo", sourceOrderNo));
        }
        return mapping.internalObjectId();
    }

    private static boolean shouldSubmit(String sourceStatus) {
        String value = lower(sourceStatus);
        return Set.of("stockup", "stock_up", "shipped", "received", "finished", "forcedone")
                .contains(value);
    }

    private static boolean isCancelled(String sourceStatus) {
        return "cancelled".equals(lower(sourceStatus)) || "canceled".equals(lower(sourceStatus));
    }

    private static boolean salesOrderDraft(SalesOrderDetailView current) {
        return current != null && "DRAFT".equalsIgnoreCase(current.orderStatusCode());
    }

    private static boolean receiptCancelled(String sourceStatus) {
        String value = lower(sourceStatus);
        return "canceled".equals(value) || "cancelled".equals(value) || "c".equals(value);
    }

    private static boolean paymentCancelled(String sourceStatus) {
        String value = lower(sourceStatus);
        return "canceled".equals(value) || "cancelled".equals(value) || "c".equals(value)
                || "cancel".equals(value) || "closed".equals(value) || "已取消".equals(value);
    }

    private static String shipmentStatusCode(String sourceStatus) {
        String value = lower(sourceStatus);
        if (value == null) return "SHIPPED";
        if (Set.of("signed", "received", "completed", "finished", "done", "confirm").contains(value)
                || value.contains("签收") || value.contains("完成")) {
            return "SIGNED";
        }
        if (Set.of("cancel", "canceled", "cancelled", "closed").contains(value)
                || value.contains("取消")) {
            return "CANCELLED";
        }
        if (Set.of("created", "new", "draft", "pending").contains(value)
                || value.contains("待")) {
            return "CREATED";
        }
        return "SHIPPED";
    }

    private static String fundDocumentStatusCode(String sourceStatus, Map<String, Object> attributes) {
        String source = firstNonBlank(sourceStatus,
                first(attributes, "Status", "status", "PayStatus", "payStatus", "paymentStatus"));
        String value = lower(source);
        if (value == null) return "CONFIRMED";
        if (paymentCancelled(value) || value.contains("取消")) return "CANCELLED";
        if (Set.of("pending", "wait", "waiting", "new", "draft", "created", "pend",
                "pend_receipt").contains(value) || value.contains("待")) {
            return "PENDING";
        }
        return "CONFIRMED";
    }

    private static String receiptFundBusinessTypeCode(String sourceBusinessType,
                                                      Map<String, Object> attributes,
                                                      String sourceOrderNo) {
        String source = firstNonBlank(sourceBusinessType,
                first(attributes, "IncexpId", "incexpId", "businessType", "BusinessType",
                        "IncexpName", "incexpName", "BusinessTypeName", "businessTypeName"));
        String value = lower(source);
        if (value == null) return blank(sourceOrderNo) ? "CUSTOMER_RECHARGE" : "ORDER_RECEIPT";
        if (value.contains("充值") || value.contains("预存") || value.contains("余额")) {
            return "CUSTOMER_RECHARGE";
        }
        if (value.contains("冲正") || value.contains("回冲") || value.contains("红冲")) {
            return "REVERSAL";
        }
        return switch (value) {
            case "13" -> "ORDER_RECEIPT";
            case "1", "8", "19" -> "CUSTOMER_RECHARGE";
            case "9", "10" -> "REVERSAL";
            default -> blank(sourceOrderNo) ? "CUSTOMER_RECHARGE" : "ORDER_RECEIPT";
        };
    }

    private static String paymentFundBusinessTypeCode(String sourceBusinessType, Map<String, Object> attributes) {
        String source = firstNonBlank(sourceBusinessType,
                first(attributes, "IncexpId", "incexpId", "businessType", "BusinessType",
                        "IncexpName", "incexpName", "BusinessTypeName", "businessTypeName"));
        String value = lower(source);
        if (value == null) return "BALANCE_DEDUCTION";
        if (value.contains("退款") || value.contains("refund")) {
            return "CUSTOMER_REFUND";
        }
        if (value.contains("冲正") || value.contains("回冲") || value.contains("红冲")) {
            return "REVERSAL";
        }
        if (value.contains("余额") || value.contains("抵扣") || value.contains("消费")
                || value.contains("预存")) {
            return "BALANCE_DEDUCTION";
        }
        return switch (value) {
            case "9", "10" -> "REVERSAL";
            default -> "BALANCE_DEDUCTION";
        };
    }

    private static String receiptSourceOrderNo(Receipt receipt, Map<String, Object> attributes) {
        return firstNonBlank(
                receipt == null ? null : receipt.orderNumber(),
                first(attributes, "OrdersNum", "ordersNum", "orders_num",
                        "OrderSN", "orderSn", "order_sn",
                        "OrderNo", "orderNo", "order_no",
                        "SourceOrderNo", "sourceOrderNo", "source_order_no"));
    }

    private static String paymentSourceOrderNo(Payment payment, Map<String, Object> attributes) {
        return firstNonBlank(
                payment == null ? null : payment.orderNumber(),
                first(attributes, "OrdersNum", "ordersNum", "orders_num",
                        "OrderSN", "orderSn", "order_sn",
                        "OrderNo", "orderNo", "order_no",
                        "SourceOrderNo", "sourceOrderNo", "source_order_no"));
    }

    private static String paymentMethodCode(Receipt receipt, Map<String, Object> attributes) {
        String source = firstNonBlank(
                receipt == null ? null : receipt.paymentMethod(),
                receipt == null ? null : receipt.businessType(),
                first(attributes, "TypeId", "typeId", "BankPrefix", "bankPrefix", "BankName", "bankName"));
        return paymentMethodCode(source);
    }

    private static String paymentMethodCode(Payment payment, Map<String, Object> attributes) {
        String source = firstNonBlank(
                payment == null ? null : payment.paymentMethod(),
                payment == null ? null : payment.businessType(),
                first(attributes, "TypeId", "typeId", "BankPrefix", "bankPrefix", "BankName", "bankName"));
        return paymentMethodCode(source);
    }

    private static String paymentMethodCode(String source) {
        String value = lower(source);
        if (value == null) return null;
        if (value.contains("alipay") || value.contains("支付宝")) return "ALIPAY";
        if (value.contains("wechat") || value.contains("weixin") || value.contains("micro")
                || value.contains("微信")) return "WECHAT";
        if (value.contains("cash") || value.contains("现金")) return "CASH";
        if (value.contains("deposit") || value.contains("预存")) return "DEPOSIT";
        if (value.contains("credit") || value.contains("赊")) return "CREDIT";
        if (value.contains("bank") || value.contains("offline") || value.contains("transfer")
                || value.contains("netbank") || value.contains("银行") || value.contains("转账")) {
            return "BANK_TRANSFER";
        }
        return "OTHER";
    }

    private static String receiptRemark(Receipt receipt, Map<String, Object> attributes) {
        List<String> parts = new ArrayList<>();
        addRemark(parts, receipt == null ? null : receipt.remark());
        addRemark(parts, receipt == null ? null : receipt.serialNumber());
        addRemark(parts, receipt == null ? null : receipt.bankName());
        addRemark(parts, receipt == null ? null : receipt.accountNumber());
        addRemark(parts, first(attributes, "Remark", "remark"));
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private static String paymentRemark(Payment payment, Map<String, Object> attributes) {
        List<String> parts = new ArrayList<>();
        addRemark(parts, payment == null ? null : payment.remark());
        addRemark(parts, payment == null ? null : payment.receiptNumber());
        addRemark(parts, payment == null ? null : payment.serialNumber());
        addRemark(parts, payment == null ? null : payment.bankName());
        addRemark(parts, payment == null ? null : payment.accountNumber());
        addRemark(parts, first(attributes, "Remark", "remark"));
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private static void addRemark(List<String> values, String value) {
        String text = text(value);
        if (text != null && !values.contains(text)) values.add(text);
    }

    private static String unitCode(String sourceUnit) {
        String value = text(sourceUnit);
        if (value == null) {
            throw new ProjectionRejected("DHB_ORDER_LINE_UNIT_MISSING",
                    "订货宝订单明细缺少单位，不能生成销售订单",
                    "DETAIL", Map.of("required", "unitCode"), Map.of());
        }
        String alias = UNIT_ALIASES.get(value);
        if (alias != null) return alias;
        String normalized = value.toUpperCase(Locale.ROOT);
        if (INTERNAL_CODE.matcher(normalized).matches()) return normalized;
        throw new ProjectionRejected("DHB_ORDER_LINE_UNIT_UNMAPPED",
                "订货宝订单明细单位未映射到我方PRODUCT_UNIT编码",
                "MAPPING", Map.of("required", "PRODUCT_UNIT"), Map.of("sourceUnit", value));
    }

    private static String optionalUnitCode(String sourceUnit) {
        if (blank(sourceUnit)) return null;
        try {
            return unitCode(sourceUnit);
        } catch (ProjectionRejected ignored) {
            return null;
        }
    }

    private static void validateTask(SyncTaskContext task) {
        if (task == null) {
            throw new IllegalArgumentException("订货宝同步任务不存在");
        }
        if (!"ORDER".equalsIgnoreCase(task.objectType())) {
            throw new IllegalArgumentException("当前订货同步只支持 objectType=ORDER");
        }
        if (!task.enabled()) {
            throw new IllegalStateException("订货宝同步任务已禁用");
        }
        if (!"ACTIVE".equalsIgnoreCase(task.connectorStatus())) {
            throw new IllegalStateException("订货宝连接器未启用");
        }
        if ("PAUSED".equalsIgnoreCase(task.taskStatus())) {
            throw new IllegalStateException("订货宝同步任务已暂停");
        }
    }

    private static Window resolveWindow(SyncRunCommand command) {
        Instant from = command == null ? null : command.from();
        Instant to = command == null ? null : command.to();
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("同步窗口 from 和 to 必须同时提供");
        }
        if (from == null) {
            return null;
        }
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("同步窗口 from 必须早于 to");
        }
        return new Window(from, to);
    }

    private static TimeWindow timeWindow(Window window) {
        return window == null ? null : new TimeWindow(window.from(), window.to());
    }

    private static Instant windowFrom(Window window) {
        return window == null ? null : window.from();
    }

    private static Instant windowTo(Window window) {
        return window == null ? null : window.to();
    }

    private static int resolvePageSize(SyncTaskContext task, SyncRunCommand command) {
        int pageSize = command == null || command.pageSize() == null
                ? task.batchSize() : command.pageSize();
        if (pageSize <= 0) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > 1000) {
            throw new IllegalArgumentException("订货宝订单分页 pageSize 不能超过1000");
        }
        return pageSize;
    }

    private static int resolveMaxPages(Integer value) {
        int maxPages = value == null ? DEFAULT_MAX_PAGES : value;
        if (maxPages < 1 || maxPages > 100) {
            throw new IllegalArgumentException("订货宝订单maxPages必须在1到100之间");
        }
        return maxPages;
    }

    private static int normalizeDetailConcurrency(int value) {
        if (value < 1 || value > MAX_DETAIL_CONCURRENCY) {
            throw new IllegalArgumentException("订货宝订单明细并发度必须在1到" + MAX_DETAIL_CONCURRENCY + "之间");
        }
        return value;
    }

    private <T> List<ProjectionOutcome> projectDetails(String workerName, List<T> items,
                                                       Function<T, ProjectionOutcome> projector) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (detailConcurrency == 1 || items.size() == 1) {
            List<ProjectionOutcome> outcomes = new ArrayList<>(items.size());
            for (T item : items) {
                outcomes.add(projector.apply(item));
            }
            return outcomes;
        }
        int workers = Math.min(detailConcurrency, items.size());
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "rigour-dhb-" + workerName + "-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<ProjectionOutcome>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> projector.apply(item)));
            }
            List<ProjectionOutcome> outcomes = new ArrayList<>(futures.size());
            for (Future<ProjectionOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("订货宝" + workerName + "并发处理被中断", exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (cause instanceof Error error) {
                        throw error;
                    }
                    throw new IllegalStateException("订货宝" + workerName + "并发处理失败", cause);
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Map<String, Object>> rows(Map<String, Object> root, String... keys) {
        for (String key : keys) {
            Object value = firstObject(root, key);
            if (value instanceof Iterable<?> iterable) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : iterable) {
                    if (item instanceof Map<?, ?> map) result.add(stringMap(map));
                }
                return List.copyOf(result);
            }
            if (value instanceof Map<?, ?> map) return List.of(stringMap(map));
        }
        return List.of();
    }

    private static String first(Map<String, Object> preferred, Map<String, Object> fallback,
                                String... keys) {
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

    private static List<String> candidates(Map<String, Object> preferred, Map<String, Object> fallback,
                                           String... keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys) {
            addCandidate(result, text(firstObject(preferred, key)));
            addCandidate(result, text(firstObject(fallback, key)));
        }
        return List.copyOf(result);
    }

    private static List<String> candidates(Map<String, Object> source, String... keys) {
        return candidates(source, Map.<String, Object>of(), keys);
    }

    private static void addCandidate(List<String> values, String value) {
        if (blank(value)) return;
        String normalized = value.strip();
        if (!values.contains(normalized)) values.add(normalized);
    }

    private static void addNormalizedSkuCandidate(List<String> values, String productSourceId,
                                                  String skuSourceId) {
        if (blank(productSourceId) || blank(skuSourceId)) return;
        addCandidate(values, DhbIntegrationService.normalizedSkuSourceId(productSourceId, skuSourceId));
    }

    private static List<String> cleanDistinct(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values == null ? List.<String>of() : values) {
            addCandidate(result, text(value));
        }
        return List.copyOf(result);
    }

    private static Object firstObject(Map<String, Object> row, String... keys) {
        if (row == null || row.isEmpty()) return null;
        for (String key : keys) {
            Object direct = row.get(key);
            if (direct != null) return direct;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.strip();
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) return null;
        for (T value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Instant firstInstant(Map<String, Object> preferred, Map<String, Object> fallback,
                                        Instant typed, String... keys) {
        Instant value = instant(firstObject(preferred, keys));
        if (value != null) return value;
        value = instant(firstObject(fallback, keys));
        return value == null ? typed : value;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        String text = text(value);
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(text, D_HMS).atZone(SOURCE_ZONE).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        String text = text(value);
        if (text == null) return null;
        try {
            return new BigDecimal(text.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Map<String, Object> map(Map<String, Object> value) {
        return value == null ? Map.of() : new LinkedHashMap<>(value);
    }

    private static Map<String, Object> mapObject(Object value) {
        if (value instanceof Map<?, ?> map) return stringMap(map);
        return Map.of();
    }

    private static Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).strip();
        return result.isEmpty() || "null".equalsIgnoreCase(result) ? null : result;
    }

    private static String lower(String value) {
        String text = text(value);
        return text == null ? null : text.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safeValue(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "订货宝同步失败";
        }
        String redacted = message.replaceAll(
                "(?i)(password|token|skey|serialnumber|api[-_]?key)\\s*[:=]\\s*[^,;\\s}]+",
                "$1=[REDACTED]");
        return redacted.length() > 2000 ? redacted.substring(0, 2000) : redacted;
    }

    private record Window(Instant from, Instant to) {
    }

    private record PreparedSalesOrder(String sourceOrderNo, String sourceStatus,
                                      SalesOrderCommand command, boolean cancelled) {
    }

    private record PreparedSalesPayment(String sourceReceiptNo, String sourceOrderNo,
                                        SalesPaymentRecordCommand command) {
    }

    private record PreparedFundDocument(String sourceDocumentNo, String sourceOrderNo,
                                        FundDocumentCommand command) {
    }

    private record PreparedSalesShipment(String sourceShipmentNo, String sourceOrderNo,
                                         SalesOrderDetailView salesOrder,
                                         SalesShipmentCommand command) {
        PreparedSalesShipment withStockOut(ProjectedStockOut stockOut) {
            return new PreparedSalesShipment(sourceShipmentNo, sourceOrderNo, salesOrder,
                    DhbOrderSyncService.withStockOut(command, stockOut));
        }
    }

    private record PreparedStockOut(String sourceShipmentNo, String stockOutTypeCode,
                                    ExternalStockOutProjectionCommand command) {
    }

    private record PreparedTransferStockOut(
            String sourceShipmentNo, ExternalTransferStockOutProjectionCommand command) {
    }

    private record PreparedGenericStockOut(
            String sourceShipmentNo, String stockOutTypeCode,
            ExternalGenericStockOutProjectionCommand command) {
    }

    private record ProjectedStockOut(Long stockOutOrderId, String stockOutNo,
                                     String stockOutTypeCode, boolean duplicate) {
    }

    private record ProjectedTransferStockOut(Long transferOrderId, String transferNo,
                                             Long stockOutOrderId, String stockOutNo,
                                             boolean duplicate) {
    }

    private record InventoryStockOutLine(
            Long productId,
            Long productVariantId,
            String productCodeSnapshot,
            String skuCodeSnapshot,
            String productNameSnapshot,
            String unitCode,
            BigDecimal quantity,
            String remark) {
    }

    private record StaffProjection(String staffCode, String staffName) {
    }

    private static final class InventoryStockOutLineAccumulator {
        private final Long productId;
        private final Long productVariantId;
        private final String productCodeSnapshot;
        private final String skuCodeSnapshot;
        private final String productNameSnapshot;
        private final String unitCode;
        private BigDecimal quantity;
        private String remark;

        private InventoryStockOutLineAccumulator(InventoryStockOutLine line) {
            this.productId = line.productId();
            this.productVariantId = line.productVariantId();
            this.productCodeSnapshot = line.productCodeSnapshot();
            this.skuCodeSnapshot = line.skuCodeSnapshot();
            this.productNameSnapshot = line.productNameSnapshot();
            this.unitCode = line.unitCode();
            this.quantity = line.quantity();
            this.remark = line.remark();
        }

        static InventoryStockOutLineAccumulator from(InventoryStockOutLine line) {
            return new InventoryStockOutLineAccumulator(line);
        }

        InventoryStockOutLineAccumulator merge(InventoryStockOutLine line) {
            quantity = quantity.add(line.quantity());
            remark = mergedRemark(remark, line.remark());
            return this;
        }

        InventoryStockOutLine toLine() {
            return new InventoryStockOutLine(productId, productVariantId, productCodeSnapshot,
                    skuCodeSnapshot, productNameSnapshot, unitCode, quantity, remark);
        }

        private static String mergedRemark(String left, String right) {
            if (blank(left)) return right;
            if (blank(right) || left.contains(right.strip())) return left;
            return left + "；" + right.strip();
        }
    }

    private static final class SalesOrderLineAccumulator {
        private final Long productId;
        private final Long productVariantId;
        private final String productCodeSnapshot;
        private final String skuCodeSnapshot;
        private final String productNameSnapshot;
        private final String specificationSnapshot;
        private final String unitCode;
        private BigDecimal quantity;
        private BigDecimal originalAmount;
        private BigDecimal discountAmount;
        private String remark;

        private SalesOrderLineAccumulator(SalesOrderLineCommand line) {
            this.productId = line.productId();
            this.productVariantId = line.productVariantId();
            this.productCodeSnapshot = line.productCodeSnapshot();
            this.skuCodeSnapshot = line.skuCodeSnapshot();
            this.productNameSnapshot = line.productNameSnapshot();
            this.specificationSnapshot = line.specificationSnapshot();
            this.unitCode = line.unitCode();
            this.quantity = line.quantity();
            this.originalAmount = line.quantity().multiply(line.unitPrice());
            this.discountAmount = zeroIfNull(line.discountAmount());
            this.remark = line.remark();
        }

        static SalesOrderLineAccumulator from(SalesOrderLineCommand line) {
            return new SalesOrderLineAccumulator(line);
        }

        SalesOrderLineAccumulator merge(SalesOrderLineCommand line) {
            quantity = quantity.add(line.quantity());
            originalAmount = originalAmount.add(line.quantity().multiply(line.unitPrice()));
            discountAmount = discountAmount.add(zeroIfNull(line.discountAmount()));
            remark = mergedRemark(remark, line.remark());
            return this;
        }

        SalesOrderLineCommand toCommand() {
            BigDecimal unitPrice = quantity.compareTo(BigDecimal.ZERO) > 0
                    ? originalAmount.divide(quantity, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new SalesOrderLineCommand(productId, productVariantId, productCodeSnapshot,
                    skuCodeSnapshot, productNameSnapshot, specificationSnapshot, unitCode,
                    quantity, unitPrice, null, discountAmount, remark);
        }

        private static String mergedRemark(String left, String right) {
            if (blank(left)) return right;
            if (blank(right) || left.contains(right.strip())) return left;
            return left + "；" + right.strip();
        }
    }

    private static final class SalesShipmentLineAccumulator {
        private final Long salesOrderLineId;
        private final Long productId;
        private final Long productVariantId;
        private final String productCodeSnapshot;
        private final String skuCodeSnapshot;
        private final String productNameSnapshot;
        private final String specificationSnapshot;
        private final String unitCode;
        private BigDecimal shippedQuantity;
        private String remark;

        private SalesShipmentLineAccumulator(SalesShipmentLineCommand line) {
            this.salesOrderLineId = line.salesOrderLineId();
            this.productId = line.productId();
            this.productVariantId = line.productVariantId();
            this.productCodeSnapshot = line.productCodeSnapshot();
            this.skuCodeSnapshot = line.skuCodeSnapshot();
            this.productNameSnapshot = line.productNameSnapshot();
            this.specificationSnapshot = line.specificationSnapshot();
            this.unitCode = line.unitCode();
            this.shippedQuantity = line.shippedQuantity();
            this.remark = line.remark();
        }

        static SalesShipmentLineAccumulator from(SalesShipmentLineCommand line) {
            return new SalesShipmentLineAccumulator(line);
        }

        SalesShipmentLineAccumulator merge(SalesShipmentLineCommand line) {
            shippedQuantity = shippedQuantity.add(line.shippedQuantity());
            remark = mergedRemark(remark, line.remark());
            return this;
        }

        SalesShipmentLineCommand toCommand() {
            return new SalesShipmentLineCommand(salesOrderLineId, productId, productVariantId,
                    productCodeSnapshot, skuCodeSnapshot, productNameSnapshot,
                    specificationSnapshot, unitCode, shippedQuantity, remark);
        }

        private static String mergedRemark(String left, String right) {
            if (blank(left)) return right;
            if (blank(right) || left.contains(right.strip())) return left;
            return left + "；" + right.strip();
        }
    }

    private enum ProjectionOutcome {
        ACCEPTED, DUPLICATE, REJECTED
    }

    private record MappingLookupKey(UUID tenantId, UUID connectorId,
                                    String sourceObjectType, String sourceObjectId) {
    }

    private static final class Counts {
        long fetched;
        long accepted;
        long duplicate;
        long rejected;

        void add(ProjectionOutcome outcome) {
            switch (outcome) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicate++;
                case REJECTED -> rejected++;
            }
        }

        void addAll(List<ProjectionOutcome> outcomes) {
            for (ProjectionOutcome outcome : outcomes) {
                add(outcome);
            }
        }
    }

    private static final class ProjectionRejected extends RuntimeException {
        private final String code;
        private final String checkType;
        private final Map<String, Object> expected;
        private final Map<String, Object> actual;

        ProjectionRejected(String code, String message, String checkType,
                           Map<String, Object> expected, Map<String, Object> actual) {
            super(message);
            this.code = code;
            this.checkType = checkType;
            this.expected = expected == null ? Map.of() : Map.copyOf(expected);
            this.actual = actual == null ? Map.of() : Map.copyOf(actual);
        }

        String code() { return code; }
        String checkType() { return checkType; }
        Map<String, Object> expected() { return expected; }
        Map<String, Object> actual() { return actual; }
    }
}
