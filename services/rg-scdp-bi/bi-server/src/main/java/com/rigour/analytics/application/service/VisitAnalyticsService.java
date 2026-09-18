package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.VisitAnalyticsView;
import com.rigour.analytics.application.model.BiBusinessTime;
import com.rigour.analytics.application.port.out.VisitAnalyticsStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;

/** 拜访看板用例；先收紧权限，再按北京时间日期统计，限制趋势跨度防止超大响应。 */
@Service
public final class VisitAnalyticsService {
    private final VisitAnalyticsStore store;
    private final BiDataScopeService scopes;
    private final Clock clock;
    public VisitAnalyticsService(VisitAnalyticsStore store, BiDataScopeService scopes, Clock analyticsClock) {
        this.store = store; this.scopes = scopes; this.clock = analyticsClock;
    }
    public VisitAnalyticsView report(Instant from, Instant to, String regionCode, String employeeCode) {
        var scope = scopes.resolve(regionCode, employeeCode);
        var end = to == null ? clock.instant() : to;
        var start = from == null ? BiBusinessTime.monthStart(end) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start.atZone(BiBusinessTime.ZONE).toLocalDate(),
                end.atZone(BiBusinessTime.ZONE).toLocalDate()) > 1095) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择开始不晚于结束且不超过三年的日期范围", List.of());
        }
        return store.read(scope.tenantId(), start, end, scope.regionCode(), scope.ownerStaffCode());
    }
}
