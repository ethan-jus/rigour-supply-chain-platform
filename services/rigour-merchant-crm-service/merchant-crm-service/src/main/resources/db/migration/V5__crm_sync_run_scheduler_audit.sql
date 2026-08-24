ALTER TABLE crm_sync_run
    ADD COLUMN source_task_id BINARY(16) NULL
        COMMENT 'Integration同步任务ID；手动任务为空' AFTER trigger_type,
    ADD KEY idx_crm_sync_run_source_task (tenant_id, source_task_id, started_at),
    ADD CONSTRAINT chk_crm_sync_run_skipped_terminal CHECK (
        status <> 'SKIPPED'
        OR (source_task_id IS NOT NULL AND finished_at IS NOT NULL
            AND error_code IS NOT NULL AND error_message IS NOT NULL)
    );
