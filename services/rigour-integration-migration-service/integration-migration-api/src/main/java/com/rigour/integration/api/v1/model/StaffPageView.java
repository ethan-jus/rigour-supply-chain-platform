package com.rigour.integration.api.v1.model;

import java.util.List;

public record StaffPageView(long total, List<StaffView> items) {
    public StaffPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
