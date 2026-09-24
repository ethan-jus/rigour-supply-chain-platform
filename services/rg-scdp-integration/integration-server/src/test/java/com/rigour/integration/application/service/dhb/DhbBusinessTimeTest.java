package com.rigour.integration.application.service.dhb;

import static org.assertj.core.api.Assertions.*;

import com.rigour.integration.application.port.out.DhbClient.Receipt;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DhbBusinessTimeTest {
    private Receipt receipt(String business, String operation) {
        return new Receipt("FR-1", "FR-1", "OLD-ORDER", "C-1", null, "13", "Offline",
                BigDecimal.TEN, "pend_receipted", business == null ? null : Instant.parse(business),
                operation == null ? null : Instant.parse(operation), Instant.parse("2026-09-23T00:00:00Z"),
                null, null, null, null, null, Map.of());
    }

    @Test
    void usesFullOperationTimeIncludingCrossDayAndNewReceiptsForOldOrders() {
        for (String business : new String[] {"2026-09-03T16:00:00Z", "2026-09-09T16:00:00Z"}) {
            assertThat(DhbOrderSyncService.receiptBusinessTime(receipt(business, "2026-09-11T07:47:02Z"), "FR-1"))
                    .isEqualTo(Instant.parse("2026-09-11T07:47:02Z"));
        }
    }

    @Test
    void historicalBusinessDateIsNotReclassifiedByLaterOperationOrUpdateTime() {
        for (String operation : new String[] {null, "2026-09-04T12:38:45Z", "2026-09-23T01:00:00Z"}) {
            assertThat(DhbOrderSyncService.receiptBusinessTime(receipt("2026-09-03T15:59:59Z", operation), "FR-1"))
                    .isEqualTo(Instant.parse("2026-09-03T15:59:59Z"));
        }
    }

    @Test
    void missingBusinessDateCannotGuessCutoverFromOperationTime() {
        assertThatThrownBy(() -> DhbOrderSyncService.receiptBusinessTime(receipt(null, "2026-09-23T01:00:00Z"), "FR-1"))
                .hasMessageContaining("历史补录边界");
    }

    @Test
    void missingOrPreCutoverOperationTimeNeedsReviewInsteadOfMidnightOrUpdatedTime() {
        for (String operation : new String[] {null, "2026-09-03T15:59:59Z"}) {
            assertThatThrownBy(() -> DhbOrderSyncService.receiptBusinessTime(receipt("2026-09-03T16:00:00Z", operation), "FR-1"))
                    .hasMessageContaining("操作时间");
        }
    }
}
