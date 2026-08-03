package com.rigour.integration.api.controller.dinghuobao;

import com.rigour.integration.application.service.dinghuobao.DinghuobaoIntegrationService;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.ConnectorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.FieldMappingView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.OrderMirrorView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncLogView;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskCommand;
import com.rigour.integration.application.service.dinghuobao.DinghuobaoModels.SyncTaskView;
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
@RequestMapping("/api/v1/integration/dinghuobao")
public final class DinghuobaoIntegrationController {

    private final DinghuobaoIntegrationService service;

    public DinghuobaoIntegrationController(DinghuobaoIntegrationService service) {
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

    @GetMapping("/order-mirrors")
    public List<OrderMirrorView> orderMirrors(
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return service.orderMirrors(limit, offset);
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
}
