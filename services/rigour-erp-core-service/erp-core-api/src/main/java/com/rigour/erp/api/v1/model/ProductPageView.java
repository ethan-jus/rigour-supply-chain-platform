package com.rigour.erp.api.v1.model;

import java.util.List;

/** ERP 商品/SPU 分页查询结果。 */
public record ProductPageView(
        /** 当前租户符合查询条件的商品总数。 */
        long total,
        /** 当前页从零开始的记录偏移量。 */
        int begin,
        /** 当前请求的最大返回记录数。 */
        int step,
        /** 当前页商品列表，不返回 {@code null}。 */
        List<ProductView> items) {

    public ProductPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
