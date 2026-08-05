package com.rigour.order.api.v1.model;

import java.util.List;

/** 订单域外部来源单据的本地分页。 */
public record DhbDocumentPageView<T>(
        /** 当前租户在本地数据库中符合过滤条件的记录总数。 */ long total,
        /** 当前偏移页的只读单据列表。 */ List<T> items) {
    public DhbDocumentPageView { items = items == null ? List.of() : List.copyOf(items); }
}
