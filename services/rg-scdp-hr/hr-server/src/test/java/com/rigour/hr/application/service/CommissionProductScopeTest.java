package com.rigour.hr.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.rigour.erp.api.v1.model.ErpPayrollProductView;
import com.rigour.hr.api.v1.model.*;
import com.rigour.order.api.v1.model.OrderPayrollLineView;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

class CommissionProductScopeTest {
    final ConfiguredCommissionServiceTest fixture = new ConfiguredCommissionServiceTest();
    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setup() {
        fixture.setup();
        when(fixture.source.productsByVariants(eq("t"), anyList()))
                .thenAnswer(
                        invocation ->
                                ((List<Long>) invocation.getArgument(1))
                                        .stream()
                                                .map(
                                                        id ->
                                                                new ErpPayrollProductView(
                                                                        id,
                                                                        id,
                                                                        "P" + id,
                                                                        "SKU" + id,
                                                                        "台呢",
                                                                        "型号" + id,
                                                                        "SET",
                                                                        21L,
                                                                        "台呢"))
                                                .toList());
    }

    PayrollSchemeCommissionRule rule(String code, List<Long> variants, String value) {
        return new PayrollSchemeCommissionRule(
                code,
                code,
                "SALES",
                List.of(21L),
                List.of(),
                variants,
                List.of(),
                "QUANTITY",
                new BigDecimal(value),
                "SET",
                false);
    }

    PayrollSchemeCommissionRule changed(
            PayrollSchemeCommissionRule rule, String field, Object value) {
        ObjectNode node = json.valueToTree(rule);
        node.set(field, json.valueToTree(value));
        return json.treeToValue(node, PayrollSchemeCommissionRule.class);
    }

    List<PayrollSchemeCommissionRule> pair(
            String name, List<Long> variants, String sales, String manager) {
        return List.of(
                changed(rule(name + "-sales", variants, sales), "rowCode", name),
                changed(
                        changed(
                                rule(name + "-manager", variants, manager),
                                "recipientRole",
                                "CITY_MANAGER"),
                        "rowCode",
                        name));
    }

    void use(List<PayrollSchemeCommissionRule> rules) {
        ObjectNode definition = json.valueToTree(fixture.scheme.definition());
        ((ObjectNode) definition.get("commission")).set("rules", json.valueToTree(rules));
        fixture.scheme =
                PayrollSchemeTestFixture.version(
                        json.treeToValue(definition, PayrollSchemeDefinition.class));
    }

    List<String> errors() {
        var d = fixture.scheme.definition();
        return PayrollSchemeValidator.component(
                        new PayrollComponentDefinition(
                                "商品提成",
                                "COMMISSION",
                                "ALL",
                                List.of(1L),
                                d.effectiveFrom(),
                                d.effectiveTo(),
                                null,
                                null,
                                d.commission(),
                                null,
                                null,
                                List.of()))
                .errors();
    }

    ConfiguredCommissionResult calculate(Long... variants) {
        var lines =
                Arrays.stream(variants)
                        .map(
                                id -> {
                                    ObjectNode node =
                                            json.valueToTree(fixture.line("L" + id, id, "C", true));
                                    node.put("unitCode", "SET");
                                    return json.treeToValue(node, OrderPayrollLineView.class);
                                })
                        .toList();
        return fixture.calculate(
                lines,
                List.of(fixture.assignment("C", "A", LocalDate.of(2026, 9, 1), LocalDate.MAX)));
    }

    @Test
    void disjointClothModelsInOneCategorySaveAndPayEachRoleAtTheirOwnRate() {
        var rules = new ArrayList<PayrollSchemeCommissionRule>();
        rules.addAll(pair("A300/A500", List.of(300L, 500L), "15", "5"));
        rules.addAll(pair("A600/A700/A800", List.of(600L, 700L, 800L), "25", "10"));
        rules.addAll(pair("斯诺克", List.of(900L, 901L, 902L), "35", "10"));
        use(rules);
        assertThat(errors()).isEmpty();
        var result = calculate(300L, 600L, 900L);
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("200");
        assertThat(result.details())
                .hasSize(6)
                .allSatisfy(
                        detail -> {
                            String expected =
                                    switch (detail.sourceLineId()
                                            + "|"
                                            + detail.recipientRoleCode()) {
                                        case "L300|SALES" -> "30";
                                        case "L300|CITY_MANAGER" -> "10";
                                        case "L600|SALES" -> "50";
                                        case "L600|CITY_MANAGER", "L900|CITY_MANAGER" -> "20";
                                        case "L900|SALES" -> "70";
                                        default ->
                                                throw new AssertionError(
                                                        "Unexpected commission: " + detail);
                                    };
                            assertThat(detail.released()).isEqualByComparingTo(expected);
                        });
    }

