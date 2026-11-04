package com.rigour.shared.core.math;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MoneyAllocationTest {
    @Test void aSingleCentAcrossManyDocumentsNeverCreatesNegativeRemainders() {
        var rows = MoneyAllocation.split(new BigDecimal("0.01"), Collections.nCopies(100, BigDecimal.ONE));
        assertThat(rows).hasSize(100).allMatch(v -> v.signum() >= 0);
        assertThat(rows.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0.01");
        assertThat(rows.getFirst()).isEqualByComparingTo("0.01");
    }
    @Test void weightedFeesConserveCentsAndUseStableTieOrder() {
        assertThat(MoneyAllocation.split(new BigDecimal("280.00"), List.of(BigDecimal.ONE, new BigDecimal("2")))).containsExactly(new BigDecimal("93.33"),new BigDecimal("186.67"));
        assertThat(MoneyAllocation.split(new BigDecimal("20.00"), List.of(BigDecimal.ONE, new BigDecimal("2")))).containsExactly(new BigDecimal("6.67"),new BigDecimal("13.33"));
        assertThatThrownBy(() -> MoneyAllocation.split(BigDecimal.ONE, List.of(BigDecimal.ZERO))).isInstanceOf(IllegalArgumentException.class);
    }
}
