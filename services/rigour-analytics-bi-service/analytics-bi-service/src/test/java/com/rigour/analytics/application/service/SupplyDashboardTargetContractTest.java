package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.SupplyDashboardData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.TargetCompletionItem;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 目标覆盖信息完整输出，零目标或缺月份不能稀释总览的有效目标平均完成度。 */
class SupplyDashboardTargetContractTest {
    @AfterEach void clear() { TestAuthorizationContext.clear(); }

    @Test void coverageIsPreservedAndZeroOrPartialTargetsDoNotEnterAverage() throws Exception {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null,
                UUID.randomUUID(), 1, 1, 1, Set.of(), Set.of("analytics:dashboard:read")));
        var store = mock(SupplyDashboardStore.class);
        var scope = mock(BiDataScopeService.class);
        when(scope.resolve(any(), any())).thenReturn(new BiDataScopeService.ScopedSelection(tenant.toString(), null, null, true));
        var targets = List.of(
                target("A", "100", "50", "50", 2L, 2L),
                target("B", "0", "80", "0", 2L, 2L),
                target("C", "100", "100", "100", 1L, 2L));
        var fields = SupplyDashboardData.class.getRecordComponents();
        var constructor = SupplyDashboardData.class.getDeclaredConstructor(
                Arrays.stream(fields).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new));
        var data = constructor.newInstance(Arrays.stream(fields).map(field ->
                Set.of("cityTargetCompletions", "salesTargetCompletions").contains(field.getName()) ? targets : null).toArray());
        when(store.overview(any(), any())).thenReturn(data);
        var service = new SupplyDashboardQueryService(store, Clock.fixed(now, ZoneOffset.UTC), scope);
        var result = service.overview(now.minusSeconds(1), now, null, null, null, null, null);
        assertThat(result.metrics()).anySatisfy(metric -> {
            assertThat(metric.metricCode()).isEqualTo("target_achievement_rate");
            assertThat(metric.value()).isEqualByComparingTo("50");
        });
        assertThat(result.cityTargetCompletions()).hasSize(3);
        var partial = result.cityTargetCompletions().get(2);
        assertThat(partial.configuredMonthCount()).isEqualTo(1L);
        assertThat(partial.periodMonthCount()).isEqualTo(2L);
        assertThat(partial.achievementRate()).isEqualByComparingTo("100");
        assertThat(result.salesTargetCompletions().get(2).configuredMonthCount()).isEqualTo(1L);
    }

    private static TargetCompletionItem target(String code, String target, String actual, String rate,
            Long configured, Long period) {
        return new TargetCompletionItem("CITY", code, code, "SALES_AMOUNT", "销售额",
                new BigDecimal(target), new BigDecimal(actual), new BigDecimal(rate), configured, period);
    }
}
