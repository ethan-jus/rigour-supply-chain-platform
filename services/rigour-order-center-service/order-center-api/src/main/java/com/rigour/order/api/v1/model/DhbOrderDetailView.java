package com.rigour.order.api.v1.model;

import java.util.List;

/** 订单明细本地投影；外部补拉由Integration同步任务负责。 */
public record DhbOrderDetailView(
        /** 订单主信息。 */
        DhbOrderView order,
        /** 订单商品明细。 */
        List<DhbOrderLineView> lines,
        /** 订单发货信息。 */
        List<DhbShipmentView> shipments,
        /** 关联订单的收款/付款本地只读记录。 */
        List<DhbFinancialDocumentView> financialDocuments,
        /** 订单列表/详情原始报文版本。 */
        List<DhbOrderSourceRecordView> sourceRecords,
        /** 本次响应是否经过外部接口刷新；本地查询接口固定为false。 */
        boolean synchronizedFromProvider) {

    public DhbOrderDetailView {
        lines = lines == null ? List.of() : List.copyOf(lines);
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
        financialDocuments = financialDocuments == null ? List.of() : List.copyOf(financialDocuments);
        sourceRecords = sourceRecords == null ? List.of() : List.copyOf(sourceRecords);
    }

    /** 兼容旧调用方的订单详情构造方式。 */
    public DhbOrderDetailView(DhbOrderView order, List<DhbOrderLineView> lines,
                              List<DhbShipmentView> shipments, boolean synchronizedFromProvider) {
        this(order, lines, shipments, List.of(), List.of(), synchronizedFromProvider);
    }
}
