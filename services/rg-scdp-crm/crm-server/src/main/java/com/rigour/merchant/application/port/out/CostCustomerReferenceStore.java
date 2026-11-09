package com.rigour.merchant.application.port.out;

import com.rigour.merchant.api.v1.model.CostCustomerView;

import java.util.List;

public interface CostCustomerReferenceStore {
    default List<CostCustomerView> customersByCodes(
            String tenant, String city, List<String> codes) {
        throw new UnsupportedOperationException("客户批量引用尚未接入");
    }

    List<CostCustomerView> customers(
            String tenant, String city, String keyword, Long id, int begin);
}
