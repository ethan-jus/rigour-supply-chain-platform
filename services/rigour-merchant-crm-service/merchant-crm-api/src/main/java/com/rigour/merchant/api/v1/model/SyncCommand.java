package com.rigour.merchant.api.v1.model;

import java.time.Instant;

public record SyncCommand(String objectType, Integer maxPages, Instant from, Instant to) {
    public SyncCommand {
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("同步窗口from和to必须同时提供");
        }
        if (from != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("同步窗口from必须早于to");
        }
    }

    public SyncCommand(String objectType, Integer maxPages) {
        this(objectType, maxPages, null, null);
    }
}
