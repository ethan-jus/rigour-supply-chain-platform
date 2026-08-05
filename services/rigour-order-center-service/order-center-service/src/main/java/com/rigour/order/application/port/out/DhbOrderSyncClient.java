package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.DhbOrderImportBatch;
import com.rigour.order.api.v1.model.DhbOrderSyncCommand;
import com.rigour.shared.context.CallerIdentity;
import java.util.Set;
import java.util.UUID;

/** Order Center 调用 Integration 订货宝订单查询契约的出站端口。 */
public interface DhbOrderSyncClient {
    /**
     * @param caller Gateway 已确认的当前租户调用人；身份和权限必须重新签名后传给 Integration
     * @param connectorId 当前租户在 Integration 中登记的连接器 UUID
     * @param command 查询窗口、是否拉订单明细和最大页数
     * @return Integration V1 查询结果转换成 Order Center 的本地导入批次
     */
    Collected collect(CallerIdentity caller, UUID connectorId, DhbOrderSyncCommand command);

    record Collected(UUID runId, String objectType, long fetched,
                     Set<String> completedObjects, DhbOrderImportBatch batch) {
        public Collected {
            runId = runId == null ? UUID.randomUUID() : runId;
            objectType = objectType == null || objectType.isBlank() ? "ORDER" : objectType;
            completedObjects = completedObjects == null ? Set.of() : Set.copyOf(completedObjects);
            batch = batch == null ? new DhbOrderImportBatch(null, null, null, null) : batch;
        }
    }
}
