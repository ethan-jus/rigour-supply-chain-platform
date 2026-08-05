package com.rigour.order.api.v1.model;

import java.util.List;

/** 退货单主信息与商品明细。 */
public record DhbReturnDetailView(
        /** 退货单本地主信息。 */ DhbReturnDocumentView returnDocument,
        /** 已落库商品明细；尚未同步详情时为空列表。 */ List<DhbReturnLineView> lines) {
    public DhbReturnDetailView { lines = lines == null ? List.of() : List.copyOf(lines); }
}
