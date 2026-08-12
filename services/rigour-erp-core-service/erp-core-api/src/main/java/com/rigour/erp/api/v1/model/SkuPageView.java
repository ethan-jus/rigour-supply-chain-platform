package com.rigour.erp.api.v1.model;

import java.util.List;

/** ERP SKU 分页查询结果。 */
public record SkuPageView(
        /** 当前租户符合查询条件的 SKU 总数。 */
        long total,
        /** 当前页从零开始的记录偏移量。 */
        int begin,
        /** 当前请求的最大返回记录数。 */
        int step,
        /** 当前页 SKU 列表，不返回 {@code null}。 */
        List<SkuView> items) {

    public SkuPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
