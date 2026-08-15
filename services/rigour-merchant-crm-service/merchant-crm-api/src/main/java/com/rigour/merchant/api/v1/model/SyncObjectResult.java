package com.rigour.merchant.api.v1.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SyncObjectResult(
        UUID runId, String objectType, String status, long fetched, long created,
        long changed, long repaired, long duplicates, long absent, long rejected,
        int pages, Instant finishedAt, long unmapped,
        Map<String, Long> dictionaryRevisions) {
    public SyncObjectResult {
        dictionaryRevisions = dictionaryRevisions == null ? Map.of() : Map.copyOf(dictionaryRevisions);
    }
}
