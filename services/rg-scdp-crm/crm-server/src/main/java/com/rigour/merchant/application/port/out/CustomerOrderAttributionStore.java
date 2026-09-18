package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;

/** CRM 是客户当前经营归属的唯一提供者。 */
public interface CustomerOrderAttributionStore {
    CustomerOrderAttributionView read(String tenant, long customerId);
}
