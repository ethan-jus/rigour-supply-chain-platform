package com.rigour.erp.application.port.out;

import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
import com.rigour.erp.api.v1.model.ErpPayrollProductView;

import java.util.List;

public interface ErpPayrollProductStore {
    List<ErpPayrollProductView> products(
            String tenant, String keyword, Long variant, Long categoryId);

    List<ErpPayrollCategoryView> categories(String tenant);

    List<ErpPayrollProductView> productsByVariants(String tenant, List<Long> ids);
}
