package com.rigour.shared.core.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BusinessCodeGeneratorTest {

    private final Clock fixedClock = Clock.fixed(
            Instant.parse("2026-08-20T01:02:03.456Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void generateDailyCodeWithBusinessDateAndPrefix() {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(fixedClock, length -> "7".repeat(length));

        String code = generator.generate(BusinessCodeRule.daily("so", 4));

        assertThat(code).isEqualTo("SO202608207777");
    }

    @Test
    void generateDailyCodeWithExplicitBusinessTime() {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(fixedClock, length -> "8".repeat(length));

        String code = generator.generate(BusinessCodeRule.daily("so", 4),
                Instant.parse("2026-08-18T16:30:00Z"));

        assertThat(code).isEqualTo("SO202608198888");
    }

    @Test
    void generateMillisecondCodeWithBusinessDateTime() {
        BusinessCodeGenerator generator = new BusinessCodeGenerator(fixedClock, length -> "1".repeat(length));

        String code = generator.generate(BusinessCodeRule.millisecond("stk", 3));

        assertThat(code).isEqualTo("STK20260820090203456111");
    }

    @Test
    void retryUntilReserveSucceeded() {
        AtomicInteger attempts = new AtomicInteger();
        BusinessCodeGenerator generator = new BusinessCodeGenerator(fixedClock,
                length -> String.valueOf(attempts.incrementAndGet()).repeat(length));

        String code = generator.generateUnique(BusinessCodeRule.daily("po", 2),
                ignored -> attempts.get() == 2);

        assertThat(code).isEqualTo("PO2026082022");
    }

    @Test
    void retryWithExplicitBusinessTimeUntilReserveSucceeded() {
        AtomicInteger attempts = new AtomicInteger();
        BusinessCodeGenerator generator = new BusinessCodeGenerator(fixedClock,
                length -> String.valueOf(attempts.incrementAndGet()).repeat(length));

        String code = generator.generateUnique(BusinessCodeRule.daily("po", 2),
                Instant.parse("2026-08-18T16:30:00Z"), ignored -> attempts.get() == 2);

        assertThat(code).isEqualTo("PO2026081922");
    }

    @Test
    void rejectInvalidPrefix() {
        assertThatThrownBy(() -> BusinessCodeRule.daily("销售", 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("编码前缀");
    }
}
