package com.rigour.analytics.application.service;

import com.rigour.analytics.api.v1.model.BiComparisonView;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.BiComparisonStore;
import com.rigour.shared.core.api.ErrorCode;
import com.rigour.shared.core.exception.BusinessException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.List;
import org.springframework.stereotype.Service;

/** 校验比较期间并应用服务端员工、城市范围。 */
@Service
public final class BiComparisonService {
    private final BiComparisonStore store;
    private final BiDataScopeService scopes;
    private final Clock clock;
    public BiComparisonService(BiComparisonStore store, BiDataScopeService scopes, Clock analyticsClock) {
        this.store = store;
        this.scopes = scopes;
        this.clock = analyticsClock;
    }
    public BiComparisonView compare(Instant from, Instant to, String region, String owner,
                                    String customerType, Long category, String source) {
        var scope = scopes.resolve(region, owner);
        if (from == null || to == null || category != null) throw invalid("比较需要完整期间，且不支持整单按商品分类筛选");
        Instant start = from.truncatedTo(ChronoUnit.MICROS);
        if (start.isBefore(from)) start = start.plus(1, ChronoUnit.MICROS);
        Instant end = to.truncatedTo(ChronoUnit.MICROS);
        if (start.isAfter(end) || Duration.between(start, end).toDays() > 3660) throw invalid("比较期间无效或超过十年");
        Instant previousEnd;
        Instant previousStart;
        try {
            previousEnd = start.minus(1, ChronoUnit.MICROS);
            previousStart = previousEnd.minus(Duration.between(start, end));
        } catch (DateTimeException | ArithmeticException exception) {
            throw invalid("前期时间窗口超出支持范围");
        }
        String sourceCode = normalized(source);
        if ("DHB".equals(sourceCode)) sourceCode = "DINGHUOBAO";
        var current = new SupplyDashboardFilter(start, end, scope.regionCode(), scope.ownerStaffCode(),
                normalized(customerType), null, sourceCode);
        var previous = new SupplyDashboardFilter(previousStart, previousEnd, current.regionCode(),
                current.ownerStaffCode(), current.customerTypeCode(), null, current.sourceSystemCode());
        var data = store.compare(scope.tenantId(), current, previous);
        return new BiComparisonView(start, end, previousStart, previousEnd, clock.instant(),
                data.current(), data.previous(), data.cities());
    }
    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) throw invalid("筛选编码无效");
        return code;
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message, List.of()); }
}
