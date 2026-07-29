package com.rigour.shared.outbox;

/**
 * Outbox 事务内写入端口。
 * 实现必须与所属服务的业务数据使用同一数据库事务；表结构、锁策略、重试和清理由服务所有。
 */
@FunctionalInterface
public interface OutboxStore {
    void append(OutboxMessage message);
}
