package com.rigour.analytics.application.service;

import com.rigour.analytics.api.controller.AnalyticsSupplyDashboardController;
import com.rigour.analytics.api.v1.AnalyticsSupplyDashboardApi;
import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.SupplyDashboardStore;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.OperatingAnalysisData;
import com.rigour.analytics.application.port.out.SupplyDashboardStore.RankingItem;
import com.rigour.shared.context.CallerIdentity;
import com.rigour.shared.context.TestAuthorizationContext;
import com.rigour.shared.core.web.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 经营分析 HTTP 合同、权限、查询校验和完整前期窗口回归。 */
class SupplyDashboardOperatingAnalysisTest {
    private static final UUID TENANT = UUID.fromString("019fb700-0000-7000-8000-000000000001");
    private static final UUID USER = UUID.fromString("019fb700-0000-7000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-09-12T08:00:00Z");
    private static final String PATH = AnalyticsSupplyDashboardApi.OPERATING_ANALYSIS_PATH;
    private SupplyDashboardStore store;
    private SupplyDashboardQueryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(SupplyDashboardStore.class);
        when(store.operatingAnalysis(any(), any(), any()))
                .thenReturn(new OperatingAnalysisData(null, null, null, null));
        var scopes = new BiDataScopeService(mock(com.rigour.analytics.application.port.out.BiDataScopeStore.class), Clock.fixed(NOW, ZoneOffset.UTC), mock(BiDataScopeRenewer.class));
        service = new SupplyDashboardQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC), scopes);
        mvc = MockMvcBuilders.standaloneSetup(new AnalyticsSupplyDashboardController(service, null, null, null, scopes))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        TestAuthorizationContext.set(caller("analytics:dashboard:read"));
    }

    @AfterEach
    void clearContext() {
        TestAuthorizationContext.clear();
    }

    @Test
    void endpointUsesOverviewQueryAndReturnsIsoTimesAndEmptyArrays() throws Exception {
        mvc.perform(get(PATH).param("from", "2026-09-01T00:00:00Z")
                        .param("to", "2026-09-10T23:59:59.999999Z")
                        .param("regionCode", " bj ").param("ownerStaffCode", " S1 ")
                        .param("customerTypeCode", " store ").param("sourceSystemCode", " dhb "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(9))
                .andExpect(jsonPath("$.data.from").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.to").value("2026-09-10T23:59:59.999999Z"))
                .andExpect(jsonPath("$.data.generatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.data.previousFrom").value("2026-08-22T00:00:00Z"))
                .andExpect(jsonPath("$.data.previousTo").value("2026-08-31T23:59:59.999999Z"))
                .andExpect(jsonPath("$.data.previousSalesRanking").isEmpty())
                .andExpect(jsonPath("$.data.cityProducts").isEmpty())
                .andExpect(jsonPath("$.data.cityCustomers").isEmpty())
                .andExpect(jsonPath("$.data.salesReceipts").isEmpty());

        ArgumentCaptor<SupplyDashboardFilter> current = ArgumentCaptor.forClass(SupplyDashboardFilter.class);
        ArgumentCaptor<SupplyDashboardFilter> previous = ArgumentCaptor.forClass(SupplyDashboardFilter.class);
        verify(store).operatingAnalysis(eq(TENANT.toString()), current.capture(), previous.capture());
        for (SupplyDashboardFilter filter : List.of(current.getValue(), previous.getValue())) {
            assertThat(filter.regionCode()).isEqualTo("BJ");
            assertThat(filter.ownerStaffCode()).isEqualTo("S1");
            assertThat(filter.customerTypeCode()).isEqualTo("STORE");
            assertThat(filter.sourceSystemCode()).isEqualTo("DINGHUOBAO");
            assertThat(filter.productCategoryId()).isNull();
        }
    }

    @Test
    void serializesExactRowFieldsAndKeepsRankingAmountsWhileRemovingSyntheticDimensions() throws Exception {
        when(store.operatingAnalysis(any(), any(), any())).thenReturn(new OperatingAnalysisData(
                List.of(ranking("S1", "MULTI"), ranking("S2", "UNKNOWN"), ranking("S3", "BJ"),
                        ranking("UNKNOWN", "BJ"), ranking("MULTI", "BJ"), ranking(" ", "BJ")),
                List.of(new SupplyDashboardStore.CityProductItem("BJ", "北京", "10", "饮品",
                        new BigDecimal("123.45"), 2L, 1L)),
                List.of(new SupplyDashboardStore.CityCustomerItem("BJ", "北京", 2L, 1L)),
                List.of(new SupplyDashboardStore.SalesReceiptItem("S1", "销售一", new BigDecimal("32.10"), 2L, 1L))));
        mvc.perform(get(PATH).param("from", "2026-09-01T00:00:00Z").param("to", NOW.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previousSalesRanking.length()").value(3))
                .andExpect(jsonPath("$.data.previousSalesRanking[0].dimensionCode").value("S1"))
                .andExpect(jsonPath("$.data.previousSalesRanking[0].regionCode").isEmpty())
                .andExpect(jsonPath("$.data.previousSalesRanking[0].regionName").isEmpty())
                .andExpect(jsonPath("$.data.previousSalesRanking[0].salesAmount").value(100))
                .andExpect(jsonPath("$.data.previousSalesRanking[0].paidAmount").value(40))
                .andExpect(jsonPath("$.data.previousSalesRanking[0].unpaidAmount").value(60))
                .andExpect(jsonPath("$.data.previousSalesRanking[0].rate").value(40))
                .andExpect(jsonPath("$.data.previousSalesRanking[2].regionCode").value("BJ"))
                .andExpect(jsonPath("$.data.cityProducts[0].length()").value(7))
                .andExpect(jsonPath("$.data.cityProducts[0].categoryCode").value("10"))
                .andExpect(jsonPath("$.data.cityProducts[0].salesAmount").value(123.45))
                .andExpect(jsonPath("$.data.cityProducts[0].orderCount").value(2))
                .andExpect(jsonPath("$.data.cityProducts[0].customerCount").value(1))
                .andExpect(jsonPath("$.data.cityCustomers[0].length()").value(4))
                .andExpect(jsonPath("$.data.cityCustomers[0].orderingCustomerCount").value(2))
                .andExpect(jsonPath("$.data.cityCustomers[0].repeatCustomerCount").value(1))
                .andExpect(jsonPath("$.data.salesReceipts[0].length()").value(5))
                .andExpect(jsonPath("$.data.salesReceipts[0].ownerStaffCode").value("S1"))
                .andExpect(jsonPath("$.data.salesReceipts[0].paidAmount").value(32.10))
                .andExpect(jsonPath("$.data.salesReceipts[0].paymentCount").value(2))
                .andExpect(jsonPath("$.data.salesReceipts[0].customerCount").value(1));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 10})
    void rejectsEveryNonNullCategoryBeforeReadingFacts(long category) throws Exception {
        mvc.perform(get(PATH).param("productCategoryId", Long.toString(category)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("productCategoryId")));
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @MethodSource("invalidFilters")
    void rejectsInvalidQueryWithoutAggregating(String name, String value) throws Exception {
        mvc.perform(get(PATH).param("from", "2026-09-01T00:00:00Z").param("to", NOW.toString())
                        .param(name, value))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verifyNoInteractions(store);
    }

    static Stream<Arguments> invalidFilters() {
        return Stream.of(Arguments.of("regionCode", "bad-code"),
                Arguments.of("ownerStaffCode", "x".repeat(51)),
                Arguments.of("customerTypeCode", "bad type"),
                Arguments.of("sourceSystemCode", "bad-source"));
    }

    @Test
    void rejectsReversedWindow() throws Exception {
        mvc.perform(get(PATH).param("from", NOW.plusSeconds(1).toString()).param("to", NOW.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("from不能晚于to"));
        verifyNoInteractions(store);
    }

    @Test
    void deniesMissingCallerMissingPermissionAndPlatformCallerBeforeAnyQuery() throws Exception {
        TestAuthorizationContext.clear();
        mvc.perform(get(PATH)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_FORBIDDEN"));
        TestAuthorizationContext.set(caller("analytics:dashboard:refresh"));
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        TestAuthorizationContext.set(new CallerIdentity("PLATFORM", USER, null, null, USER,
                UUID.randomUUID(), 0, 0, 0, Set.of(), Set.of("*:*:*")));
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void unexpectedStoreFailureUsesExistingErrorEnvelopeWithoutLeakingSql() throws Exception {
        when(store.operatingAnalysis(any(), any(), any())).thenThrow(new IllegalStateException("private SQL detail"));
        mvc.perform(get(PATH).param("from", "2026-09-01T00:00:00Z").param("to", NOW.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2024-03-01T00:00:00Z", "2026-01-01T00:00:00Z"})
    void previousWindowIsCompleteEqualAndAdjacentAcrossMonthAndYear(String start) {
        Instant from = Instant.parse(start);
        Instant to = from.plus(10, ChronoUnit.DAYS).minus(1, ChronoUnit.MICROS);
        var result = service.operatingAnalysis(from, to, null, null, null, null, null);
        assertThat(result.previousFrom()).isEqualTo(from.minus(10, ChronoUnit.DAYS));
        assertThat(result.previousTo()).isEqualTo(from.minus(1, ChronoUnit.MICROS));
        assertThat(Duration.between(result.previousFrom(), result.previousTo()))
                .isEqualTo(Duration.between(result.from(), result.to()));
    }

    @Test
    void singleMicrosecondWindowHasASingleMicrosecondPredecessor() {
        var result = service.operatingAnalysis(NOW, NOW, null, null, null, null, null);
        assertThat(result.previousFrom()).isEqualTo(NOW.minus(1, ChronoUnit.MICROS));
        assertThat(result.previousTo()).isEqualTo(result.previousFrom());
    }

    @Test
    void fractionalBoundsAreRoundedInwardToFactPrecision() {
        var result = service.operatingAnalysis(NOW.plusNanos(1), NOW.plusNanos(2999), null, null, null, null, null);
        assertThat(result.from()).isEqualTo(NOW.plusNanos(1000));
        assertThat(result.to()).isEqualTo(NOW.plusNanos(2000));
        assertThat(result.previousFrom()).isEqualTo(NOW.minusNanos(1000));
        assertThat(result.previousTo()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @MethodSource("latestBusinessMonths")
    void defaultsUseSameTenantLatestFactAndShanghaiMonthStartAsOverview(Instant latest, Instant expectedStart) {
        when(store.latestSalesOrderDate(TENANT.toString())).thenReturn(Optional.of(latest));
        var result = service.operatingAnalysis(null, null, null, null, null, null, null);
        assertThat(result.to()).isEqualTo(latest);
        assertThat(result.from()).isEqualTo(expectedStart);
        assertThat(result.previousTo()).isEqualTo(expectedStart.minus(1, ChronoUnit.MICROS));
        assertThat(Duration.between(result.previousFrom(), result.previousTo()))
                .isEqualTo(Duration.between(expectedStart, latest));
        var current = ArgumentCaptor.forClass(SupplyDashboardFilter.class);
        verify(store).operatingAnalysis(eq(TENANT.toString()), current.capture(), any());
        assertThat(current.getValue().from()).isEqualTo(expectedStart);
        assertThat(current.getValue().to()).isEqualTo(latest);
        verify(store).latestSalesOrderDate(TENANT.toString());
    }

    private static Stream<Arguments> latestBusinessMonths() {
        return Stream.of(
                Arguments.of(Instant.parse("2026-08-16T04:05:06Z"), Instant.parse("2026-07-31T16:00:00Z")),
                Arguments.of(Instant.parse("2026-08-31T15:59:59.999999Z"), Instant.parse("2026-07-31T16:00:00Z")),
                Arguments.of(Instant.parse("2026-08-31T16:00:00Z"), Instant.parse("2026-08-31T16:00:00Z")));
    }

    @Test
    void emptyTenantDefaultsToClockAndWildcardPermissionIsPreserved() {
        TestAuthorizationContext.set(caller("*:*:*"));
        var result = service.operatingAnalysis(null, null, " ", " ", " ", null, " ");
        assertThat(result.to()).isEqualTo(NOW);
        assertThat(result.from()).isEqualTo(Instant.parse("2026-08-31T16:00:00Z"));
        var current = ArgumentCaptor.forClass(SupplyDashboardFilter.class);
        verify(store).operatingAnalysis(eq(TENANT.toString()), current.capture(), any());
        assertThat(current.getValue().from()).isEqualTo(result.from());
        assertThat(current.getValue().regionCode()).isNull();
        assertThat(current.getValue().ownerStaffCode()).isNull();
    }

    private static RankingItem ranking(String owner, String region) {
        return new RankingItem("SALES_OWNER", owner, owner, region, region,
                new BigDecimal("100"), new BigDecimal("40"), new BigDecimal("60"), 2L, 1L, new BigDecimal("40"));
    }

    private static CallerIdentity caller(String... permissions) {
        return new CallerIdentity("TENANT", USER, TENANT, USER, null, UUID.randomUUID(),
                0, 0, 0, Set.of("TENANT_SUPER_ADMIN"), Set.of(permissions));
    }
}
