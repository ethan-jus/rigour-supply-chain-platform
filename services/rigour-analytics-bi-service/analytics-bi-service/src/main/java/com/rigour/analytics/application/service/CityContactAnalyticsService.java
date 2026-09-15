package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.CityContactAnalyticsView;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.CityContactAnalyticsStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 按可信员工范围筛选提交人固定编码；未关联拜访不回退为全城业绩。 */
@Service
public final class CityContactAnalyticsService {
    private final CityContactAnalyticsStore store;
    private final BiDataScopeService scopes;
    private final Clock clock;
    public CityContactAnalyticsService(CityContactAnalyticsStore store, BiDataScopeService scopes, Clock analyticsClock) {
        this.store = store; this.scopes = scopes; this.clock = analyticsClock;
    }
    public CityContactAnalyticsView report(Instant from, Instant to, String regionCode, String ownerStaffCode) {
        var scope = scopes.resolve(regionCode, ownerStaffCode);
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? BiBusinessTime.monthStart(end) : from;
        if (start.isAfter(end)) throw new BusinessException(ErrorCode.BAD_REQUEST, "开始日期不能晚于结束日期", List.of());
        var snapshot = store.read(scope.tenantId(), start, end, scope.regionCode(), scope.ownerStaffCode());
        if (scope.ownerStaffCode() != null && !snapshot.businessLinksReady()) {
            return new CityContactAnalyticsView("OWNER_MAPPING_REQUIRED", null, start, end, List.of());
        }
        return new CityContactAnalyticsView(snapshot.syncedAt() == null ? "NOT_READY" : "READY",
                snapshot.syncedAt(), start, end, snapshot.cities());
    }
}
