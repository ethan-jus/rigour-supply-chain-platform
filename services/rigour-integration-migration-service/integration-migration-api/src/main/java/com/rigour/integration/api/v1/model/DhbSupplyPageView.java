package com.rigour.integration.api.v1.model;

import java.util.List;

/** Integration 对订货宝供应链数据的归一化分页结果。 */
public record DhbSupplyPageView<T>(
        /** 符合条件的来源记录总数。 */ long total,
        /** 当前页归一化记录。 */ List<T> items) {
    public DhbSupplyPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
