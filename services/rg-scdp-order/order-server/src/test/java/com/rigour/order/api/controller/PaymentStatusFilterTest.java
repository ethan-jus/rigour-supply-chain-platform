package com.rigour.order.api.controller;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PaymentStatusFilterTest {
    @Test void separatesReceiptAndFinanceWithoutChangingLegacyQuerySets() {
        assertThat(PaymentStatusFilter.resolve(null, null, null)).isNull();
        assertThat(PaymentStatusFilter.resolve(null, "RECEIVED", null)).isEqualTo("CONFIRMED");
        assertThat(PaymentStatusFilter.resolve(null, "RECEIVED", "CONFIRMED")).isEqualTo("CHECKED");
        assertThat(PaymentStatusFilter.resolve(null, "RECEIVED", "UNCONFIRMED")).isEqualTo("RECEIVED");
        assertThat(PaymentStatusFilter.resolve("CONFIRMED", null, null)).isEqualTo("CONFIRMED");
        assertThat(PaymentStatusFilter.resolve(null, "CANCELLED", "CONFIRMED")).isEqualTo("NO_MATCH");
        assertThatThrownBy(()->PaymentStatusFilter.resolve(null, "CHECKED", null)).hasMessageContaining("无效");
    }
}
