package com.rigour.order.domain.model.order;

import java.util.List;

/** 外部订单经过防腐层转换后的导入批次；业务层不再处理订货宝 JsonNode。 */
public record ImportedOrder(
        /** 已完成防腐转换的内部订单主模型。 */
        Order order,
        /** 订单明细；仅getOrderContent成功时有值。 */
        List<OrderLine> lines,
        /** 发货信息；仅getOrderContent成功时有值。 */
        List<OrderShipment> shipments,
        /** getOrderList原始响应中的单订单摘要。 */
        String rawListPayload,
        /** getOrderContent原始响应。 */
        String rawDetailPayload,
        /** 本次来源报文内容哈希。 */
        String payloadHash,
        /** 是否包含订单明细，决定是否替换内部明细和发货子表。 */
        boolean detailIncluded) {

    public ImportedOrder {
        lines = lines == null ? List.of() : List.copyOf(lines);
        shipments = shipments == null ? List.of() : List.copyOf(shipments);
    }
}
