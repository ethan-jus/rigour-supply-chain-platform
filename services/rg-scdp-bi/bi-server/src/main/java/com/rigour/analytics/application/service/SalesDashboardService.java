package com.rigour.analytics.application.service;

import com.rigour.analytics.application.model.*;
import com.rigour.analytics.application.port.out.SalesDashboardStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;

import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SalesDashboardService {
    private final SalesDashboardStore store;
    private final BiDataScopeService scopes;

    public SalesDashboardService(SalesDashboardStore store, BiDataScopeService scopes) {
        this.store = store;
        this.scopes = scopes;
    }

    public SalesDashboardData query(
            Instant from,
            Instant to,
            String region,
            String owner,
            String customerType,
            Long category,
            String source) {
        var actor = AuthorizationContext.requireCurrent();
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        if (actor.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        if (from == null || to == null || from.isAfter(to))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供有效统计期间", java.util.List.of());
        if (category != null)
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST, "商品分类请在个人商品分析区域筛选", java.util.List.of());
        var scope = scopes.resolve(region, owner);
        return store.query(
                actor.tenantId().toString(),
                new SupplyDashboardFilter(
                        from,
                        to,
                        scope.regionCode(),
                        scope.ownerStaffCode(),
                        customerType,
                        null,
                        source));
    }
}
