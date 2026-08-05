package com.rigour.integration.application.port.out;

import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.OrderMirrorView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.application.port.out.DhbClient.ConnectionTestResult;
import java.util.List;
import java.util.UUID;

/** 订货宝同步持久化端口；实现必须在SQL层绑定已验签租户ID。 */
public interface DhbIntegrationStore {
    List<ConnectorView> connectors(UUID tenantId);
    ConnectorView connector(UUID tenantId, UUID connectorId);
    void recordConnectionTest(UUID tenantId, UUID actorId, UUID connectorId, ConnectionTestResult result);
    ConnectorView createConnector(UUID tenantId, UUID actorId, ConnectorCommand command);
    ConnectorView updateConnector(UUID tenantId, UUID actorId, UUID id, ConnectorCommand command);
    List<SyncTaskView> syncTasks(UUID tenantId);
    SyncTaskView createSyncTask(UUID tenantId, UUID actorId, SyncTaskCommand command);
    SyncTaskView updateSyncTask(UUID tenantId, UUID actorId, UUID id, SyncTaskCommand command);
    List<OrderMirrorView> orderMirrors(UUID tenantId, int limit, int offset);
    List<SyncLogView> syncLogs(UUID tenantId, int limit);
    List<FieldMappingView> fieldMappings(UUID tenantId, UUID connectorId);
    FieldMappingView saveFieldMapping(UUID tenantId, UUID actorId, UUID id, FieldMappingCommand command);
}
