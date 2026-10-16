package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.CostCustomerView;

import java.util.List;

public interface CostCustomerReferenceStore {
    List<CostCustomerView> customers(
            String tenant, String city, String keyword, Long id, int begin);
}
