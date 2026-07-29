package com.rigour.shared.idempotency;

import java.time.Duration;
import java.util.Optional;

/**
 * 幂等存储端口。
 * reserve 必须是原子操作；跨实例并发、过期回收和失败释放语义由具体服务实现并做集成测试。
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(IdempotencyKey key);

    boolean reserve(IdempotencyKey key, Duration timeToLive);

    void complete(IdempotencyKey key, String responsePayload);

    void release(IdempotencyKey key);
}
