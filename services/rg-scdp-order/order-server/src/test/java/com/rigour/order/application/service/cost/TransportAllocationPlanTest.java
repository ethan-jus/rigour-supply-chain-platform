package com.rigour.order.application.service.cost;

import static org.assertj.core.api.Assertions.*;

import com.rigour.order.api.v1.model.*;
import com.rigour.order.api.v1.model.TransportRecordModels.Detail;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

class TransportAllocationPlanTest {
    final JsonMapper json = JsonMapper.builder().build();

    LogisticsCostSourceView row(String no, String amount) {
        return json.readValue(
                "{\"documentNo\":\""
                        + no
                        + "\",\"sourceType\":\"SALES_ORDER\",\"documentAmount\":"
                        + amount
                        + "}",
                LogisticsCostSourceView.class);
    }

    @Test
    void salesAmountsProduceExactSharesIndependentOfSelectionOrder() {
        var plan =
                TransportAllocationPlan.calculate(
                        new BigDecimal("137.64"),
                        List.of(row("S2", "4000"), row("S1", "6000")),
                        null);
        assertThat(plan.method()).isEqualTo("AMOUNT");
        assertThat(plan.shares().get("S1")).isEqualByComparingTo("82.58");
        assertThat(plan.shares().get("S2")).isEqualByComparingTo("55.06");
    }

    @Test
    void missingOrZeroSalesAmountUsesEqualSharesWithoutInventingSourceMoney() {
        var rows = List.of(row("S2", "null"), row("S1", "0"), row("S3", "100"));
        var plan = TransportAllocationPlan.calculate(new BigDecimal("100.01"), rows, null);
        assertThat(plan.method()).isEqualTo("EQUAL");
        assertThat(plan.shares().values())
                .containsExactly(
                        new BigDecimal("33.34"), new BigDecimal("33.34"), new BigDecimal("33.33"));
        assertThat(rows.getFirst().documentAmount()).isNull();
    }

    @Test
    void explicitManualSharesRetainAmountsAndRejectUnbalancedOrForeignOrders() {
        var rows = List.of(row("S1", "6000"), row("S2", "4000"));
        var manual = Map.of("S1", new BigDecimal("80"), "S2", new BigDecimal("57.64"));
        assertThat(
                        TransportAllocationPlan.calculate(new BigDecimal("137.64"), rows, manual)
                                .shares())
                .containsEntry("S1", new BigDecimal("80.00"));
        for (var invalid :
                List.of(
                        Map.of("S1", new BigDecimal("80")),
                        Map.of("S1", new BigDecimal("80"), "HIDDEN", new BigDecimal("57.64")),
                        Map.of("S1", new BigDecimal("80"), "S2", new BigDecimal("57.63")),
                        Map.of("S1", new BigDecimal("-1"), "S2", new BigDecimal("138.64")),
                        Map.of("S1", new BigDecimal("80.001"), "S2", new BigDecimal("57.639"))))
            assertThatThrownBy(
                            () ->
                                    TransportAllocationPlan.calculate(
                                            new BigDecimal("137.64"), rows, invalid))
                    .isInstanceOf(com.rigour.shared.core.exception.BusinessException.class);
    }

    @Test
    void unknownTotalStaysUnknownAndKnownZeroStaysZero() {
        var rows = List.of(row("S1", "6000"), row("S2", "4000"));
        assertThat(TransportAllocationPlan.calculate(null, rows, null).shares())
                .containsEntry("S1", null);
        assertThat(TransportAllocationPlan.calculate(BigDecimal.ZERO, rows, null).shares().values())
                .allMatch(v -> v.signum() == 0);
    }

    Detail platformDetail(String status, String amount, String shares) {
        return json.readValue(
                """
{"record":{"revision":1,"statusCode":"%s","data":{"amount":137.64,"ledger":{"schemaVersion":2,"feeItems":[],"sources":[{"orderCode":"S1","linkRoleCode":"COST","allocations":[{"amount":82.58}]},{"orderCode":"S2","linkRoleCode":"COST","allocations":[{"amount":55.06}]}]}},"sources":[{"documentNo":"S1","sourceType":"SALES_ORDER","documentAmount":6000},{"documentNo":"S2","sourceType":"SALES_ORDER","documentAmount":4000}]},"platform":{"source":{"revision":1,"sourceDifference":false,"order":{"line":0,"raw":{},"facts":{"billAmount":%s}}},"draft":{"orderCodes":["S1","S2"],"orderAllocations":%s}}}
"""
                        .formatted(status, amount, shares),
                Detail.class);
    }

    @Test
    void pendingPlatformDraftIsVisibleInSalesSummaryBeforeConfirmation() {
        var d = platformDetail("DRAFT", "137.64", "{\"S1\":80,\"S2\":57.64}");
        assertThat(TransportAllocationPlan.savedShares(d.record(), d.platform()).get("S1"))
                .isEqualByComparingTo("80");
    }

    @Test
    void confirmedCostUsesFrozenAllocationWhenPlatformBillChanges() {
        var d = platformDetail("CONFIRMED", "142.64", "{\"S1\":80,\"S2\":57.64}");
        assertThat(TransportAllocationPlan.savedShares(d.record(), d.platform()).get("S1"))
                .isEqualByComparingTo("82.58");
    }

    @Test
    void changedPendingBillDoesNotQuietlyRedistributeAnExplicitAllocation() {
        var d = platformDetail("DRAFT", "142.64", "{\"S1\":80,\"S2\":57.64}");
        assertThat(TransportAllocationPlan.savedShares(d.record(), d.platform())).isEmpty();
    }
}
