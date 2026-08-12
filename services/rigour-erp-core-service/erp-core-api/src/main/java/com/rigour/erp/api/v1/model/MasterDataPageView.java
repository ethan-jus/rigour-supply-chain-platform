package com.rigour.erp.api.v1.model;

import java.util.List;

/** 分类、品牌、规格和标签共用的本地主数据分页结果。 */
public record MasterDataPageView<T>(
        /** 当前租户符合查询条件的主数据总数。 */
        long total,
        /** 当前页从零开始的记录偏移量。 */
        int begin,
        /** 当前请求的最大返回记录数。 */
        int step,
        /** 当前页主数据列表，不返回 {@code null}。 */
        List<T> items) {

    public MasterDataPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
