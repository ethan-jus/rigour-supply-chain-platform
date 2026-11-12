package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.PaymentFilterApi.Catalog;
import com.rigour.shared.context.CallerIdentity;

public interface PaymentFilterCatalogClient {
    Catalog catalog(CallerIdentity caller);
}
