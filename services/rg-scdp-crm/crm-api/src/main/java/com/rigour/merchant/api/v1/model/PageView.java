package com.rigour.merchant.api.v1.model;

import java.util.List;

public record PageView<T>(long total, int begin, int step, List<T> items) {
    public PageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
