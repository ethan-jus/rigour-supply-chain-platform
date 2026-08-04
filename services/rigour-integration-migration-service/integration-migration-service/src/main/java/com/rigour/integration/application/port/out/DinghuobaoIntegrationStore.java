package com.rigour.integration.application.port.out;

import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.OrderMirrorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncLogView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskView;
import com.rigour.integration.application.port.out.DinghuobaoClient.ConnectionTestResult;
import java.util.List;
import java.util.UUID;

/** 订货宝同步持久化端口；实现必须在SQL层绑定已验签租户ID。 */
public interface DinghuobaoIntegrationStore {
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
