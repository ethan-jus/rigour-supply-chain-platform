package com.rigour.order.api.controller;

import com.rigour.order.api.v1.OrderAuthorityProjectionApi;
import com.rigour.order.application.port.out.OrderAuthorityProjectionStore;
import com.rigour.shared.context.*;

import org.springframework.web.bind.annotation.RestController;

/** 仅输出当前调用租户的归属标识，浏览器用户不能调用服务投影接口。 */
@RestController
public final class OrderAuthorityProjectionController implements OrderAuthorityProjectionApi {
    private final OrderAuthorityProjectionStore store;

    public OrderAuthorityProjectionController(OrderAuthorityProjectionStore store) {
        this.store = store;
    }

    private String tenant() {
        var a = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(a.principalScope()) || a.tenantId() == null)
            throw new AuthorizationDeniedException("service-projection-caller");
        AuthorizationContext.requirePermission("order:analytics:projection-read");
        return a.tenantId().toString();
    }

    public Version version() {
        return new Version(store.version(tenant()));
    }

    public Page page(long afterId, int step) {
        var tenant = tenant();
        if (afterId < 0 || step < 1 || step > 1000) throw new IllegalArgumentException("投影分页范围无效");
        var p = store.page(tenant, afterId, step);
        return new Page(p.version(), p.items(), p.regions());
    }
}
