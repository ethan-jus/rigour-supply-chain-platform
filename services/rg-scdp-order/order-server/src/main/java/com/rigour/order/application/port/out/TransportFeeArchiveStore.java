package com.rigour.order.application.port.out;

public interface TransportFeeArchiveStore {
    record Archive(String id, String key) {}

    Archive find(String tenant, String actor, String hash);

    Archive insert(String tenant, String actor, String hash, String name, String key, long size);
}
