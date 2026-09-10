package com.rigour.hr.api.v1.model;

import java.util.List;

/** HR 分页视图；与 ERP 主数据分页模型保持同样的 begin/step 语义。 */
public record HrPageView<T>(
        long total,
        int begin,
        int step,
        List<T> items) {
    public HrPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
