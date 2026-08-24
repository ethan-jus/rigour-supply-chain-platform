package com.rigour.integration.api.controller.dhb;

import com.rigour.integration.application.service.dhb.DhbIntegrationService;
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
import com.rigour.integration.api.v1.DhbIntegrationApi;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 订货宝数据同步HTTP边界；身份与权限只来自Gateway签名上下文。 */
@RestController
@RequestMapping(DhbIntegrationApi.BASE_PATH)
public final class DhbIntegrationController implements DhbIntegrationApi {

    private final DhbIntegrationService service;

    public DhbIntegrationController(DhbIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/connectors")
    public List<ConnectorView> connectors() {
        return service.connectors();
    }

    @PostMapping("/connectors")
    public ConnectorView createConnector(@RequestBody ConnectorCommand command) {
        return service.createConnector(command);
    }

    @PostMapping("/connectors/{id}/test")
    public DhbConnectionTestResult testConnection(@PathVariable("id") UUID id) {
        return service.testConnection(id);
    }

    @PutMapping("/connectors/{id}")
    public ConnectorView updateConnector(@PathVariable("id") UUID id,
                                         @RequestBody ConnectorCommand command) {
        return service.updateConnector(id, command);
    }

    @GetMapping("/sync-tasks")
    public List<SyncTaskView> syncTasks() {
        return service.syncTasks();
    }

    @PostMapping("/sync-tasks")
    public SyncTaskView createSyncTask(@RequestBody SyncTaskCommand command) {
        return service.createSyncTask(command);
    }

    @PutMapping("/sync-tasks/{id}")
    public SyncTaskView updateSyncTask(@PathVariable("id") UUID id,
                                       @RequestBody SyncTaskCommand command) {
        return service.updateSyncTask(id, command);
    }

    @GetMapping("/sync-logs")
    public List<SyncLogView> syncLogs(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.syncLogs(limit);
    }

    @GetMapping("/connectors/{connectorId}/field-mappings")
    public List<FieldMappingView> fieldMappings(@PathVariable("connectorId") UUID connectorId) {
        return service.fieldMappings(connectorId);
    }

    @PostMapping("/field-mappings")
    public FieldMappingView createFieldMapping(@RequestBody FieldMappingCommand command) {
        return service.saveFieldMapping(null, command);
    }

    @PutMapping("/field-mappings/{id}")
    public FieldMappingView updateFieldMapping(@PathVariable("id") UUID id,
                                               @RequestBody FieldMappingCommand command) {
        return service.saveFieldMapping(id, command);
    }

    @GetMapping("/sync-center/field-descriptions")
    public List<DhbSyncFieldDescriptionView> syncFieldDescriptions() {
        return service.syncFieldDescriptions();
    }

    @GetMapping("/sync-center/object-mappings")
    public DhbExternalObjectMappingPageView externalObjectMappings(
            @RequestParam(name = "sourceObjectType", required = false) String sourceObjectType,
            @RequestParam(name = "internalDomain", required = false) String internalDomain,
            @RequestParam(name = "mappingStatus", required = false) String mappingStatus,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return service.externalObjectMappings(sourceObjectType, internalDomain, mappingStatus, limit, offset);
    }

    @GetMapping("/sync-center/runs")
    public List<DhbSyncRunAuditView> syncRuns(
            @RequestParam(name = "objectType", required = false) String objectType,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.syncRuns(objectType, status, limit);
    }

    @GetMapping("/sync-center/logs")
    public List<DhbSyncLogDetailView> syncLogDetails(
            @RequestParam(name = "runId", required = false) UUID runId,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.syncLogDetails(runId, level, limit);
    }

    @GetMapping("/sync-center/exceptions")
    public List<DhbSyncExceptionView> syncExceptions(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.syncExceptions(status, limit);
    }

    @GetMapping("/sync-center/reconciliation-cases")
    public List<DhbSyncReconciliationCaseView> syncReconciliationCases(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return service.syncReconciliationCases(status, severity, limit);
    }
}
