package com.rigour.integration.application.service.dhb;

import com.rigour.integration.application.port.out.DhbIntegrationStore;
import com.rigour.integration.application.port.out.DhbClient;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import com.rigour.integration.application.port.out.DhbClient.Customer;
import com.rigour.integration.application.port.out.DhbClient.CustomerQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderDetail;
import com.rigour.integration.application.port.out.DhbClient.OrderQuery;
import com.rigour.integration.application.port.out.DhbClient.OrderSummary;
import com.rigour.integration.application.port.out.DhbClient.Page;
import com.rigour.integration.application.port.out.DhbClient.PageRequest;
import com.rigour.integration.application.port.out.DhbClient.Product;
import com.rigour.integration.application.port.out.DhbClient.ProductQuery;
import com.rigour.integration.application.port.out.DhbClient.TimeWindow;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.CustomerPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.CustomerQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.CustomerView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderContentView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductPageView;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductQueryCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ProductView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncRunView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbConnectionTestResult;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 订货宝数据同步用例；租户和权限只取Gateway签名上下文，不接受客户端传入。 */
public final class DhbIntegrationService {

    private final DhbIntegrationStore store;
    private final DhbClient client;
    private final DhbOrderSyncService orderSyncService;

    public DhbIntegrationService(DhbIntegrationStore store, DhbClient client,
                                        DhbOrderSyncService orderSyncService) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.client = Objects.requireNonNull(client, "client cannot be null");
        this.orderSyncService = Objects.requireNonNull(orderSyncService, "orderSyncService cannot be null");
    }

    public List<ConnectorView> connectors() {
        CallerIdentity caller = requireReadCaller();
        return store.connectors(caller.tenantId());
    }

    public ConnectorView createConnector(ConnectorCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.createConnector(caller.tenantId(), caller.userId(), command);
    }

    /** 只验证订货宝认证，不返回 Secret 或令牌；调用需要写权限因为会触发外部请求。 */
    public DhbConnectionTestResult testConnection(UUID connectorId) {
        CallerIdentity caller = requireWriteCaller();
        ConnectorView connector = store.connector(caller.tenantId(), connectorId);
        ConnectionTestResult result = client.testConnection(new DhbClient.Connector(
                connector.tenantId(), connector.id(), connector.baseUrl(), connector.authSecretRef()));
        store.recordConnectionTest(caller.tenantId(), caller.userId(), connectorId, result);
        return new DhbConnectionTestResult(result.success(), result.code(), result.message(),
                result.tokenExpiresAt());
    }

    public ConnectorView updateConnector(UUID id, ConnectorCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.updateConnector(caller.tenantId(), caller.userId(), id, command);
    }

    /** 查询订货宝商品；认证、分页和字段兼容由 Integration 适配器处理。 */
    public ProductPageView products(UUID connectorId, ProductQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        ProductQuery query = command == null
                ? new ProductQuery(PageRequest.first(100), null, null, null, null, null)
                : new ProductQuery(page(command.begin(), command.step()), command.status(),
                command.putaway(), command.goodsCode(), window("商品更新时间",
                command.updatedFrom(), command.updatedTo()), command.barcode());
        Page<Product> page = client.getProducts(connector(caller, connectorId), query);
        return new ProductPageView(page.total(), page.items().stream()
                .map(item -> new ProductView(item.sourceId(), item.code(), item.name(), item.putaway(),
                        item.attributes()))
                .toList());
    }

    /** 查询订货宝客户；联系方式等来源字段仅在调用方已有 Integration 权限时返回。 */
    public CustomerPageView customers(UUID connectorId, CustomerQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        CustomerQuery query = command == null
                ? new CustomerQuery(PageRequest.first(100), null, null, null, null, null, null, null)
                : new CustomerQuery(page(command.begin(), command.step()), command.status(),
                command.dataType(), command.timeType(), window("客户时间",
                command.updatedFrom(), command.updatedTo()), command.clientNo(), command.clientArea(),
                command.typeId());
        Page<Customer> page = client.getCustomers(connector(caller, connectorId), query);
        return new CustomerPageView(page.total(), page.items().stream()
                .map(item -> new CustomerView(item.sourceId(), item.account(), item.number(), item.name(),
                        item.status(), item.createdAt(), item.updatedAt(), item.attributes()))
                .toList());
    }

    /** 查询订货宝订单摘要；调用 getOrderList，不会调用订单明细副作用接口。 */
    public OrderPageView orders(UUID connectorId, OrderQueryCommand command) {
        CallerIdentity caller = requireReadCaller();
        OrderQuery query = command == null
                ? new OrderQuery(PageRequest.first(100), null, null, null, null, null, null, null)
                : new OrderQuery(page(command.begin(), command.step()), command.orderStatus(),
                window("订单创建时间", command.createdFrom(), command.createdTo()),
                window("订单更新时间", command.updatedFrom(), command.updatedTo()),
                command.exceptionStatus(), command.apiStatus(), command.payStatus(), command.splitType());
        Page<OrderSummary> page = client.getOrders(connector(caller, connectorId), query);
        return new OrderPageView(page.total(), page.items().stream()
                .map(item -> new OrderView(item.sourceId(), item.orderNumber(), item.status(), item.amount(),
                        item.createdAt(), item.updatedAt(), item.customerNumber(), item.paymentStatus(),
                        item.attributes()))
                .toList());
    }

    /**
     * 获取订单明细。订货宝文档说明该接口可能标记订单已获取/审核，因此必须具备写权限，
     * 且调用方必须显式传入两个副作用开关。
     */
    public OrderContentView orderContent(UUID connectorId, String orderNumber,
                                         OrderContentCommand command) {
        CallerIdentity caller = requireWriteCaller();
        if (orderNumber == null || orderNumber.isBlank()) {
            throw new IllegalArgumentException("orderNumber is required");
        }
        boolean autoMarkDownloaded = command != null && Boolean.TRUE.equals(command.autoMarkDownloaded());
        boolean autoAudit = command != null && Boolean.TRUE.equals(command.autoAudit());
        OrderDetail detail = client.getOrderContent(connector(caller, connectorId), orderNumber,
                autoMarkDownloaded, autoAudit);
        return new OrderContentView(detail.orderNumber(), detail.status(), detail.amount(), detail.attributes());
    }

    public List<SyncTaskView> syncTasks() {
        CallerIdentity caller = requireReadCaller();
        return store.syncTasks(caller.tenantId());
    }

    public SyncTaskView createSyncTask(SyncTaskCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.createSyncTask(caller.tenantId(), caller.userId(), command);
    }

    public SyncTaskView updateSyncTask(UUID id, SyncTaskCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.updateSyncTask(caller.tenantId(), caller.userId(), id, command);
    }

    /** 手动执行第一阶段订单同步；定时调度和供应商写接口暂不在本用例启用。 */
    public SyncRunView runOrderPull(UUID taskId, SyncRunCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return orderSyncService.runOrderPull(caller, taskId, command);
    }

    public List<OrderMirrorView> orderMirrors(int limit, int offset) {
        CallerIdentity caller = requireReadCaller();
        return store.orderMirrors(caller.tenantId(), Math.max(1, Math.min(limit, 200)),
                Math.max(0, offset));
    }

    public List<SyncLogView> syncLogs(int limit) {
        CallerIdentity caller = requireReadCaller();
        return store.syncLogs(caller.tenantId(), Math.max(1, Math.min(limit, 500)));
    }

    public List<FieldMappingView> fieldMappings(UUID connectorId) {
        CallerIdentity caller = requireReadCaller();
        return store.fieldMappings(caller.tenantId(), connectorId);
    }

    public FieldMappingView saveFieldMapping(UUID id, FieldMappingCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.saveFieldMapping(caller.tenantId(), caller.userId(), id, command);
    }

    private DhbClient.Connector connector(CallerIdentity caller, UUID connectorId) {
        ConnectorView connector = store.connector(caller.tenantId(), connectorId);
        return new DhbClient.Connector(caller.tenantId(), connector.id(), connector.baseUrl(),
                connector.authSecretRef());
    }

    private static PageRequest page(Integer begin, Integer step) {
        return new PageRequest(begin == null ? 0 : begin, step == null ? 100 : step);
    }

    private static TimeWindow window(String field, java.time.Instant from, java.time.Instant to) {
        if (from == null && to == null) {
            return null;
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException(field + " from 和 to 必须同时提供");
        }
        return new TimeWindow(from, to);
    }

    private static CallerIdentity requireReadCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        AuthorizationContext.requirePermission("integration:dhb:read");
        return caller;
    }

    private static CallerIdentity requireWriteCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        AuthorizationContext.requirePermission("integration:dhb:write");
        return caller;
    }

    private static void requireTenant(CallerIdentity caller) {
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new com.rigour.shared.context.AuthorizationDeniedException("tenant-caller");
        }
    }
}
