package com.rigour.erp.api.v1.model;

import java.util.List;

/** ERP 供应链本地查询通用分页结果。 */
public record SupplyDataPageView<T>(
        /** 符合条件的总记录数。 */ long total,
        /** 本页零基起始偏移。 */ int begin,
        /** 本页请求数量。 */ int step,
        /** 本页业务记录。 */ List<T> items) {
    public SupplyDataPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
