package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.*;
import com.rigour.order.api.v1.model.FulfillmentExecutionView;

import java.util.Optional;
import java.util.function.Supplier;

/** 幂等回执与真实扣库工作在同一个本地事务，回执查询不能新建出库。 */
public interface ErpOrderExecutionStore {
    Optional<SalesExecutionReceipt> receipt(String tenant, String id);

    SalesExecutionReceipt execute(
            FulfillmentExecutionView command, Supplier<InternalStockOutOrderDetailView> work);
}
