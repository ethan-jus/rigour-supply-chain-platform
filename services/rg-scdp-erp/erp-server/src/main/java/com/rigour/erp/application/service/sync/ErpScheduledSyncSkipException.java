package com.rigour.erp.application.service.sync;

import com.rigour.erp.application.port.out.ErpSyncRunAuditStore.ScheduledSkipReason;
import java.util.Objects;

/**
 * ERP 定时同步在业务动作开始前被明确阻塞。
 *
 * <p>只允许连接器租约获取冲突和本地对象同步锁冲突构造该异常。动作已开始后的
 * 租约丢失、上游失败或落库失败仍是失败，不得降级为跳过。</p>
 */
public final class ErpScheduledSyncSkipException extends RuntimeException {
    private final String blockedObjectType;
    private final ScheduledSkipReason reason;

    private ErpScheduledSyncSkipException(String blockedObjectType, ScheduledSkipReason reason) {
        super(Objects.requireNonNull(reason, "reason不能为空").message());
        if (blockedObjectType == null || !blockedObjectType.matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new IllegalArgumentException("blockedObjectType必须是1到32位大写业务编码");
        }
        this.blockedObjectType = blockedObjectType;
        this.reason = reason;
    }

    /** 连接器租约在业务动作开始前获取失败。 */
    public static ErpScheduledSyncSkipException connectorLeaseConflict(String blockedObjectType) {
        return new ErpScheduledSyncSkipException(blockedObjectType,
                ScheduledSkipReason.CONNECTOR_LEASE_CONFLICT);
    }

    /** ERP 对象批次在创建前发现本地同步锁被占用。 */
    public static ErpScheduledSyncSkipException objectSyncLockConflict(String blockedObjectType) {
        return new ErpScheduledSyncSkipException(blockedObjectType,
                ScheduledSkipReason.OBJECT_SYNC_LOCK_CONFLICT);
    }

    public String blockedObjectType() {
        return blockedObjectType;
    }

    public ScheduledSkipReason reason() {
        return reason;
    }
}
