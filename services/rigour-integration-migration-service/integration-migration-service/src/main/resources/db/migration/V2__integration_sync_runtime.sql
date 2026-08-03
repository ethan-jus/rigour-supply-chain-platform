-- Integration Schema V2：同步运行控制面、游标、失败重放、事件出站、对账和主权记录。
--
-- 设计约束：
-- 1. 每张表都带 tenant_id；Integration 不信任请求体中的租户，运行时由 Gateway 签名上下文提供。
-- 2. 第三方账号密码、API Key 和 Token 只保存 Secret 引用，绝不落库明文。
-- 3. Raw Landing 保留外部原始事实；下游业务服务通过 outbox 事件消费，不直接读第三方接口。
-- 4. 本迁移只增加能力，不修改已经执行过的 V1 文件；后续变更继续追加新的 Flyway 版本。

ALTER TABLE integration_dinghuobao_connector
    ADD COLUMN api_version VARCHAR(32) NULL AFTER base_url,
    ADD COLUMN credential_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER auth_secret_ref,
    ADD COLUMN last_checked_at DATETIME(6) NULL AFTER credential_status,
    ADD COLUMN last_error_code VARCHAR(64) NULL AFTER last_checked_at,
    ADD COLUMN last_error_message VARCHAR(2000) NULL AFTER last_error_code,
    ADD CONSTRAINT ck_integration_connector_credential_status CHECK (
        credential_status IN ('UNKNOWN', 'VALID', 'INVALID', 'EXPIRED')
    ),
    ADD INDEX idx_integration_connector_credential (
        tenant_id, status, credential_status, last_checked_at
    );

ALTER TABLE integration_sync_task
    ADD COLUMN schedule_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' AFTER task_status,
    ADD COLUMN schedule_expression VARCHAR(128) NULL AFTER schedule_type,
    ADD COLUMN batch_size INT UNSIGNED NOT NULL DEFAULT 100 AFTER schedule_expression,
    ADD COLUMN retry_limit INT UNSIGNED NOT NULL DEFAULT 3 AFTER batch_size,
    ADD COLUMN overlap_seconds INT UNSIGNED NOT NULL DEFAULT 300 AFTER retry_limit,
    ADD COLUMN enabled TINYINT UNSIGNED NOT NULL DEFAULT 1 AFTER overlap_seconds,
    ADD CONSTRAINT ck_integration_sync_task_schedule_type CHECK (
        schedule_type IN ('MANUAL', 'FIXED_DELAY', 'CRON')
    ),
    ADD CONSTRAINT ck_integration_sync_task_batch_size CHECK (batch_size BETWEEN 1 AND 10000),
    ADD CONSTRAINT ck_integration_sync_task_enabled CHECK (enabled IN (0, 1)),
    ADD INDEX idx_integration_sync_task_schedule (
        tenant_id, enabled, schedule_type, next_run_at
    );

ALTER TABLE integration_raw_landing
    DROP INDEX uk_integration_raw_landing_source,
    ADD COLUMN connector_id BINARY(16) NULL AFTER tenant_id,
    ADD COLUMN run_id BINARY(16) NULL AFTER connector_id,
    ADD COLUMN source_version VARCHAR(128) NULL AFTER source_id,
    ADD COLUMN source_updated_at DATETIME(6) NULL AFTER source_version,
    ADD COLUMN processed_at DATETIME(6) NULL AFTER landing_status,
    ADD COLUMN error_code VARCHAR(64) NULL AFTER processed_at,
    ADD COLUMN error_message VARCHAR(2000) NULL AFTER error_code,
    ADD COLUMN attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER error_message,
    ADD COLUMN last_attempt_at DATETIME(6) NULL AFTER attempts,
    ADD CONSTRAINT uk_integration_raw_landing_revision UNIQUE (
        tenant_id, source_system, source_object_type, source_id, payload_checksum
    ),
    ADD INDEX idx_integration_raw_landing_run (
        tenant_id, connector_id, run_id, received_at
    ),
    ADD INDEX idx_integration_raw_landing_source_time (
        tenant_id, source_object_type, source_updated_at
    );

