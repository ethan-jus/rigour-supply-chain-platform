package com.rigour.order.api.v1.model;

import java.util.Set;
import java.util.UUID;

/** Order Center 完成外部查询和本地业务落库后的同步结果。 */
public record DhbOrderSyncResult(
        /** Integration 同步运行 UUID。 */ UUID runId,
        /** ORDER、SHIPMENT、RETURN、RECEIPT、PAYMENT 或 ORDER_DOMAIN。 */ String objectType,
        /** 外部查询与本地落库均完成时固定为 SUCCEEDED。 */ String status,
        /** 订货宝订单、出库/发货、退货、收款和付款列表接口返回记录数之和，不重复统计详情。 */ long fetched,
        /** 五类业务数据实际新增或内容变化数量之和。 */ int changed,
        /** 本次实际新增或内容发生变化的订单数量。 */ int ordersChanged,
        /** 本次实际新增或内容发生变化的发货单数量。 */ int shipmentsChanged,
        /** 本次实际新增或内容发生变化的getWaitShips物流快照数量。 */ int shipmentLogisticsChanged,
        /** 本次实际新增或内容发生变化的退货单数量。 */ int returnsChanged,
        /** 本次实际新增或内容发生变化的收款单和付款单总数量。 */ int financialDocumentsChanged,
        /** 已完成拉取及落库的对象集合。 */ Set<String> completedObjects) {

    public DhbOrderSyncResult {
        completedObjects = completedObjects == null ? Set.of() : Set.copyOf(completedObjects);
        changed = ordersChanged + shipmentsChanged + shipmentLogisticsChanged
                + returnsChanged + financialDocumentsChanged;
    }

    /** 兼容旧版四类单据同步结果构造方式。 */
    public DhbOrderSyncResult(UUID runId, String objectType, String status, long fetched, int changed,
                              int ordersChanged, int shipmentsChanged, int returnsChanged,
                              int financialDocumentsChanged, Set<String> completedObjects) {
        this(runId, objectType, status, fetched, changed, ordersChanged, shipmentsChanged, 0,
                returnsChanged, financialDocumentsChanged, completedObjects);
    }

}
