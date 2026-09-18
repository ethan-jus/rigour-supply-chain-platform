package com.rigour.order.api.v1.model;

import java.util.List;

/** Order 自研业务分页结果。 */
public record OrderPageView<T>(
        long total,
        int begin,
        int step,
        List<T> items) {
    public OrderPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
