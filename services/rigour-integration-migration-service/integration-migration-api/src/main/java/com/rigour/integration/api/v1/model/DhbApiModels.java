package com.rigour.integration.api.v1.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration V1 的跨服务请求/响应模型。
 *
 * <p>这些模型描述本平台的 Integration 契约，不等同于订货宝官方的 {@code f/v} 报文；
 * 账号密码、Token 和原始回执不会进入该模块。</p>
 */
public final class DhbApiModels {

    private DhbApiModels() {
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

    /** 手动订单同步请求；首次联调建议显式提供时间窗口，避免误拉全量数据。 */
    public record SyncRunCommand(Instant from, Instant to, Integer pageSize) {
    }

    /** 手动同步结果；不包含第三方凭据、令牌或原始回执。 */
    public record SyncRunView(UUID runId, UUID taskId, String status,
                              Instant windowFrom, Instant windowTo,
                              long fetchedCount, long acceptedCount,
                              long duplicateCount, long rejectedCount,
                              String errorCode, String errorMessage) {
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

    /** 商品查询参数，对应订货宝 getGoodsList 的非认证参数。 */
    public record ProductQueryCommand(Integer begin, Integer step, String status, String putaway,
                                      String goodsCode, Instant updatedFrom, Instant updatedTo,
                                      String barcode) {
    }

    /** 客户查询参数，对应订货宝 getDealersList 的非认证参数。 */
    public record CustomerQueryCommand(Integer begin, Integer step, Integer status, Integer dataType,
                                       String timeType, Instant updatedFrom, Instant updatedTo,
                                       String clientNo, Integer clientArea, Integer typeId) {
    }

    /** 订单查询参数，对应订货宝 getOrderList 的非认证参数。 */
    public record OrderQueryCommand(Integer begin, Integer step, String orderStatus,
                                    Instant createdFrom, Instant createdTo,
                                    Instant updatedFrom, Instant updatedTo,
                                    String exceptionStatus, String apiStatus, String payStatus,
                                    Integer splitType) {
    }

    /** 订单明细查询命令；自动标记和自动审核必须由调用方显式决定。 */
    public record OrderContentCommand(Boolean autoMarkDownloaded, Boolean autoAudit) {
    }

    public record ProductPageView(long total, List<ProductView> items) {
    }

    public record ProductView(String sourceId, String code, String name, String putaway,
                              Map<String, Object> sourceFields) {
    }

    public record CustomerPageView(long total, List<CustomerView> items) {
    }

    public record CustomerView(String sourceId, String account, String number, String name,
                               String status, Instant createdAt, Instant updatedAt,
                               Map<String, Object> sourceFields) {
    }

    public record OrderPageView(long total, List<OrderView> items) {
    }

    public record OrderView(String sourceId, String orderNumber, String status, BigDecimal amount,
                            Instant createdAt, Instant updatedAt, String customerNumber,
                            String paymentStatus, Map<String, Object> sourceFields) {
    }

    public record OrderContentView(String orderNumber, String status, BigDecimal amount,
                                   Map<String, Object> sourceFields) {
    }
}
