package com.rigour.integration.api.v1.model;

import java.util.List;

/** 订货宝 batchGetStock 归一化库存结果。 */
public record DhbInventoryView(
        /** 按仓库、商品和规格展开的库存余额。 */ List<DhbInventoryBalanceView> items) {
    public DhbInventoryView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
