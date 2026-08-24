-- 定时调度可因多目标歧义、连接器租约或本地对象锁被阻塞，仍需在同一批次台账中可追溯。
ALTER TABLE erp_master_data_sync_run
    ADD COLUMN source_task_id CHAR(36) NULL
        COMMENT 'Integration同步任务UUID，用于追溯定时调度来源'
        AFTER connector_id,
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
        COMMENT '批次状态：RUNNING/SUCCEEDED/SUCCEEDED_WITH_WARNINGS/FAILED/SKIPPED',
    ADD CONSTRAINT ck_erp_sync_run_skipped_terminal
        CHECK (status <> 'SKIPPED' OR
               (source_task_id IS NOT NULL AND connector_id IS NOT NULL AND
                finished_at IS NOT NULL AND error_code IS NOT NULL AND error_message IS NOT NULL)),
    ADD KEY idx_erp_sync_run_source_task
        (tenant_id, source_task_id, started_at);
