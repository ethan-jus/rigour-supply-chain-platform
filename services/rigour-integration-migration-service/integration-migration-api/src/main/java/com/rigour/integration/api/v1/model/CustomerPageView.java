package com.rigour.integration.api.v1.model;

import java.util.List;

public record CustomerPageView(long total, List<CustomerView> items) {
    public CustomerPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
