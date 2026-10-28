package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;

import com.rigour.hr.api.v1.model.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.*;

class ManagerMetricRuleValidationTest {
    static PayrollSchemeDefinition definition(List<PayrollMetricStep> bands) {
        var json = JsonMapper.builder().build();
        ObjectNode node = (ObjectNode) json.valueToTree(PayrollSchemeTestFixture.definition());
        node.put("role", "CITY_MANAGER");
        node.put("performanceMode", "MANAGER");
        node.set("allowances", json.createArrayNode());
        node.set(
                "manager",
                json.valueToTree(
                        new PayrollManagerPolicy(
                                List.of(
                                        new PayrollMetricDefinition(
                                                "store",
                                                "建联门店数",
                                                "MANUAL_CONFIRMED",
                                                null,
                                                new BigDecimal("100"),
                                                bands,
                                                null,
                                                "CONTACTED_STORE_COUNT",
                                                "TIERED")),
                                new PayrollProtectionPolicy("MONTHS", 1, true),
                                null,
                                List.of(),
                                "ACTUAL_COMPLETION")));
        return json.treeToValue(node, PayrollSchemeDefinition.class);
    }

    @Test
    void acceptsHrDefinedBreakpointsAndCoefficients() {
        var bands =
                List.of(
                        new PayrollMetricStep(
                                BigDecimal.ZERO,
                                new BigDecimal("1.35"),
                                true,
                                false,
                                "LINEAR",
                                BigDecimal.ONE),
                        new PayrollMetricStep(
                                new BigDecimal("1.35"),
                                null,
                                true,
                                false,
                                "FIXED",
                                new BigDecimal("1.77")));
        assertThat(PayrollSchemeValidator.check(definition(bands)).errors()).isEmpty();
    }

    @Test
    void rejectsMissingAndNegativeScoringRulesWithoutRejectingOverlaps() {
        assertThat(PayrollSchemeValidator.check(definition(List.of())).errors())
                .anyMatch(s -> s.contains("计分区间"));
        var overlapping =
                List.of(
                        new PayrollMetricStep(
                                BigDecimal.ZERO,
                                BigDecimal.ONE,
                                true,
                                true,
                                "LINEAR",
                                BigDecimal.ONE),
                        new PayrollMetricStep(
                                BigDecimal.ONE, null, true, false, "FIXED", new BigDecimal("-1")));
        var errors = PayrollSchemeValidator.check(definition(overlapping)).errors();
        assertThat(errors).noneMatch(s -> s.contains("连续无重叠"));
        assertThat(errors).anyMatch(s -> s.contains("非负数"));
    }

    static PayrollMetricStep band(String from, String to) {
        return new PayrollMetricStep(
                new BigDecimal(from),
                to == null ? null : new BigDecimal(to),
                true,
                true,
                "FIXED",
                new BigDecimal("1.2"));
    }

    @Test
    void singleMetricRulesAllowPartialBoundedGappedAndOverlappingIntervals() {
        for (var bands :
                List.of(
                        List.of(band("0.5", "1")),
                        List.of(band("0", "1"), band("1.5", "2")),
                        List.of(band("1.5", "2"), band("1.5", "2")),
                        List.of(band("0", "1"), band("1", "2")),
                        List.of(band("1.5", "2"), band("0", null)))) {
            assertThat(PayrollSchemeValidator.check(definition(bands)).errors()).isEmpty();
            var d = definition(bands);
            var component =
                    new PayrollComponentDefinition(
                            d.name(),
                            "PERFORMANCE",
                            d.role(),
                            d.departmentIds(),
                            d.effectiveFrom(),
                            d.effectiveTo(),
                            null,
                            new PayrollPerformanceStandard("MANAGER", null, d.manager(), List.of()),
                            null,
                            null,
                            null,
                            List.of());
            assertThat(PayrollSchemeValidator.component(component).errors()).isEmpty();
        }
    }

    @Test
    void singleMetricWeightsAreSavedAsEnteredAndDoNotHaveToTotalOneHundred() {
        var json = JsonMapper.builder().build();
        for (var weight : List.of("0", "30", "140")) {
            ObjectNode node = (ObjectNode) json.valueToTree(definition(List.of(band("0", "3"))));
            ((ObjectNode) node.path("manager").path("metrics").get(0))
                    .put("weight", new BigDecimal(weight));
            assertThat(
                            PayrollSchemeValidator.check(
                                            json.treeToValue(node, PayrollSchemeDefinition.class))
                                    .errors())
                    .isEmpty();
        }
    }

    @Test
    void invalidBoundsAndHistoricalCombinedScoringStillRemainInvalid() {
        assertThat(PayrollSchemeValidator.check(definition(List.of(band("2", "1")))).errors())
                .anyMatch(s -> s.contains("上界须大于下界"));
        assertThat(PayrollSchemeValidator.check(definition(List.of(band("-1", "1")))).errors())
                .anyMatch(s -> s.contains("非负数"));
        var json = JsonMapper.builder().build();
        ObjectNode node = (ObjectNode) json.valueToTree(definition(List.of(band("0", null))));
        var manager = (ObjectNode) node.path("manager");
        manager.put("calculationMode", "TIERED");
        manager.put("cap", 3);
        manager.set("steps", json.valueToTree(List.of(band("1", "2"))));
        assertThat(
                        PayrollSchemeValidator.check(
                                        json.treeToValue(node, PayrollSchemeDefinition.class))
                                .errors())
                .anyMatch(s -> s.contains("综合完成率系数须从0开始"));
    }
}
