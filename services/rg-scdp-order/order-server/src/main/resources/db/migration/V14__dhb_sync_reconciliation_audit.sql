-- 订单中心V14：将既有order_sync_run正式用于订单域同步审计，并增加逐对象完整性核对账。
-- 历史迁移只追加不改写；已有历史行按MANUAL/FULL/ALL兼容补值。

ALTER TABLE order_sync_run
    DROP CHECK ck_order_sync_run_status;

ALTER TABLE order_sync_run
    MODIFY COLUMN run_status VARCHAR(32) NOT NULL,
    ADD COLUMN connector_id CHAR(36) NULL COMMENT 'Integration订货宝连接器UUID' AFTER tenant_id,
    ADD COLUMN source_task_id CHAR(36) NULL COMMENT 'Integration同步任务UUID；SCHEDULED必填，MANUAL为空' AFTER connector_id,
    ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL或SCHEDULED' AFTER function_name,
    ADD COLUMN sync_mode VARCHAR(16) NOT NULL DEFAULT 'FULL' COMMENT 'FULL、INCREMENTAL或REPAIR' AFTER trigger_type,
    ADD COLUMN sync_scope VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '命令指定的订单域对象范围' AFTER sync_mode,
    ADD COLUMN completed_objects JSON NULL COMMENT '已完成或按策略明确跳过的对象集合' AFTER rejected_count,
    ADD COLUMN persisted_count INT NOT NULL DEFAULT 0 COMMENT '事务完成且幂等核验通过的对象数，包含未变化' AFTER completed_objects,
    ADD COLUMN changed_count INT NOT NULL DEFAULT 0 COMMENT '新增、来源变化、本地缺口修复或REPAIR强制重建对象数' AFTER persisted_count,
    ADD COLUMN skip_reason VARCHAR(1000) NULL COMMENT '策略或租约冲突跳过原因，已脱敏截断' AFTER error_message,
    ADD CONSTRAINT ck_order_sync_run_status
        CHECK (run_status IN ('RUNNING', 'SUCCEEDED', 'SUCCEEDED_WITH_WARNINGS', 'PARTIAL', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_order_sync_run_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    ADD CONSTRAINT ck_order_sync_run_source_task
        CHECK ((trigger_type = 'SCHEDULED' AND source_task_id IS NOT NULL)
            OR (trigger_type = 'MANUAL' AND source_task_id IS NULL)),
    ADD CONSTRAINT ck_order_sync_run_mode CHECK (sync_mode IN ('FULL', 'INCREMENTAL', 'REPAIR')),
    ADD UNIQUE KEY uk_order_sync_run_scope (id, tenant_id, connector_id),
    ADD KEY idx_order_sync_run_connector_time (tenant_id, connector_id, started_at),
    ADD KEY idx_order_sync_run_source_task_time (tenant_id, source_task_id, started_at);

ALTER TABLE order_dhb_sync_checkpoint
    ADD COLUMN last_full_success_at DATETIME(6) NULL
        COMMENT '最近一次FULL完整对账的窗口终点，UTC' AFTER last_success_at;

-- 不从旧last_success_at回填：历史值同时包含FULL和INCREMENTAL，不能作为FULL成功证据。
-- 保持NULL会使升级后首轮调度强制FULL，之后再与增量游标分别推进。

-- 兼容早期实现可能遗留的无finished_at终态，再启用强终态约束。
UPDATE order_sync_run
SET finished_at = started_at
WHERE run_status <> 'RUNNING' AND finished_at IS NULL;

ALTER TABLE order_sync_run
    ADD CONSTRAINT ck_order_sync_run_terminal_time
        CHECK (run_status = 'RUNNING' OR finished_at IS NOT NULL),
    ADD CONSTRAINT ck_order_sync_run_skip_reason
        CHECK (run_status <> 'SKIPPED'
            OR (finished_at IS NOT NULL AND skip_reason IS NOT NULL AND CHAR_LENGTH(TRIM(skip_reason)) > 0));

CREATE TABLE order_sync_reconciliation (
    id                         CHAR(36)      NOT NULL COMMENT '核对项UUID',
    run_id                     CHAR(36)      NOT NULL COMMENT 'order_sync_run.id',
    tenant_id                  VARCHAR(64)   NOT NULL COMMENT '可信租户ID，冗余用于强制隔离查询',
    connector_id               CHAR(36)      NOT NULL COMMENT 'Integration订货宝连接器UUID',
    object_type                VARCHAR(32)   NOT NULL COMMENT 'ORDER、SHIPMENT、SHIPMENT_LOGISTICS、RETURN、RECEIPT或PAYMENT',
    stage_status               VARCHAR(16)   NOT NULL COMMENT 'RUNNING、COLLECTED、PERSISTED、SUCCEEDED、FAILED或SKIPPED',
    expected_count             BIGINT        NULL COMMENT '来源首个分页声明总数；未收到有效分页时为空',
    fetched_count              BIGINT        NOT NULL DEFAULT 0 COMMENT '已成功取得的列表或逐单对象数',
    distinct_count             BIGINT        NOT NULL DEFAULT 0 COMMENT '按来源业务键去重后的对象数',
    raw_landed_count           BIGINT        NOT NULL DEFAULT 0 COMMENT 'Integration已完成对象Raw Landing后返回的数量',
    raw_detail_landed_count    BIGINT        NOT NULL DEFAULT 0 COMMENT 'Integration已完成详情Raw Landing后返回的数量',
    detail_expected_count      BIGINT        NOT NULL DEFAULT 0 COMMENT '应补拉详情对象数',
    detail_succeeded_count     BIGINT        NOT NULL DEFAULT 0 COMMENT '详情成功数',
    detail_failed_count        BIGINT        NOT NULL DEFAULT 0 COMMENT '详情失败数',
    persisted_count            BIGINT        NOT NULL DEFAULT 0 COMMENT '事务完成且幂等核验通过对象数，包含未变化',
    changed_count              BIGINT        NOT NULL DEFAULT 0 COMMENT '新增、来源变化、本地缺口修复或REPAIR强制重建对象数',
    failure_reason             VARCHAR(1000) NULL COMMENT '失败或策略跳过原因，已脱敏截断',
    started_at                 DATETIME(6)   NOT NULL COMMENT '本次run开始时间，UTC',
    finished_at                DATETIME(6)   NULL COMMENT '该对象终态时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT fk_order_sync_reconciliation_run
        FOREIGN KEY (run_id, tenant_id, connector_id)
        REFERENCES order_sync_run(id, tenant_id, connector_id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT ck_order_sync_reconciliation_status
        CHECK (stage_status IN ('RUNNING', 'COLLECTED', 'PERSISTED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_order_sync_reconciliation_terminal_time
        CHECK (stage_status IN ('RUNNING', 'COLLECTED', 'PERSISTED') OR finished_at IS NOT NULL),
    CONSTRAINT ck_order_sync_reconciliation_terminal_reason
        CHECK (stage_status NOT IN ('FAILED', 'SKIPPED')
            OR (failure_reason IS NOT NULL AND CHAR_LENGTH(TRIM(failure_reason)) > 0)),
    UNIQUE KEY uk_order_sync_reconciliation_object (run_id, object_type),
    KEY idx_order_sync_reconciliation_parent (run_id, tenant_id, connector_id),
    KEY idx_order_sync_reconciliation_tenant (tenant_id, started_at),
    KEY idx_order_sync_reconciliation_status (tenant_id, stage_status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订货宝订单域来源Raw到本地持久化逐对象核对账';
