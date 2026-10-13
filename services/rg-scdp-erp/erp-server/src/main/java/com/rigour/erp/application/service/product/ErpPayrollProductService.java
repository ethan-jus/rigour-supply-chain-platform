package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.erp.application.port.out.ErpPayrollProductStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public final class ErpPayrollProductService {
    private final ErpPayrollProductStore store;

    public ErpPayrollProductService(ErpPayrollProductStore store) {
        this.store = store;
    }

    public List<ErpPayrollProductView> products(String keyword, Long variant) {
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null || !"SERVICE".equals(a.principalScope()))
            throw new AuthorizationDeniedException("payroll-product-reference");
        AuthorizationContext.requirePermission("erp:payroll:product-read");
        return store.products(a.tenantId().toString(), keyword, variant);
    }
}
