package com.rigour.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.rigour.order.domain.sync.HistorySyncRules;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HistoryBusinessDateTest {
    @Test
    void unmatchedHistoricalOrdersAndReceiptsUseAugust31() {
        for (String source : new String[] {"2026-08-20T00:00:00Z", "2026-09-03T15:59:59Z"}) {
            assertThat(HistorySyncRules.businessDate("DINGHUOBAO", Instant.parse(source), null))
                    .isEqualTo(HistorySyncRules.HISTORICAL_FALLBACK);
        }
    }

    @Test
    void cutoverIsInclusiveAndNewReceiptsForOldOrdersRemainNew() {
        assertThat(HistorySyncRules.businessDate("DINGHUOBAO", HistorySyncRules.CUTOVER, null))
                .isEqualTo(HistorySyncRules.CUTOVER);
    }

    @Test
    void repeatedSourceDateChangesNeverOverwriteMatchedOrFallbackDates() {
        for (Instant existing : new Instant[] {Instant.parse("2026-07-15T02:00:00Z"), HistorySyncRules.HISTORICAL_FALLBACK}) {
            for (String changed : new String[] {"2026-09-02T00:00:00Z", "2026-09-18T00:00:00Z", "2026-09-22T00:00:00Z"}) {
                assertThat(HistorySyncRules.businessDate("DINGHUOBAO", Instant.parse(changed), existing))
                        .isEqualTo(existing);
            }
        }
    }

    @Test
    void normalSourceCorrectionsAndNonDhbDatesAreUnaffected() {
        Instant changed = Instant.parse("2026-09-20T00:00:00Z");
        assertThat(HistorySyncRules.businessDate("DINGHUOBAO", changed, HistorySyncRules.CUTOVER)).isEqualTo(changed);
        assertThat(HistorySyncRules.businessDate("FEISHU", changed, HistorySyncRules.HISTORICAL_FALLBACK)).isEqualTo(changed);
        assertThat(HistorySyncRules.businessDate("DINGHUOBAO", null, null)).isNull();
    }
}
