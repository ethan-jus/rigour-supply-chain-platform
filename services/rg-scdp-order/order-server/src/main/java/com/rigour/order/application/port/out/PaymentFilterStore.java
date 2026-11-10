package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.PaymentFilterApi.Parties;

public interface PaymentFilterStore {
    Parties parties(String tenant);
}