CREATE TABLE integration_sync_run (
    id              BINARY(16)      NOT NULL,
    tenant_id       BINARY(16)      NOT NULL,
    task_id         BINARY(16)      NOT NULL,
    trigger_type    VARCHAR(16)     NOT NULL DEFAULT 'MANUAL',
    status          VARCHAR(16)     NOT NULL DEFAULT 'QUEUED',
    cursor_before   VARCHAR(1024)   NULL,
    cursor_after    VARCHAR(1024)   NULL,
    window_from     DATETIME(6)     NULL,
    window_to       DATETIME(6)     NULL,
    fetched_count   BIGINT UNSIGNED NOT NULL DEFAULT 0,
    accepted_count  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    duplicate_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    rejected_count  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    started_at      DATETIME(6)     NULL,
    finished_at     DATETIME(6)     NULL,
    error_code      VARCHAR(64)     NULL,
    error_message   VARCHAR(2000)   NULL,
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL,
    created_by      BINARY(16)      NULL,
    updated_at      DATETIME(6)     NOT NULL,
    updated_by      BINARY(16)      NULL,
    CONSTRAINT pk_integration_sync_run PRIMARY KEY (id),
    CONSTRAINT ck_integration_sync_run_trigger CHECK (
        trigger_type IN ('SCHEDULED', 'MANUAL', 'RETRY', 'REPLAY')
    ),
    CONSTRAINT ck_integration_sync_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT ck_integration_sync_run_period CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    ),
    INDEX idx_integration_sync_run_task_time (tenant_id, task_id, created_at),
    INDEX idx_integration_sync_run_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次同步执行批次及其计数';

