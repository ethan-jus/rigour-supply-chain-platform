package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.CityProductSupplyView;
import com.rigour.analytics.api.v1.model.CityProductSupplyView.SourceStatus;
import com.rigour.analytics.application.port.out.CityProductSupplyStore;
import com.rigour.shared.context.AuthorizationContext;
import com.rigour.shared.context.AuthorizationDeniedException;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订货调查不计算猜测的周转天数或补货量；未知单位、过期状态交给业务显式核查。 */
@Service
public class CityProductSupplyService {
    private final CityProductSupplyStore store;
    private final Clock clock;
    public CityProductSupplyService(CityProductSupplyStore store, Clock analyticsClock) {
        this.store = store;
        this.clock = analyticsClock;
    }
    @Transactional(readOnly = true)
    public CityProductSupplyView supply(Instant from, Instant to, Long productId, Long warehouseId, Long skuId) {
        var caller = AuthorizationContext.requireCurrent();
        if (caller.tenantId() == null) throw new AuthorizationDeniedException("tenant-caller");
        AuthorizationContext.requirePermission("analytics:dashboard:read");
        if (from == null || to == null || from.isAfter(to) || productId == null || productId < 1
                || warehouseId == null || warehouseId < 1 || (skuId != null && skuId < 1))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择有效的期间、商品和供货仓库", List.of());
        String tenant = caller.tenantId().toString();
        Instant now = clock.instant();
        var stock = store.stocks(tenant, productId, warehouseId, skuId, 1001);
        var operations = store.operations(tenant, from, to, productId, skuId, 1001);
        boolean truncated = stock.size() > 1000 || operations.size() > 1000;
        return new CityProductSupplyView(now, from, to,
                status(store.checkpoint(tenant, "ERP_STOCK_BALANCE"), now),
                status(store.checkpoint(tenant, "ERP_INVENTORY_OPERATION"), now),
                truncated ? List.of() : stock, truncated ? List.of() : operations, truncated);
    }
    private static SourceStatus status(CityProductSupplyStore.Checkpoint checkpoint, Instant now) {
        if (checkpoint == null) return new SourceStatus("UNAVAILABLE", null);
        if ("FAILED".equals(checkpoint.status())) return new SourceStatus("FAILED", checkpoint.lastSuccessAt());
        if ("RUNNING".equals(checkpoint.status())) return new SourceStatus("RUNNING", checkpoint.lastSuccessAt());
        if (checkpoint.lastSuccessAt() == null) return new SourceStatus("UNAVAILABLE", null);
        return new SourceStatus(checkpoint.lastSuccessAt().isBefore(now.minus(24, ChronoUnit.HOURS))
                || checkpoint.lastSuccessAt().isAfter(now)
                || !"SUCCESS".equals(checkpoint.status()) ? "STALE" : "FRESH", checkpoint.lastSuccessAt());
    }
}
