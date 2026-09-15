package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.CustomerAttributeAnalyticsView;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.CustomerAttributeAnalyticsStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 当前客户属性与期间订单统计，先验证城市/本人权限，再读取本地快照。 */
@Service
public class CustomerAttributeAnalyticsService {
    private final CustomerAttributeAnalyticsStore store;
    private final BiDataScopeService scopes;
    private final Clock clock;

    public CustomerAttributeAnalyticsService(CustomerAttributeAnalyticsStore store, BiDataScopeService scopes,
            Clock analyticsClock) { this.store = store; this.scopes = scopes; this.clock = analyticsClock; }

    public CustomerAttributeAnalyticsView report(Instant from, Instant to, String regionCode, String employeeCode) {
        var scope = scopes.resolve(regionCode, employeeCode);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? BiBusinessTime.monthStart(end) : from;
        if (start.isAfter(end)) throw new BusinessException(ErrorCode.BAD_REQUEST, "开始时间不能晚于结束时间", List.of());
        var data = store.read(scope.tenantId(), start, end, scope.regionCode(), scope.ownerStaffCode());
        return new CustomerAttributeAnalyticsView(data.syncedAt() == null ? "NOT_READY"
                : data.sources().isEmpty() ? "EMPTY" : "READY", data.syncedAt(), start, end,
                data.sources(), data.businessCategories());
    }
}
