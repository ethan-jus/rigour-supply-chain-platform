package com.rigour.integration.application.service.dinghuobao;

import com.rigour.integration.application.port.out.DinghuobaoIntegrationStore;
import com.rigour.integration.application.port.out.DinghuobaoClient;
import com.rigour.integration.application.port.out.DinghuobaoClient.ConnectionTestResult;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.OrderMirrorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncLogView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskView;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.CallerIdentity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 订货宝数据同步用例；租户和权限只取Gateway签名上下文，不接受客户端传入。 */
public final class DinghuobaoIntegrationService {

    private final DinghuobaoIntegrationStore store;
    private final DinghuobaoClient client;

    public DinghuobaoIntegrationService(DinghuobaoIntegrationStore store, DinghuobaoClient client) {
        this.store = Objects.requireNonNull(store, "store cannot be null");
        this.client = Objects.requireNonNull(client, "client cannot be null");
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
    public ConnectionTestResult testConnection(UUID connectorId) {
        CallerIdentity caller = requireWriteCaller();
        ConnectorView connector = store.connector(caller.tenantId(), connectorId);
        ConnectionTestResult result = client.testConnection(new DinghuobaoClient.Connector(
                connector.tenantId(), connector.id(), connector.baseUrl(), connector.authSecretRef()));
        store.recordConnectionTest(caller.tenantId(), caller.userId(), connectorId, result);
        return result;
    }

    public ConnectorView updateConnector(UUID id, ConnectorCommand command) {
        CallerIdentity caller = requireWriteCaller();
        return store.updateConnector(caller.tenantId(), caller.userId(), id, command);
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

    private static CallerIdentity requireReadCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        AuthorizationContext.requirePermission("integration:dinghuobao:read");
        return caller;
    }

    private static CallerIdentity requireWriteCaller() {
        CallerIdentity caller = AuthorizationContext.requireCurrent();
        requireTenant(caller);
        AuthorizationContext.requirePermission("integration:dinghuobao:write");
        return caller;
    }

    private static void requireTenant(CallerIdentity caller) {
        if (caller.tenantId() == null || caller.userId() == null) {
            throw new com.rigour.shared.context.AuthorizationDeniedException("tenant-caller");
        }
    }
}
