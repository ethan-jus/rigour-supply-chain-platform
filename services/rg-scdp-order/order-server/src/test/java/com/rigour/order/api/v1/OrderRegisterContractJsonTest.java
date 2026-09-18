package com.rigour.order.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodRow;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodStatisticsView;
import com.rigour.order.api.v1.model.OrderRegisterModels.PeriodTotals;
import com.rigour.order.api.v1.model.OrderRegisterModels.ReceivablesView;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 订单登记 JSON 字段名契约；web 端 order-register.ts 只认 camelCase 键，改名即视为破坏。 */
class OrderRegisterContractJsonTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void coverageExposesHistoryCompleteInsteadOfComplete() {
        JsonNode covered = JSON.readTree(JSON.writeValueAsString(HistoryCoverage.covered()));
        assertThat(covered.get("historyComplete").asBoolean()).isTrue();
        assertThat(covered.has("complete")).isFalse();
        assertThat(names(covered))
                .containsExactlyInAnyOrder(
                        "historyComplete", "coverageFrom", "message", "missingCount");

        JsonNode incomplete =
                JSON.readTree(
                        JSON.writeValueAsString(
                                HistoryCoverage.incomplete(
                                        Instant.parse("2026-09-03T16:00:00Z"),
                                        "切换日前订单缺少历史资金事件",
                                        12L)));
        assertThat(incomplete.get("historyComplete").asBoolean()).isFalse();
        assertThat(incomplete.get("missingCount").asLong()).isEqualTo(12L);
        assertThat(incomplete.get("coverageFrom").asString())
                .isEqualTo("2026-09-03T16:00:00Z");
        assertThat(incomplete.get("message").asString()).contains("历史资金事件");
    }

    @Test
    void pageCoverageUsesTheSameHistoryCompleteName() {
        JsonNode page =
                JSON.readTree(
                        JSON.writeValueAsString(
                                new OrderRegisterPage<String>(
                                        0L,
                                        0,
                                        20,
                                        List.of(),
                                        Map.of(),
                                        HistoryCoverage.covered())));
        JsonNode coverage = page.get("coverage");
        assertThat(coverage.get("historyComplete").asBoolean()).isTrue();
        assertThat(coverage.has("complete")).isFalse();
    }

    @Test
    void periodViewMatchesFrontendTotalsAndRowsContract() {
        PeriodStatisticsView view =
                new PeriodStatisticsView(
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-17"),
                        "region",
                        new PeriodTotals(
                                new BigDecimal("100.500000"),
                                new BigDecimal("80.000000"),
                                new BigDecimal("5.000000"),
                                new BigDecimal("75.000000"),
                                new BigDecimal("20.500000")),
                        List.of(
                                new PeriodRow(
                                        "HZ",
                                        "杭州地区",
                                        new BigDecimal("60.000000"),
                                        new BigDecimal("50.000000"),
                                        new BigDecimal("0.000000"),
                                        new BigDecimal("50.000000"),
                                        new BigDecimal("10.000000")),
                                new PeriodRow(
                                        null,
                                        "未分配",
                                        new BigDecimal("40.500000"),
                                        new BigDecimal("30.000000"),
                                        new BigDecimal("5.000000"),
                                        new BigDecimal("25.000000"),
                                        new BigDecimal("10.500000"))),
                        HistoryCoverage.covered());

        JsonNode period = JSON.readTree(JSON.writeValueAsString(view));
        assertThat(names(period))
                .containsExactlyInAnyOrder(
                        "dateFrom", "dateTo", "groupBy", "totals", "rows", "coverage");
        assertThat(period.has("groups")).isFalse();
        assertThat(period.has("periodOrderAmount")).isFalse();
        assertThat(period.has("periodEndUnpaidAmount")).isFalse();
        assertThat(period.get("groupBy").asString()).isEqualTo("region");
        assertThat(period.get("dateFrom").asString()).isEqualTo("2026-09-01");
        assertThat(period.get("dateTo").asString()).isEqualTo("2026-09-17");

        JsonNode totals = period.get("totals");
        assertThat(names(totals))
                .containsExactlyInAnyOrder(
                        "periodOrderAmount",
                        "periodReceivedAmount",
                        "periodRefundAmount",
                        "periodNetReceivedAmount",
                        "endingUnpaidAmount");
        assertThat(totals.get("periodOrderAmount").decimalValue())
                .isEqualByComparingTo("100.500000");
        assertThat(totals.get("periodNetReceivedAmount").decimalValue())
                .isEqualByComparingTo("75.000000");
        assertThat(totals.get("endingUnpaidAmount").decimalValue())
                .isEqualByComparingTo("20.500000");

        JsonNode row = period.get("rows").get(0);
        assertThat(names(row))
                .containsExactlyInAnyOrder(
                        "key",
                        "label",
                        "periodOrderAmount",
                        "periodReceivedAmount",
                        "periodRefundAmount",
                        "periodNetReceivedAmount",
                        "endingUnpaidAmount");
        assertThat(row.has("group_key")).isFalse();
        assertThat(row.has("order_amount")).isFalse();
        assertThat(row.has("unpaid_amount")).isFalse();
        assertThat(row.get("key").asString()).isEqualTo("HZ");
        assertThat(row.get("label").asString()).isEqualTo("杭州地区");
        assertThat(row.get("periodOrderAmount").decimalValue()).isEqualByComparingTo("60.000000");
        assertThat(period.get("rows").get(1).get("label").asString()).isEqualTo("未分配");
        assertThat(period.get("coverage").get("historyComplete").asBoolean()).isTrue();
    }

    @Test
    void periodNullMoneyBecomesZeroAndMissingTotalsFallsBack() {
        PeriodStatisticsView blank =
                new PeriodStatisticsView(
                        LocalDate.parse("2026-09-01"),
                        LocalDate.parse("2026-09-02"),
                        null,
                        null,
                        List.of(),
                        null);

        JsonNode period = JSON.readTree(JSON.writeValueAsString(blank));
        assertThat(period.get("rows").size()).isZero();
        assertThat(period.get("totals").get("periodOrderAmount").decimalValue())
                .isEqualByComparingTo("0");
        assertThat(period.get("coverage").isNull()).isTrue();

        JsonNode row =
                JSON.readTree(
                        JSON.writeValueAsString(
                                new PeriodRow("BJ", null, null, null, null, null, null)));
        assertThat(row.get("periodOrderAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(row.get("endingUnpaidAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void receivablesRowKeepsFourMoneyFieldsAndHistoryComplete() {
        JsonNode row =
                JSON.readTree(
                        JSON.writeValueAsString(
                                new ReceivablesView(
                                        1L,
                                        "SO-1",
                                        "DINGHUOBAO",
                                        9L,
                                        "C-9",
                                        "测试客户",
                                        "HZ",
                                        null,
                                        "EMP-1",
                                        "张三",
                                        "杭州一部",
                                        Instant.parse("2026-08-01T02:00:00Z"),
                                        new BigDecimal("100.000000"),
                                        new BigDecimal("120.000000"),
                                        new BigDecimal("0.000000"),
                                        new BigDecimal("20.000000"),
                                        true,
                                        null)));
        assertThat(names(row))
                .contains(
                        "receivableAmount",
                        "netReceivedAmount",
                        "unpaidAmount",
                        "overpaidAmount",
                        "historyComplete");
        assertThat(row.get("overpaidAmount").decimalValue()).isEqualByComparingTo("20.000000");
    }

    private static List<String> names(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
