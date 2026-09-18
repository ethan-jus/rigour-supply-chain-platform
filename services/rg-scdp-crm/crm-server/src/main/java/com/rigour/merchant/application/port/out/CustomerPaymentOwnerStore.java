package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.CustomerPaymentOwnerView;

import java.time.Instant;

/** 只查询CRM主责生效历史的本域端口。 */
public interface CustomerPaymentOwnerStore {
    CustomerPaymentOwnerView at(String tenant, long customer, Instant at);
}
