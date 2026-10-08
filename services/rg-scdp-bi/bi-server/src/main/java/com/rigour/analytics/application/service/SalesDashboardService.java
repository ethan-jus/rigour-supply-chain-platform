package com.rigour.analytics.application.service;

import com.rigour.analytics.application.model.*;
import com.rigour.analytics.application.port.out.SalesDashboardStore;
import com.rigour.analytics.application.port.out.BiProductImages;
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
    private final BiProductImages images;

    public SalesDashboardService(SalesDashboardStore store, BiDataScopeService scopes, BiProductImages images) {
        this.store = store;
        this.scopes = scopes;
        this.images = images;
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
        var data = store.query(
                actor.tenantId().toString(),
                new SupplyDashboardFilter(
                        from,
                        to,
                        scope.regionCode(),
                        scope.ownerStaffCode(),
                        customerType,
                        null,
                        source));
        if (data.products().isEmpty()) return data;
        var urls = images.urls(actor.tenantId().toString(), data.products().stream()
                .map(SalesDashboardData.Product::productId).distinct().toList());
        var products = data.products().stream().map(p -> new SalesDashboardData.Product(
                p.categoryId(), p.category(), p.productId(), p.product(), p.sku(), p.quantity(),
                p.sales(), p.received(), p.receipts(), p.allocated(), urls.get(p.productId()))).toList();
        return new SalesDashboardData(data.people(), data.goals(), data.history(), products,
                data.customers(), data.months(), data.receiptSplit(), data.productSyncedAt(), data.dailyReceipts());
    }
}
