package com.rigour.erp.application.port.out;

import java.util.UUID;

/**
 * ERP 同步批次审计出站端口。
 *
 * <p>定时同步在业务动作开始前可能因多目标歧义、连接器租约或本地对象锁而跳过。
 * 该端口将这类“未开始但已做出调度决策”的结果写入现有同步批次台账，避免只留下短期日志。</p>
 */
public interface ErpSyncRunAuditStore {

    /**
     * 持久化一个已终止的定时调度跳过记录。
     *
     * @param tenantId 租户主键
     * @param connectorId Integration 连接器主键
     * @param sourceTaskId Integration 同步任务主键
     * @param blockedObjectType 本轮实际被阻塞的同步对象，例如 PRODUCT_SPU
     * @param maxPages 调度配置的最大页数
     * @param reason 受控、脱敏的跳过原因
     * @return 审计批次主键
     */
    UUID recordScheduledSkip(UUID tenantId, UUID connectorId, UUID sourceTaskId,
                             String blockedObjectType, int maxPages, ScheduledSkipReason reason);

    /** 可持久化的受控调度跳过原因，不接收上游原始异常文本。 */
    enum ScheduledSkipReason {
        /** 连接器租约在 ERP 业务动作开始前已被占用。 */
        CONNECTOR_LEASE_CONFLICT(
                "CONNECTOR_LEASE_CONFLICT",
                "连接器租约在ERP业务动作开始前已被占用，本对象本轮跳过"),
        /** ERP 本地同对象同步锁已被占用。 */
        OBJECT_SYNC_LOCK_CONFLICT(
                "OBJECT_SYNC_LOCK_CONFLICT",
                "ERP本地同对象同步锁已被占用，本对象本轮跳过"),
        /** 同租户发现多个活跃同步目标，无法唯一选择。 */
        MULTIPLE_ACTIVE_SYNC_TARGETS(
                "MULTIPLE_ACTIVE_SYNC_TARGETS",
                "同租户存在多个活跃同步目标，本任务本轮跳过");

        private final String code;
        private final String message;

        ScheduledSkipReason(String code, String message) {
            if (code.length() > 64 || message.length() > 500) {
                throw new IllegalArgumentException("ERP调度跳过原因超出安全长度");
            }
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }
}
