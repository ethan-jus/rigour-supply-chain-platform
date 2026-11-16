package com.rigour.shared.cache;

/** Durable versions are committed in the same transaction as the owning domain's mutations. */
public interface CacheVersions {
    long current(String tenantId);
    void changed(String tenantId);
    boolean dirty(String tenantId);
}
