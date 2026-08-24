package com.rigour.integration.api.v1;

import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.ConnectorView;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.FieldMappingView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncLogView;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskCommand;
import com.rigour.integration.api.v1.model.DhbApiModels.SyncTaskView;
import com.rigour.integration.api.v1.model.DhbConnectionTestResult;
import com.rigour.integration.api.v1.model.DhbExternalObjectMappingPageView;
import com.rigour.integration.api.v1.model.DhbSyncExceptionView;
import com.rigour.integration.api.v1.model.DhbSyncFieldDescriptionView;
import com.rigour.integration.api.v1.model.DhbSyncLogDetailView;
import com.rigour.integration.api.v1.model.DhbSyncReconciliationCaseView;
import com.rigour.integration.api.v1.model.DhbSyncRunAuditView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 订货宝 Integration V1 控制面 HTTP 契约。
 *
 * <p>这里只负责连接器、同步任务、字段映射和日志控制面。商品、订单等业务域查询分别由
 * {@link DhbProductApi}、{@link DhbOrderApi} 暴露，避免所有第三方能力挤在一个接口里。</p>
 */
public interface DhbIntegrationApi {

    String BASE_PATH = "/api/v1/integration/dhb";
    String CONNECTORS_PATH = BASE_PATH + "/connectors";
    String SYNC_TASKS_PATH = BASE_PATH + "/sync-tasks";
    String SYNC_LOGS_PATH = BASE_PATH + "/sync-logs";
    String FIELD_MAPPINGS_PATH = BASE_PATH + "/field-mappings";
    String SYNC_CENTER_PATH = BASE_PATH + "/sync-center";

    @GetMapping(CONNECTORS_PATH)
    List<ConnectorView> connectors();

    @PostMapping(CONNECTORS_PATH)
    ConnectorView createConnector(@RequestBody ConnectorCommand command);

    @PostMapping(CONNECTORS_PATH + "/{id}/test")
    DhbConnectionTestResult testConnection(@PathVariable("id") UUID id);

    @PutMapping(CONNECTORS_PATH + "/{id}")
    ConnectorView updateConnector(@PathVariable("id") UUID id,
                                  @RequestBody ConnectorCommand command);

    @GetMapping(SYNC_TASKS_PATH)
    List<SyncTaskView> syncTasks();

    @PostMapping(SYNC_TASKS_PATH)
    SyncTaskView createSyncTask(@RequestBody SyncTaskCommand command);

    @PutMapping(SYNC_TASKS_PATH + "/{id}")
    SyncTaskView updateSyncTask(@PathVariable("id") UUID id,
                                @RequestBody SyncTaskCommand command);

    @GetMapping(SYNC_LOGS_PATH)
    List<SyncLogView> syncLogs(
            @RequestParam(name = "limit", defaultValue = "100") int limit);

    @GetMapping(CONNECTORS_PATH + "/{connectorId}/field-mappings")
    List<FieldMappingView> fieldMappings(@PathVariable("connectorId") UUID connectorId);

    @PostMapping(FIELD_MAPPINGS_PATH)
    FieldMappingView createFieldMapping(@RequestBody FieldMappingCommand command);

    @PutMapping(FIELD_MAPPINGS_PATH + "/{id}")
    FieldMappingView updateFieldMapping(@PathVariable("id") UUID id,
                                        @RequestBody FieldMappingCommand command);

    @GetMapping(SYNC_CENTER_PATH + "/field-descriptions")
    List<DhbSyncFieldDescriptionView> syncFieldDescriptions();

    @GetMapping(SYNC_CENTER_PATH + "/object-mappings")
    DhbExternalObjectMappingPageView externalObjectMappings(
            @RequestParam(name = "sourceObjectType", required = false) String sourceObjectType,
            @RequestParam(name = "internalDomain", required = false) String internalDomain,
            @RequestParam(name = "mappingStatus", required = false) String mappingStatus,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset);

    @GetMapping(SYNC_CENTER_PATH + "/runs")
    List<DhbSyncRunAuditView> syncRuns(
            @RequestParam(name = "objectType", required = false) String objectType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit);

    @GetMapping(SYNC_CENTER_PATH + "/logs")
    List<DhbSyncLogDetailView> syncLogDetails(
            @RequestParam(name = "runId", required = false) UUID runId,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "limit", defaultValue = "100") int limit);

    @GetMapping(SYNC_CENTER_PATH + "/exceptions")
    List<DhbSyncExceptionView> syncExceptions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit);

    @GetMapping(SYNC_CENTER_PATH + "/reconciliation-cases")
    List<DhbSyncReconciliationCaseView> syncReconciliationCases(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "limit", defaultValue = "100") int limit);
}
