package com.rigour.order.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * 订单域订货宝同步游标端口。
 *
 * <p>游标只在业务批次成功落库后推进；外部调用失败或本地落库失败时保留上一个成功游标，
 * 下一次同步会重新读取重叠窗口，依靠业务键和payloadHash幂等去重。</p>
 */
public interface DhbOrderSyncCheckpointStore {
    /** 读取租户、连接器、对象类型对应的最近成功时间；首次同步返回null。 */
    Instant lastSuccessAt(String tenantId, UUID connectorId, String objectType);

    /** 标记一次完整成功的业务批次，并推进最近成功游标。 */
    void markSucceeded(String tenantId, UUID connectorId, String objectType,
                       UUID runId, Instant windowTo);

    /** 记录同步失败，但不得覆盖最近成功游标。 */
    void markFailed(String tenantId, UUID connectorId, String objectType,
                    UUID runId, String errorMessage);
}
