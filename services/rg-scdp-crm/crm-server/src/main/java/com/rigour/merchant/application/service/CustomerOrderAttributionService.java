package com.rigour.merchant.application.service;

import com.rigour.merchant.api.v1.model.CustomerOrderAttributionView;
import com.rigour.merchant.application.port.out.CustomerOrderAttributionStore;
import com.rigour.shared.context.*;

import org.springframework.stereotype.Service;

/** 仅给有明确内部权限的服务返回客户主责与 HR 组织，不返回联系方式。 */
@Service
public final class CustomerOrderAttributionService {
    private final CustomerOrderAttributionStore store;

    public CustomerOrderAttributionService(CustomerOrderAttributionStore store) {
        this.store = store;
    }

    public CustomerOrderAttributionView read(long id) {
        var c = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(c.principalScope()) || c.tenantId() == null)
            throw new AuthorizationDeniedException("service-caller");
        AuthorizationContext.requirePermission("crm:customer:attribution-read");
        if (id < 1) throw new IllegalArgumentException("客户 ID 无效");
        return store.read(c.tenantId().toString(), id);
    }
}
