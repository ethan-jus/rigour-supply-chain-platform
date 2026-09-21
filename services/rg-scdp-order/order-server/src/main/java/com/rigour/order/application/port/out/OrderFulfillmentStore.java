package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.*;
import com.rigour.shared.context.CallerIdentity;

import java.time.Instant;

/** Order 独占执行意图和回写状态；每次 ERP 调用前提交意图，网络调用不占本地事务。 */
public interface OrderFulfillmentStore {
    OrderFulfillmentQueueView queue(String tenant,int begin,int step,String keyword,String outboundStatus);
    OrderFulfillmentQueueView.Detail detail(String tenant,long id);

    /** 订单客户归属地区编码，用于按地区匹配出库仓库；没有归属地区返回 null。 */
    String regionCode(String tenant,long id);
    java.util.Set<Long> permittedWarehouseIds(
            CallerIdentity actor, long orderId, java.util.List<Long> candidates);

    OrderFulfillmentStatusView status(String tenant, long id);

    OrderFulfillmentStatusView select(CallerIdentity actor, long id, long warehouse, int revision);

    FulfillmentExecutionView prepare(
            CallerIdentity actor,
            long id,
            long warehouse,
            int revision,
            Instant time,
            String remark);

    FulfillmentExecutionView claim(CallerIdentity actor, String executionId);

    OrderFulfillmentStatusView complete(
            String tenant,
            String executionId,
            long stockOutId,
            String stockOutNo,
            Instant time,
            long warehouseId);

    void failed(String tenant, String executionId, String reason);
}
