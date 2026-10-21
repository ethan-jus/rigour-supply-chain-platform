package com.rigour.erp.application.service.product;

import com.rigour.erp.api.v1.model.ErpPayrollCategoryView;
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

    public List<ErpPayrollProductView> products(String keyword, Long variant, Long categoryId) {
        return store.products(tenant(), keyword == null ? "" : keyword.trim(), variant, categoryId);
    }

    public List<ErpPayrollCategoryView> categories() {
        return store.categories(tenant());
    }

    public List<ErpPayrollProductView> productsByVariants(List<Long> ids) {
        String tenant = tenant();
        if (ids == null
                || ids.isEmpty()
                || ids.size() > 200
                || ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new com.rigour.shared.core.exception.BusinessException(
                    com.rigour.shared.core.api.ErrorCode.BAD_REQUEST, "商品规格范围无效", List.of());
        return store.productsByVariants(tenant, ids);
    }

    private String tenant() {
        var a = AuthorizationContext.requireCurrent();
        if (a.tenantId() == null || !"SERVICE".equals(a.principalScope()))
            throw new AuthorizationDeniedException("payroll-product-reference");
        AuthorizationContext.requirePermission("erp:payroll:product-read");
        return a.tenantId().toString();
    }
}
