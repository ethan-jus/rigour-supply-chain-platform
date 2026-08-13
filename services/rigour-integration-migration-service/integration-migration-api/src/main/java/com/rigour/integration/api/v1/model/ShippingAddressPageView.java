package com.rigour.integration.api.v1.model;

import java.util.List;

public record ShippingAddressPageView(long total, List<ShippingAddressView> items) {
    public ShippingAddressPageView {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
