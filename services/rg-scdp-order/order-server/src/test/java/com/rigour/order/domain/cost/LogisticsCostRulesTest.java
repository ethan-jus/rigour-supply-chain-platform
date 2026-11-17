package com.rigour.order.domain.cost;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

class LogisticsCostRulesTest {
    @Test
    void savedRecordsAreEditableAndOldConfirmationActionsAreRejected() {
        assertThatCode(() -> LogisticsCostRules.transition("ACTIVE", "UPDATE", BigDecimal.TEN, ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> LogisticsCostRules.transition("ACTIVE", "UPDATE", null, ""))
                .doesNotThrowAnyException();
        for (String action : List.of("CONFIRM", "REVOKE"))
            assertThatThrownBy(
                            () ->
                                    LogisticsCostRules.transition(
                                            "ACTIVE", action, BigDecimal.TEN, "说明"))
                    .hasMessageContaining("无需确认");
        assertThatThrownBy(
                        () -> LogisticsCostRules.transition("VOID", "UPDATE", BigDecimal.TEN, "说明"))
                .hasMessageContaining("仅有效");
    }

    @Test
    void voidAndDeleteStillRequireReasonsAndCorrectStates() {
        assertThatThrownBy(
                        () -> LogisticsCostRules.transition("ACTIVE", "VOID", BigDecimal.TEN, " "))
                .hasMessageContaining("原因");
        assertThatCode(
                        () ->
                                LogisticsCostRules.transition(
                                        "ACTIVE", "VOID", BigDecimal.TEN, "重复登记"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                LogisticsCostRules.transition(
                                        "VOID", "DELETE", BigDecimal.TEN, "清理作废单"))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                LogisticsCostRules.transition(
                                        "ACTIVE", "DELETE", BigDecimal.TEN, "说明"))
                .hasMessageContaining("仅已作废");
    }

    @Test
    void combinedTripRejectsDifferentSourceWarehouseOrSupplier() {
        assertThatCode(() -> LogisticsCostRules.sameTrip(List.of("WH|1|2|A", "WH|1|2|A")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> LogisticsCostRules.sameTrip(List.of("WH|1|2|A", "WH|1|2|B")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
