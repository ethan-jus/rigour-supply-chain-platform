package com.rigour.shared.audit;

/**
 * 审计写入端口。
 * 具体服务决定审计是否与业务事务隔离、如何持久化和保留；shared 不提供静默降级实现。
 */
@FunctionalInterface
public interface AuditSink {
    void append(AuditEvent event);
}
