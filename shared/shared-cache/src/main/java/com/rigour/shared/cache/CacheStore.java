package com.rigour.shared.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 租户隔离缓存端口。
 * 缓存只能作为可丢失投影，不能成为订单、库存、额度或财务事实的唯一来源。
 */
public interface CacheStore {

    Optional<byte[]> get(String tenantId, String key);

    void put(String tenantId, String key, byte[] value, Duration timeToLive);

    void evict(String tenantId, String key);
}
