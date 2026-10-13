package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpPayrollProductView;

import java.util.List;

public interface ErpPayrollProductStore {
    List<ErpPayrollProductView> products(String tenant, String keyword, Long variant);
}
