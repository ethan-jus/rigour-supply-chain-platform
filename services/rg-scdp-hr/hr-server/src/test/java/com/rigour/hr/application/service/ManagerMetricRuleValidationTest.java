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
    void rejectsMissingGappedOverlappingAndNegativeScoringRules() {
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
        assertThat(errors).anyMatch(s -> s.contains("连续无重叠"));
        assertThat(errors).anyMatch(s -> s.contains("非负数"));
    }
}
