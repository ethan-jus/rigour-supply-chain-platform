package com.rigour.order.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.order.api.v1.model.OrderRegisterModels.HistoryCoverage;
import com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPage;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
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
    void paymentIdKeepsAllDigitsInBrowserJson() {
        var payment = JSON.readValue("{\"id\":2108016692525498370}",
                com.rigour.order.api.v1.model.OrderRegisterModels.OrderRegisterPaymentView.class);
        var node = JSON.readTree(JSON.writeValueAsString(payment));
        assertThat(node.get("id").isString()).isTrue();
        assertThat(node.get("id").asString()).isEqualTo("2108016692525498370");
    }

    private static List<String> names(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
