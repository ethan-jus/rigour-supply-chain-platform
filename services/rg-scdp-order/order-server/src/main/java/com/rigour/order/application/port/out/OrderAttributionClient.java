package com.rigour.order.application.port.out;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.shared.context.CallerIdentity;

/** 原操作人客户可选范围 + 服务内部归属读取，禁止跨库查询。 */
public interface OrderAttributionClient {
    default com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView paymentOwner(CallerIdentity actor,long id,java.time.Instant at) {
        return new com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView(null,null,null,"历史归属接口尚未可用");
    }
    CustomerOrderAttributionView resolve(CallerIdentity actor, long customerId);
}