    @Test
    void aModelRuleDoesNotPayUnselectedModelsInItsCategory() {
        use(pair("A300/A500", List.of(300L, 500L), "15", "5"));
        var result = calculate(600L);
        assertThat(result.released()).isZero();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("提成标准未配置"));
    }

    @Test
    void categoryFallbackAndModelOverrideCanCoexistWithoutDoublePayment() {
        use(List.of(rule("分类默认", List.of(), "8"), rule("A300", List.of(300L), "15")));
        assertThat(errors()).isEmpty();
        var result = calculate(300L, 600L);
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("46");
        assertThat(result.details()).hasSize(2);
        assertThat(result.details())
                .filteredOn(d -> d.sourceLineId().equals("L300"))
                .singleElement()
                .satisfies(d -> assertThat(d.released()).isEqualByComparingTo("30"));
    }

    @Test
    void overlappingModelsForOneRoleAreRejectedAndNeverCalculatedTwice() {
        use(
                List.of(
                        rule("第一组", List.of(300L, 500L), "15"),
                        rule("第二组", List.of(500L, 600L), "25")));
        assertThat(errors()).anyMatch(error -> error.contains("同级商品范围交叉"));
        var result = calculate(500L);
        assertThat(result.released()).isZero();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("同级商品规则冲突"));
    }

    @Test
    void excludingTheSharedModelRemovesTheConflictWithoutBroadeningEitherRule() {
        use(
                List.of(
                        changed(
                                rule("第一组", List.of(300L, 500L), "15"),
                                "excludedVariantIds",
                                List.of(500L)),
                        rule("第二组", List.of(500L, 600L), "25")));
        assertThat(errors()).isEmpty();
        var result = calculate(300L, 500L);
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("80");
        assertThat(result.details()).hasSize(2);
    }

    @Test
    void duplicateCategoryStandardsStillConflictButChildOverridesParent() {
        var first = rule("第一组", List.of(), "8");
        var second = rule("第二组", List.of(), "15");
        use(List.of(first, second));
        assertThat(errors()).anyMatch(error -> error.contains("同级商品范围交叉"));
        use(List.of(changed(first, "categoryIds", List.of(20L)), second));
        assertThat(errors()).isEmpty();
        var result = calculate(300L);
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("30");
    }

    @Test
    void excludedVariantsAndAncestorCategoriesRemainConfiguredZeroOnlyWithinTheModelScope() {
        for (var excluded :
                List.of(
                        changed(
                                rule("型号标准", List.of(300L), "15"),
                                "excludedVariantIds",
                                List.of(300L)),
                        changed(
                                rule("型号标准", List.of(300L), "15"),
                                "excludedCategoryIds",
                                List.of(20L)))) {
            use(List.of(excluded));
            var result = calculate(300L);
            assertThat(result.issues()).isEmpty();
            assertThat(result.released()).isZero();
            assertThat(calculate(600L).issues()).anyMatch(issue -> issue.contains("提成标准未配置"));
        }
    }

    @Test
    void modelWithNoCommissionOverridesCategoryWithoutSuppressingOtherModels() {
        use(
                List.of(
                        rule("分类默认", List.of(), "8"),
                        changed(rule("不计提型号", List.of(300L), "15"), "excluded", true)));
        assertThat(errors()).isEmpty();
        var result = calculate(300L, 600L);
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("16");
        assertThat(result.details())
                .singleElement()
                .satisfies(d -> assertThat(d.sourceLineId()).isEqualTo("L600"));
    }

    @Test
    void oneModelCanHaveDifferentQuantityRatesForDifferentOrderUnits() {
        var setRule = rule("A300按套", List.of(300L), "15");
        var bedRule = changed(rule("A300按床", List.of(300L), "8"), "unitCode", "BED");
        use(List.of(setRule, bedRule));
        assertThat(errors()).isEmpty();
        var set = json.<ObjectNode>valueToTree(fixture.line("SET", 300, "C", true));
        set.put("unitCode", "SET");
        var bed = json.<ObjectNode>valueToTree(fixture.line("BED", 300, "C", true));
        bed.put("unitCode", "BED");
        var result =
                fixture.calculate(
                        List.of(
                                json.treeToValue(set, OrderPayrollLineView.class),
                                json.treeToValue(bed, OrderPayrollLineView.class)),
                        List.of());
        assertThat(result.issues()).isEmpty();
        assertThat(result.released()).isEqualByComparingTo("46");
        assertThat(result.details()).hasSize(2);
        assertThat(result.details())
                .filteredOn(d -> d.unitCode().equals("SET"))
                .singleElement()
                .satisfies(d -> assertThat(d.released()).isEqualByComparingTo("30"));
        assertThat(result.details())
                .filteredOn(d -> d.unitCode().equals("BED"))
                .singleElement()
                .satisfies(d -> assertThat(d.released()).isEqualByComparingTo("16"));
        use(List.of(setRule, changed(bedRule, "unitCode", "SET")));
        assertThat(errors()).anyMatch(error -> error.contains("同级商品范围交叉"));
        use(List.of(setRule, changed(rule("同金额重复配置", List.of(300L), "15"), "unitCode", "SET")));
        assertThat(errors()).anyMatch(error -> error.contains("同级商品范围交叉"));
    }
}
