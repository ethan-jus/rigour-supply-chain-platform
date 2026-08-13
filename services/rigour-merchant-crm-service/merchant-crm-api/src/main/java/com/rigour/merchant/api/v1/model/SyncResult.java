package com.rigour.merchant.api.v1.model;

import java.util.List;
import java.util.UUID;

public record SyncResult(UUID batchId, String status, List<SyncObjectResult> objects) {
    public SyncResult {
        objects = objects == null ? List.of() : List.copyOf(objects);
    }
}
