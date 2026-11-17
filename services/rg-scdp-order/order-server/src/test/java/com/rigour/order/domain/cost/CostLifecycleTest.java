package com.rigour.order.domain.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

class CostLifecycleTest {
    @Test
    void savedRecordsCanBeEditedAndOnlyVoidedRecordsCanBeDeleted() {
        for (String state : List.of("ACTIVE", "VOID")) {
            for (String action : List.of("UPDATE", "CONFIRM", "VOID", "REVOKE", "DELETE")) {
                boolean allowed =
                        "ACTIVE".equals(state)
                                ? Set.of("UPDATE", "VOID").contains(action)
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
        for (var pair : List.of(List.of("ACTIVE", "VOID"), List.of("VOID", "DELETE")))
            assertThatThrownBy(
                            () ->
                                    LogisticsCostRules.transition(
                                            pair.get(0), pair.get(1), BigDecimal.TEN, " "))
                    .isInstanceOf(IllegalArgumentException.class);
    }
}
