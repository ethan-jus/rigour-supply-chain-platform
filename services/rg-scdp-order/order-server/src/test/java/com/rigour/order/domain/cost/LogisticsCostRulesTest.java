package com.rigour.order.domain.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class LogisticsCostRulesTest {
    @Test
    void pendingAmountIsDifferentFromConfirmedZero() {
        assertThatCode(() -> LogisticsCostRules.money(null, false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> LogisticsCostRules.transition("DRAFT", "CONFIRM", null, "已核对"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                LogisticsCostRules.transition(
                                        "DRAFT", "CONFIRM", BigDecimal.ZERO, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(
                        () ->
                                LogisticsCostRules.transition(
                                        "DRAFT", "CONFIRM", BigDecimal.ZERO, "供应商免运费"))
                .doesNotThrowAnyException();
    }

    @Test
    void confirmedEditsAndVoidsRequireReason() {
        assertThatThrownBy(
                        () ->
                                LogisticsCostRules.transition(
                                        "CONFIRMED", "UPDATE", BigDecimal.TEN, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> LogisticsCostRules.transition("DRAFT", "VOID", BigDecimal.TEN, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> LogisticsCostRules.transition("VOID", "UPDATE", BigDecimal.TEN, "纠正"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void combinedTripRejectsDifferentSourceWarehouseOrSupplier() {
        assertThatCode(() -> LogisticsCostRules.sameTrip(List.of("WH|1|2|A", "WH|1|2|A")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LogisticsCostRules.sameTrip(List.of("WH|1|2|A", "WH|1|2|B")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
