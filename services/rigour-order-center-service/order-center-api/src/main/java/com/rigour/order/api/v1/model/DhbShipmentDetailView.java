package com.rigour.order.api.v1.model;

import java.util.List;

/** 发货单主信息与商品明细。 */
public record DhbShipmentDetailView(
        /** 发货单本地主信息。 */ DhbShipmentDocumentView shipment,
        /** 已落库商品明细；尚未同步详情时为空列表。 */ List<DhbShipmentLineView> lines) {
    public DhbShipmentDetailView { lines = lines == null ? List.of() : List.copyOf(lines); }
}