CREATE TABLE integration_sync_checkpoint (
    id                  BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    task_id             BINARY(16)      NOT NULL,
    cursor_type         VARCHAR(24)     NOT NULL DEFAULT 'TIME_WINDOW',
    cursor_value        VARCHAR(1024)   NULL,
    source_updated_at   DATETIME(6)     NULL,
    last_success_run_id BINARY(16)      NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL,
    created_by          BINARY(16)      NULL,
    updated_at          DATETIME(6)     NOT NULL,
    updated_by          BINARY(16)      NULL,
    CONSTRAINT pk_integration_sync_checkpoint PRIMARY KEY (id),
    CONSTRAINT uk_integration_sync_checkpoint_task UNIQUE (tenant_id, task_id),
    CONSTRAINT ck_integration_sync_checkpoint_cursor CHECK (
        cursor_type IN ('TIME_WINDOW', 'PAGE_TOKEN', 'SOURCE_VERSION')
    ),
    INDEX idx_integration_sync_checkpoint_updated (
        tenant_id, updated_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每个租户任务的增量游标，成功后原子推进';

CREATE TABLE integration_dead_letter (
    id                  BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    run_id              BINARY(16)      NULL,
    raw_landing_id      BINARY(16)      NULL,
    source_system       VARCHAR(32)     NOT NULL,
    source_object_type  VARCHAR(64)     NOT NULL,
    source_id           VARCHAR(128)    NOT NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'OPEN',
    attempts            INT UNSIGNED    NOT NULL DEFAULT 0,
    next_retry_at       DATETIME(6)     NULL,
    last_error_code     VARCHAR(64)     NULL,
    last_error_message  VARCHAR(2000)   NULL,
    resolved_at         DATETIME(6)     NULL,
    resolved_by         BINARY(16)      NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL,
    created_by          BINARY(16)      NULL,
    updated_at          DATETIME(6)     NOT NULL,
    updated_by          BINARY(16)      NULL,
    CONSTRAINT pk_integration_dead_letter PRIMARY KEY (id),
    CONSTRAINT ck_integration_dead_letter_status CHECK (
        status IN ('OPEN', 'REPLAYING', 'RESOLVED', 'IGNORED')
    ),
    INDEX idx_integration_dead_letter_queue (
        tenant_id, status, next_retry_at, created_at
    ),
    INDEX idx_integration_dead_letter_source (
        tenant_id, source_system, source_object_type, source_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同步失败死信及人工/自动重放队列';

CREATE TABLE integration_outbox_event (
    id              BINARY(16)      NOT NULL,
    tenant_id       BINARY(16)      NOT NULL,
    aggregate_type  VARCHAR(64)     NOT NULL,
    aggregate_id    BINARY(16)      NOT NULL,
    event_type      VARCHAR(128)    NOT NULL,
    event_key       VARCHAR(255)    NOT NULL,
    payload_json    JSON            NOT NULL,
    payload_checksum CHAR(64)        NOT NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    attempts        INT UNSIGNED    NOT NULL DEFAULT 0,
    available_at    DATETIME(6)     NOT NULL,
    published_at    DATETIME(6)     NULL,
    last_error      VARCHAR(2000)   NULL,
    created_at      DATETIME(6)     NOT NULL,
    updated_at      DATETIME(6)     NOT NULL,
    CONSTRAINT pk_integration_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_integration_outbox_event_key UNIQUE (tenant_id, event_key),
    CONSTRAINT ck_integration_outbox_event_status CHECK (
        status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD')
    ),
    INDEX idx_integration_outbox_event_queue (
        tenant_id, status, available_at, created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='归一化领域事件出站；下游订单/BI只消费内部事件';

CREATE TABLE integration_reconciliation_case (
    id                  BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    run_id              BINARY(16)      NULL,
    source_system       VARCHAR(32)     NOT NULL,
    source_object_type  VARCHAR(64)     NOT NULL,
    business_key        VARCHAR(160)    NOT NULL,
    check_type          VARCHAR(32)     NOT NULL,
    expected_value_json JSON            NULL,
    actual_value_json   JSON            NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'OPEN',
    severity            VARCHAR(16)     NOT NULL DEFAULT 'WARN',
    message             VARCHAR(2000)   NOT NULL,
    resolved_at         DATETIME(6)     NULL,
    resolved_by         BINARY(16)      NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL,
    created_by          BINARY(16)      NULL,
    updated_at          DATETIME(6)     NOT NULL,
    updated_by          BINARY(16)      NULL,
    CONSTRAINT pk_integration_reconciliation_case PRIMARY KEY (id),
    CONSTRAINT ck_integration_reconciliation_status CHECK (
        status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'IGNORED')
    ),
    CONSTRAINT ck_integration_reconciliation_severity CHECK (
        severity IN ('INFO', 'WARN', 'ERROR')
    ),
    INDEX idx_integration_reconciliation_queue (
        tenant_id, status, severity, created_at
    ),
    INDEX idx_integration_reconciliation_business (
        tenant_id, source_object_type, business_key
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部与内部事实差异的可处理核对单';

CREATE TABLE integration_domain_ownership (
    id                BINARY(16)      NOT NULL,
    tenant_id         BINARY(16)      NOT NULL,
    domain_code       VARCHAR(64)     NOT NULL,
    source_system     VARCHAR(32)     NOT NULL,
    ownership_mode    VARCHAR(32)     NOT NULL,
    effective_from    DATETIME(6)     NOT NULL,
    effective_to      DATETIME(6)     NULL,
    version           BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at        DATETIME(6)     NOT NULL,
    created_by        BINARY(16)      NULL,
    updated_at        DATETIME(6)     NOT NULL,
    updated_by        BINARY(16)      NULL,
    CONSTRAINT pk_integration_domain_ownership PRIMARY KEY (id),
    CONSTRAINT uk_integration_domain_ownership_period UNIQUE (
        tenant_id, domain_code, effective_from
    ),
    CONSTRAINT ck_integration_domain_ownership_mode CHECK (
        ownership_mode IN ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')
    ),
    CONSTRAINT ck_integration_domain_ownership_period CHECK (
        effective_to IS NULL OR effective_to > effective_from
    ),
    INDEX idx_integration_domain_ownership_current (
        tenant_id, domain_code, effective_from, effective_to
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按租户和业务域记录外部/内部事实主权';
