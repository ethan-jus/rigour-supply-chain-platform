package com.rigour.analytics.application.service;

import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.*;
import com.rigour.shared.context.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 已按城市隔离的真实成本可用，员工范围和更细筛选不能误用城市总成本。 */
class SupplyDashboardRestrictedOverviewTest {
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private final SupplyDashboardStore store = mock(SupplyDashboardStore.class);
    private final BiDataScopeService scopes = mock(BiDataScopeService.class);
    private final SupplyDashboardQueryService service = new SupplyDashboardQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC), scopes);
    private final UUID tenant = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        UUID user = UUID.randomUUID();
        TestAuthorizationContext.set(new CallerIdentity("TENANT", user, tenant, user, null, UUID.randomUUID(), 1, 1, 1, Set.of(), Set.of("analytics:dashboard:read")));
        when(store.overview(any(), any())).thenReturn(data());
        when(scopes.resolve(any(), any())).thenAnswer(call -> new BiDataScopeService.ScopedSelection(tenant.toString(), "BJ", call.getArgument(1), false));
    }
    @AfterEach void clear() { TestAuthorizationContext.clear(); }
    @Test void wholeCityRetainsItsRealCostAndScopedCityAndSalesTargets() {
        var result = service.overview(NOW, NOW, "BJ", null, null, null, null);
        assertThat(result.cityCostTrend()).hasSize(1);
        assertThat(result.cityCostRanking()).singleElement().satisfies(cost -> assertThat(cost.regionCode()).isEqualTo("BJ"));
        assertThat(result.metrics()).anySatisfy(metric -> {
            assertThat(metric.metricCode()).isEqualTo("city_cost_amount");
            assertThat(metric.value()).isEqualByComparingTo(AMOUNT);
        });
        assertThat(result.cityTargetCompletions()).hasSize(1);
        assertThat(result.salesTargetCompletions()).singleElement().satisfies(target -> assertThat(target.dimensionCode()).isEqualTo("E1"));
        assertThat(result.collectionTrend()).hasSize(1);
    }
    @Test void selfOrGranularFilterCannotExposeCitywideCostOrCityTargets() {
        for (String[] selection : List.of(new String[]{"E1", null, null}, new String[]{null, "STORE", null}, new String[]{null, null, "FEISHU"})) {
            var result = service.overview(NOW, NOW, "BJ", selection[0], selection[1], null, selection[2]);
            assertThat(result.cityCostTrend()).isEmpty();
            assertThat(result.cityCostRanking()).isEmpty();
            assertThat(result.cityTargetCompletions()).isEmpty();
            assertThat(result.metrics()).noneSatisfy(metric -> assertThat(metric.metricCode()).isEqualTo("city_cost_amount"));
            if (selection[0] != null) assertThat(result.collectionTrend()).isEmpty();
        }
        assertThat(service.overview(NOW, NOW, "BJ", null, null, 1L, null).cityCostRanking()).isEmpty();
    }
    @Test void selfCanUseScopedCustomerHistoryAndItsCompleteTarget() {
        var result = service.overview(NOW, NOW, "BJ", "E1", null, null, null);
        assertThat(result.customerSegments()).hasSize(1);
        assertThat(result.customerActivityRanking()).hasSize(1);
        assertThat(result.customerChurnRiskRanking()).hasSize(1);
        assertThat(result.salesTargetCompletions()).hasSize(1);
        assertThat(result.metrics()).anySatisfy(metric -> {
            assertThat(metric.metricCode()).isEqualTo("target_achievement_rate");
            assertThat(metric.value()).isEqualByComparingTo("50");
        });
    }
    @Test void unsupportedGranularComparisonsDoNotPretendToHaveCategoryCustomerHistoryOrProratedTargets() {
        var category = service.overview(NOW, NOW, "BJ", "E1", null, 1L, null);
        assertThat(category.customerActivityRanking()).isEmpty();
        assertThat(category.customerChurnRiskRanking()).isEmpty();
        assertThat(category.customerSegments()).isEmpty();
        assertThat(category.salesTargetCompletions()).isEmpty();
        assertThat(service.overview(NOW, NOW, "BJ", "E1", "STORE", null, null).salesTargetCompletions()).isEmpty();
        assertThat(service.overview(NOW, NOW, "BJ", "E1", null, null, "FEISHU").salesTargetCompletions()).isEmpty();
    }
    private static SupplyDashboardData data() throws Exception {
        var customer = new CustomerActivityItem("C1", "当前客户", "BJ", "北京", "E1", "本人销售", "STORE", "门店", "A", "A级客户",
                AMOUNT, AMOUNT, BigDecimal.ZERO, 1L, 1L, NOW, NOW, 60L, new BigDecimal("50"), "HIGH");
        var values = Map.<String, Object>of(
                "cityCost", new CityCostSummary(1L, AMOUNT, AMOUNT, NOW),
                "cityCostTrend", List.of(new TrendPoint("city_cost_amount", "2026-09-12", AMOUNT, AMOUNT)),
                "collectionTrend", List.of(new TrendPoint("receipt_amount", "2026-09-12", AMOUNT, AMOUNT)),
                "cityCostRanking", List.of(new CityCostItem("BJ", "北京", AMOUNT, AMOUNT, BigDecimal.ZERO, AMOUNT, AMOUNT, 1L, NOW)),
                "cityTargetCompletions", List.of(new TargetCompletionItem("CITY", "BJ", "北京", "SALES_AMOUNT", "销售额", AMOUNT, AMOUNT, AMOUNT)),
                "salesTargetCompletions", List.of(new TargetCompletionItem("SALES_OWNER", "E1", "本人销售", "SALES_AMOUNT", "销售额", AMOUNT, new BigDecimal("50"), new BigDecimal("50"))),
                "customerSegments", List.of(new CustomerSegmentItem("A", "A级客户", 1L, AMOUNT, AMOUNT, BigDecimal.ZERO, new BigDecimal("50"), 1L)),
                "customerActivityRanking", List.of(customer), "customerChurnRiskRanking", List.of(customer));
        var fields = SupplyDashboardData.class.getRecordComponents();
        return SupplyDashboardData.class.getDeclaredConstructor(Arrays.stream(fields).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new))
                .newInstance(Arrays.stream(fields).map(field -> values.get(field.getName())).toArray());
    }
}
