package com.rigour.integration.application.service.dinghuobao;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 订货宝数据同步的应用层命令与只读视图；禁止把Secret或Raw Payload放进视图。 */
public final class DinghuobaoModels {

    private DinghuobaoModels() {
    }

    public record ConnectorView(UUID id, UUID tenantId, String code, String name,
                                String baseUrl, String authSecretRef, String status, long version) {
    }

    public record ConnectorCommand(String code, String name, String baseUrl,
                                   String authSecretRef, String status, long version) {
    }

    public record SyncTaskView(UUID id, UUID tenantId, UUID connectorId, String code,
                               String objectType, String status, Instant lastRunAt,
                               Instant nextRunAt, long version) {
    }

    public record SyncTaskCommand(UUID connectorId, String code, String objectType,
                                  String status, Instant nextRunAt, long version) {
    }

    public record OrderMirrorView(UUID id, UUID tenantId, String sourceOrderId, String orderNo,
                                  String sourceStatus, BigDecimal amount, Instant orderTime,
                                  String mirrorStatus, long version) {
    }

    public record SyncLogView(UUID id, UUID tenantId, UUID taskId, UUID runId, String level,
                              String message, String errorCode, Instant occurredAt) {
    }

    public record FieldMappingView(UUID id, UUID tenantId, UUID connectorId, String sourceField,
                                   String targetField, String transformType, boolean enabled,
                                   long version) {
    }

    public record FieldMappingCommand(UUID connectorId, String sourceField, String targetField,
                                      String transformType, boolean enabled, long version) {
    }
}
