package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTargetView;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 订货宝同步持久化端口；实现必须在SQL层绑定已验签租户ID。 */
public interface DhbIntegrationStore {
    List<ConnectorView> connectors(UUID tenantId);
    ConnectorView connector(UUID tenantId, UUID connectorId);
    void recordConnectionTest(UUID tenantId, UUID actorId, UUID connectorId, ConnectionTestResult result);
    ConnectorView createConnector(UUID tenantId, UUID actorId, ConnectorCommand command);
    ConnectorView updateConnector(UUID tenantId, UUID actorId, UUID id, ConnectorCommand command);
    List<SyncTaskView> syncTasks(UUID tenantId);
    List<SyncTargetView> activeSyncTargets(String objectType);

    default List<SyncTargetView> activeOrderSyncTargets() {
        return activeSyncTargets("ORDER");
    }
    SyncTaskView createSyncTask(UUID tenantId, UUID actorId, SyncTaskCommand command);
    SyncTaskView updateSyncTask(UUID tenantId, UUID actorId, UUID id, SyncTaskCommand command);
    List<OrderMirrorView> orderMirrors(UUID tenantId, int limit, int offset);
    List<SyncLogView> syncLogs(UUID tenantId, int limit);
    List<FieldMappingView> fieldMappings(UUID tenantId, UUID connectorId);
    FieldMappingView saveFieldMapping(UUID tenantId, UUID actorId, UUID id, FieldMappingCommand command);

    /** 保存订货宝技术原始业务字段；不得传入sKey、账号、密码或Token。 */
    void persistRawLanding(UUID tenantId, UUID connectorId, String sourceObjectType,
                           String sourceId, Instant sourceUpdatedAt, Map<String, Object> payload);

    /**
     * 按订货宝分页批量保存技术原始字段，避免每条记录单独开启事务。
     * 默认实现保留其他适配器的兼容性；JDBC实现应覆盖为单事务批量写入。
     */
    default void persistRawLandings(UUID tenantId, UUID connectorId, List<RawLanding> values) {
        if (values == null) return;
        values.forEach(value -> persistRawLanding(tenantId, connectorId,
                value.sourceObjectType(), value.sourceId(), value.sourceUpdatedAt(), value.payload()));
    }

    record RawLanding(String sourceObjectType, String sourceId, Instant sourceUpdatedAt,
                      Map<String, Object> payload) {
    }
}
