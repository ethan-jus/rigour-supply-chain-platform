package com.rigour.analytics.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.rigour.analytics.application.model.SupplyDashboardFilter;
import com.rigour.analytics.application.port.out.HrTargetClient;
import com.rigour.analytics.infrastructure.persistence.mapper.SupplyDashboardQueryMapper;
import com.rigour.hr.api.v1.model.TargetSettingsModels.Target;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

class HrDashboardTargetsTest {
    @Test
    void apiValuesDriveMonthlyGoalsAndPeriodAttainmentWithoutAnyTargetTable() {
        var client = mock(HrTargetClient.class);
        var mapper = mock(SupplyDashboardQueryMapper.class);
        var targets = new HrDashboardTargets(client, mock(javax.sql.DataSource.class), mapper);
        var values = new ArrayList<Target>();
        for (var month : List.of("2026-01", "2026-02"))
            for (var metric :
                    List.of("SALES_AMOUNT", "RECEIPT_AMOUNT", "NEW_CUSTOMER", "REPEAT_CUSTOMER"))
                values.add(
                        new Target(
                                month,
                                "CITY",
                                "BJ",
                                "北京",
                                metric,
                                month.endsWith("01") ? BigDecimal.ZERO : new BigDecimal("321.25"),
                                1));
        when(client.values("T", "2026-01", "2026-12")).thenReturn(values);
        var goals = targets.cityGoals("T", 2026, null);
        assertThat(goals).hasSize(2);
        assertThat(goals.getFirst().salesTarget()).isZero();
        assertThat(goals.getLast().salesTarget()).isEqualByComparingTo("321.25");
        when(mapper.citySalesRanking(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                Map.of(
                                        "dimensionCode",
                                        "BJ",
                                        "salesAmount",
                                        new BigDecimal("160.625"))));
        when(mapper.cityReceipts(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(Map.of("regionCode", "BJ", "receiptAmount", new BigDecimal("50"))));
        when(mapper.customerRetention(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("newCustomerCount", 7, "annualReturningCustomerCount", 3));
        var filter =
                new SupplyDashboardFilter(
                        Instant.parse("2025-12-31T16:00:00Z"),
                        Instant.parse("2026-02-28T15:59:59Z"),
                        null,
                        null,
                        null,
                        null,
                        null);
        var actual = targets.completions("T", filter, "CITY", values);
        assertThat(actual).hasSize(4);
        assertThat(actual)
                .filteredOn(t -> t.metricCode().equals("SALES_AMOUNT"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.targetValue()).isEqualByComparingTo("321.25");
                            assertThat(t.actualValue()).isEqualByComparingTo("160.625");
                            assertThat(t.achievementRate()).isEqualByComparingTo("50");
                            assertThat(t.periodMonthCount()).isEqualTo(2);
                        });
        assertThat(actual)
                .filteredOn(t -> t.metricCode().equals("REPEAT_CUSTOMER"))
                .singleElement()
                .satisfies(t -> assertThat(t.actualValue()).isEqualByComparingTo("3"));
        verify(client, times(1)).values(any(), any(), any());
    }

    @Test
    void unavailableHrNeverBecomesInventedGoals() {
        var client = mock(HrTargetClient.class);
        when(client.values(any(), any(), any()))
                .thenThrow(new IllegalStateException("HR unavailable"));
        var targets =
                new HrDashboardTargets(
                        client,
                        mock(javax.sql.DataSource.class),
                        mock(SupplyDashboardQueryMapper.class));
        assertThatThrownBy(() -> targets.cityGoals("T", 2026, null)).hasMessage("HR unavailable");
    }
}
