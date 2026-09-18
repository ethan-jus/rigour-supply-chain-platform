package com.rigour.integration.api.v1.model;

import java.util.List;

public record CustomerTypeListView(List<CustomerTypeView> items) {
    public CustomerTypeListView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
