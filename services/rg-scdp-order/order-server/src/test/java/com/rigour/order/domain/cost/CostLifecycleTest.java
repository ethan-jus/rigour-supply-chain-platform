package com.rigour.order.domain.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

class CostLifecycleTest {
    @Test
    void onlyVoidsCanBeDeletedAndConfirmedMustBeRevokedBeforeEditing() {
        for (String state : List.of("DRAFT", "CONFIRMED", "VOID")) {
            for (String action : List.of("UPDATE", "CONFIRM", "VOID", "REVOKE", "DELETE")) {
                boolean allowed =
                        "DRAFT".equals(state)
                                ? Set.of("UPDATE", "CONFIRM", "VOID").contains(action)
                                : "CONFIRMED".equals(state)
                                        ? "REVOKE".equals(action)
                                        : "DELETE".equals(action);
                if (allowed)
                    assertThatCode(
                                    () ->
                                            LogisticsCostRules.transition(
                                                    state, action, BigDecimal.TEN, "依据"))
                            .doesNotThrowAnyException();
                else
                    assertThatThrownBy(
                                    () ->
                                            LogisticsCostRules.transition(
                                                    state, action, BigDecimal.TEN, "依据"))
                            .isInstanceOf(IllegalArgumentException.class);
            }
        }
        for (var pair :
                List.of(
                        List.of("DRAFT", "VOID"),
                        List.of("CONFIRMED", "REVOKE"),
                        List.of("VOID", "DELETE")))
            assertThatThrownBy(
                            () ->
                                    LogisticsCostRules.transition(
                                            pair.get(0), pair.get(1), BigDecimal.TEN, " "))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
