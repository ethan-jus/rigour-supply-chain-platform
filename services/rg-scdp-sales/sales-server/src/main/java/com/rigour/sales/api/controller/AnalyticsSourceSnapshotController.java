package com.rigour.sales.api.controller;

import com.rigour.sales.api.v1.AnalyticsSourceSnapshotApi;
import com.rigour.sales.application.port.out.AnalyticsSourceSnapshotStore;
import com.rigour.shared.context.*;

import org.springframework.web.bind.annotation.RestController;

/** 浏览器身份无法直接读取源数据；只允许携带专用权限的受信租户服务。 */
@RestController
public final class AnalyticsSourceSnapshotController implements AnalyticsSourceSnapshotApi {
    private final AnalyticsSourceSnapshotStore store;

    public AnalyticsSourceSnapshotController(AnalyticsSourceSnapshotStore store) {
        this.store = store;
    }

    private String tenant() {
        var a = AuthorizationContext.requireCurrent();
        if (!"SERVICE".equals(a.principalScope()) || a.tenantId() == null)
            throw new AuthorizationDeniedException("service-source-snapshot-caller");
        AuthorizationContext.requirePermission("sales:analytics:source-read");
        return a.tenantId().toString();
    }

    public Version version(String dataset) {
        return new Version(store.version(tenant(), dataset));
    }

    public Page page(String dataset, String after) {
        var p = store.page(tenant(), dataset, after);
        return new Page(p.version(), p.items());
    }
}
