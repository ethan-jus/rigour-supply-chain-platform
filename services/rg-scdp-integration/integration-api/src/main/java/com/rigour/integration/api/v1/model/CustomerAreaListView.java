package com.rigour.integration.api.v1.model;

import java.util.List;

public record CustomerAreaListView(List<CustomerAreaView> items) {
    public CustomerAreaListView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
