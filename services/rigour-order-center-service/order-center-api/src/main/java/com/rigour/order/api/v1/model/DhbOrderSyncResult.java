package com.rigour.order.api.v1.model;

import java.util.Set;
import java.util.UUID;

/** Order Center 完成外部查询和本地业务落库后的同步结果。 */
public record DhbOrderSyncResult(
        /** Integration 同步运行 UUID。 */ UUID runId,
        /** ORDER、SHIPMENT、RETURN、RECEIPT、PAYMENT 或 ORDER_DOMAIN。 */ String objectType,
        /** 外部查询与本地落库均完成时固定为 SUCCEEDED。 */ String status,
        /** 订货宝列表接口实际返回记录数，不重复统计详情。 */ long fetched,
        /** 四类业务数据实际新增或内容变化数量之和。 */ int changed,
        /** 本次实际新增或内容发生变化的订单数量。 */ int ordersChanged,
        /** 本次实际新增或内容发生变化的发货单数量。 */ int shipmentsChanged,
        /** 本次实际新增或内容发生变化的退货单数量。 */ int returnsChanged,
        /** 本次实际新增或内容发生变化的收款单和付款单总数量。 */ int financialDocumentsChanged,
        /** 已完成拉取及落库的对象集合。 */ Set<String> completedObjects) {

    public DhbOrderSyncResult {
        completedObjects = completedObjects == null ? Set.of() : Set.copyOf(completedObjects);
        changed = ordersChanged + shipmentsChanged + returnsChanged + financialDocumentsChanged;
    }

}
