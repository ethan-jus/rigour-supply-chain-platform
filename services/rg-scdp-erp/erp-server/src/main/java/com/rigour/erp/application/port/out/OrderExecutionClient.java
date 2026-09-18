package com.rigour.erp.application.port.out;

import com.rigour.order.api.v1.model.FulfillmentExecutionView;
import com.rigour.shared.context.CallerIdentity;

/** 原操作人经 Order 在线核验后取得不可变履约内容。 */
public interface OrderExecutionClient {
    FulfillmentExecutionView claim(CallerIdentity actor, String executionId);
}
