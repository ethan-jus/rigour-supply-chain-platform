-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__integration_dinghuobao.sql
-- Integration Schema V1：订货宝数据同步基础设施。
-- 所有业务事实按 tenant_id 隔离；本Schema不建立到IAM/Order的物理外键。

CREATE TABLE integration_dinghuobao_connector (
    id              BINARY(16)      NOT NULL,
    tenant_id       BINARY(16)      NOT NULL,
    connector_code  VARCHAR(64)     NOT NULL,
    connector_name  VARCHAR(128)    NOT NULL,
    base_url        VARCHAR(512)    NULL,
    auth_secret_ref VARCHAR(255)    NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL,
    created_by      BINARY(16)      NULL,
    updated_at      DATETIME(6)     NOT NULL,
    updated_by      BINARY(16)      NULL,
    deleted_at      DATETIME(6)     NULL,
    deleted_by      BINARY(16)      NULL,
    delete_reason   VARCHAR(512)    NULL,
    CONSTRAINT pk_integration_connector PRIMARY KEY (id),
    CONSTRAINT uk_integration_connector_tenant_code UNIQUE (tenant_id, connector_code),
    CONSTRAINT ck_integration_connector_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    INDEX idx_integration_connector_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝连接配置；Secret只存引用，不存明文';

CREATE TABLE integration_sync_task (
    id              BINARY(16)      NOT NULL,
    tenant_id       BINARY(16)      NOT NULL,
    connector_id    BINARY(16)      NOT NULL,
    task_code       VARCHAR(64)     NOT NULL,
    object_type     VARCHAR(64)     NOT NULL,
    task_status     VARCHAR(16)     NOT NULL DEFAULT 'IDLE',
    last_run_at     DATETIME(6)     NULL,
    next_run_at     DATETIME(6)     NULL,
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL,
    created_by      BINARY(16)      NULL,
    updated_at      DATETIME(6)     NOT NULL,
    updated_by      BINARY(16)      NULL,
    deleted_at      DATETIME(6)     NULL,
    deleted_by      BINARY(16)      NULL,
    delete_reason   VARCHAR(512)    NULL,
    CONSTRAINT pk_integration_sync_task PRIMARY KEY (id),
    CONSTRAINT uk_integration_sync_task_tenant_code UNIQUE (tenant_id, connector_id, task_code),
    CONSTRAINT ck_integration_sync_task_status CHECK (
        task_status IN ('IDLE', 'RUNNING', 'PAUSED', 'FAILED', 'COMPLETED')
    ),
    INDEX idx_integration_sync_task_tenant (tenant_id, task_status),
    INDEX idx_integration_sync_task_next_run (tenant_id, next_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝同步任务定义';

CREATE TABLE integration_raw_landing (
    id                  BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    source_system       VARCHAR(32)     NOT NULL,
    source_object_type  VARCHAR(64)     NOT NULL,
    source_id           VARCHAR(128)    NOT NULL,
    payload_json        JSON            NOT NULL,
    payload_checksum    CHAR(64)        NOT NULL,
    received_at         DATETIME(6)     NOT NULL,
    landing_status      VARCHAR(16)     NOT NULL DEFAULT 'RECEIVED',
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_raw_landing PRIMARY KEY (id),
    CONSTRAINT uk_integration_raw_landing_source UNIQUE (tenant_id, source_system, source_object_type, source_id),
    CONSTRAINT ck_integration_raw_landing_status CHECK (
        landing_status IN ('RECEIVED', 'PROCESSED', 'FAILED')
    ),
    INDEX idx_integration_raw_landing_tenant (tenant_id, source_system, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝原始回执，用于重放与对账';

CREATE TABLE integration_order_mirror (
    id              BINARY(16)      NOT NULL,
    tenant_id       BINARY(16)      NOT NULL,
    source_order_id VARCHAR(128)    NOT NULL,
    order_no        VARCHAR(128)    NOT NULL,
    source_status   VARCHAR(64)     NULL,
    amount          DECIMAL(18, 2)  NOT NULL DEFAULT 0,
    order_time      DATETIME(6)     NULL,
    raw_landing_id  BINARY(16)      NULL,
    mirror_status   VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL,
    created_by      BINARY(16)      NULL,
    updated_at      DATETIME(6)     NOT NULL,
    updated_by      BINARY(16)      NULL,
    CONSTRAINT pk_integration_order_mirror PRIMARY KEY (id),
    CONSTRAINT uk_integration_order_mirror_source UNIQUE (tenant_id, source_order_id),
    CONSTRAINT ck_integration_order_mirror_status CHECK (
        mirror_status IN ('ACTIVE', 'VOIDED')
    ),
    INDEX idx_integration_order_mirror_tenant (tenant_id, order_time),
    INDEX idx_integration_order_mirror_order_no (tenant_id, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝订单只读镜像，供数据管理与BI准备';

CREATE TABLE integration_sync_log (
    id          BINARY(16)      NOT NULL,
    tenant_id   BINARY(16)      NOT NULL,
    task_id     BINARY(16)      NOT NULL,
    run_id      BINARY(16)      NOT NULL,
    log_level   VARCHAR(16)     NOT NULL DEFAULT 'INFO',
    message     VARCHAR(2000)   NOT NULL,
    error_code  VARCHAR(64)     NULL,
    occurred_at DATETIME(6)     NOT NULL,
    CONSTRAINT pk_integration_sync_log PRIMARY KEY (id),
    CONSTRAINT ck_integration_sync_log_level CHECK (
        log_level IN ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR')
    ),
    INDEX idx_integration_sync_log_tenant_task (tenant_id, task_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='同步日志；错误信息不得包含Secret或Raw Payload';

CREATE TABLE integration_field_mapping (
    id               BINARY(16)      NOT NULL,
    tenant_id        BINARY(16)      NOT NULL,
    connector_id     BINARY(16)      NOT NULL,
    source_field     VARCHAR(128)    NOT NULL,
    target_field     VARCHAR(128)    NOT NULL,
    transform_type   VARCHAR(32)     NOT NULL DEFAULT 'DIRECT',
    enabled          TINYINT UNSIGNED NOT NULL DEFAULT 1,
    version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at       DATETIME(6)     NOT NULL,
    created_by       BINARY(16)      NULL,
    updated_at       DATETIME(6)     NOT NULL,
    updated_by       BINARY(16)      NULL,
    deleted_at       DATETIME(6)     NULL,
    deleted_by       BINARY(16)      NULL,
    delete_reason    VARCHAR(512)    NULL,
    CONSTRAINT pk_integration_field_mapping PRIMARY KEY (id),
    CONSTRAINT uk_integration_field_mapping_source UNIQUE (tenant_id, connector_id, source_field),
    CONSTRAINT ck_integration_field_mapping_transform CHECK (
        transform_type IN ('DIRECT', 'CONSTANT', 'EXPRESSION', 'DICTIONARY')
    ),
    INDEX idx_integration_field_mapping_tenant (tenant_id, connector_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝到订单镜像的字段映射';

-- Source: V2__integration_sync_runtime.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一次同步执行批次及其计数';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='每个租户任务的增量游标，成功后原子推进';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='同步失败死信及人工/自动重放队列';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='归一化领域事件出站；下游订单/BI只消费内部事件';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部与内部事实差异的可处理核对单';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='按租户和业务域记录外部/内部事实主权';

-- Source: V3__integration_dhb_short_names.sql
-- Integration Schema V3：统一订货宝运行时连接表短名称。
-- V1/V2 已执行的迁移文件不改写；本迁移只迁移当前表名。

RENAME TABLE integration_dinghuobao_connector TO integration_dhb_connector;

-- Source: V4__integration_default_order_sync_tasks.sql
-- Integration Schema V4：为已有启用连接器补齐默认订单同步任务。
-- 新连接器由 Integration 仓储在同一事务内自动创建默认任务；本迁移只负责存量数据。
-- 已存在任意未删除 ORDER 任务的连接器不重复创建，保留原有暂停/自定义配置。

INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_ORDER_DEFAULT', 'ORDER', 'IDLE',
       NULL, 1, UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id = c.tenant_id
   AND t.connector_id = c.id
   AND t.object_type = 'ORDER'
   AND t.deleted_at IS NULL
 WHERE c.status = 'ACTIVE'
   AND c.deleted_at IS NULL
   AND t.id IS NULL;

-- Source: V5__integration_default_product_master_tasks.sql
-- Integration Schema V5：为已有启用连接器补齐默认商品主数据同步目标。
-- ERP 只通过该任务发现连接器；订货宝协议、凭据和 Raw Landing 仍归 Integration 所有。

INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_PRODUCT_MASTER_DEFAULT',
       'PRODUCT_MASTER_DATA', 'IDLE', NULL, 1,
       UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id = c.tenant_id
   AND t.connector_id = c.id
   AND t.object_type = 'PRODUCT_MASTER_DATA'
   AND t.deleted_at IS NULL
 WHERE c.status = 'ACTIVE'
   AND c.deleted_at IS NULL
   AND t.id IS NULL;

-- Source: V6__integration_default_supply_chain_tasks.sql
-- Integration V6：为已有连接器补齐 ERP 供应商、采购、仓储和库存统一同步目标。
INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_SUPPLY_CHAIN_DEFAULT',
       'SUPPLY_CHAIN_DATA', 'IDLE', NULL, 1,
       UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id=c.tenant_id
   AND t.connector_id=c.id
   AND t.object_type='SUPPLY_CHAIN_DATA'
   AND t.deleted_at IS NULL
 WHERE c.status='ACTIVE' AND c.deleted_at IS NULL AND t.id IS NULL;

-- Source: V7__integration_product_media_jobs.sql
-- Integration Schema V7：商品图片异步上传任务及可恢复的逐图状态。
-- 商品原始字段仍由订货宝连接器负责读取；ERP 只在任务完成后读取 object key。

CREATE TABLE integration_product_media_job (
    id               BINARY(16)      NOT NULL,
    tenant_id        BINARY(16)      NOT NULL,
    connector_id     BINARY(16)      NOT NULL,
    status           VARCHAR(16)     NOT NULL DEFAULT 'QUEUED',
    total_images     BIGINT UNSIGNED NOT NULL DEFAULT 0,
    completed_images BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_images    BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_code       VARCHAR(64)     NULL,
    error_message    VARCHAR(2000)   NULL,
    created_at       DATETIME(6)     NOT NULL,
    created_by       BINARY(16)      NULL,
    started_at       DATETIME(6)     NULL,
    finished_at      DATETIME(6)     NULL,
    updated_at       DATETIME(6)     NOT NULL,
    version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_product_media_job PRIMARY KEY (id),
    CONSTRAINT ck_integration_product_media_job_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    INDEX idx_integration_product_media_job_tenant (
        tenant_id, connector_id, created_at
    ),
    INDEX idx_integration_product_media_job_status (
        status, updated_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品图片异步上传批次';

CREATE TABLE integration_product_media_item (
    id                  BINARY(16)      NOT NULL,
    job_id              BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    connector_id        BINARY(16)      NOT NULL,
    source_product_id   VARCHAR(128)    NOT NULL,
    source_resource_id  VARCHAR(128)    NULL,
    source_goods_id     VARCHAR(128)    NULL,
    source_url          VARCHAR(2048)   NULL,
    original_name       VARCHAR(255)    NULL,
    source_file_name    VARCHAR(512)    NULL,
    sort_order          INT             NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    object_key          VARCHAR(512)    NULL,
    content_type        VARCHAR(128)    NULL,
    attempts            INT UNSIGNED    NOT NULL DEFAULT 0,
    next_retry_at       DATETIME(6)     NULL,
    error_code          VARCHAR(64)     NULL,
    error_message       VARCHAR(2000)   NULL,
    created_at          DATETIME(6)     NOT NULL,
    updated_at          DATETIME(6)     NOT NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_product_media_item PRIMARY KEY (id),
    CONSTRAINT fk_integration_product_media_item_job FOREIGN KEY (job_id)
        REFERENCES integration_product_media_job (id),
    CONSTRAINT ck_integration_product_media_item_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    INDEX idx_integration_product_media_item_queue (
        status, next_retry_at, created_at
    ),
    INDEX idx_integration_product_media_item_job (
        job_id, status
    ),
    INDEX idx_integration_product_media_item_lookup (
        tenant_id, connector_id, job_id, source_product_id, source_resource_id, sort_order
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品图片逐项上传状态';

-- Source: V8__integration_product_media_reuse_index.sql
-- Integration Schema V8：支持跨历史图片任务复用成功快照时的高选择性查询。
CREATE INDEX idx_integration_product_media_item_reuse
    ON integration_product_media_item (
        tenant_id, connector_id, source_product_id, source_resource_id,
        status, updated_at, id
    );

-- Source: V9__integration_default_crm_master_tasks.sql
-- Integration V9：为已有启用连接器补齐 CRM 客户主数据同步目标。
INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_CRM_MASTER_DEFAULT',
       'CRM_MASTER_DATA', 'IDLE', NULL, 1,
       UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id=c.tenant_id
   AND t.connector_id=c.id
   AND t.object_type='CRM_MASTER_DATA'
   AND t.deleted_at IS NULL
 WHERE c.status='ACTIVE' AND c.deleted_at IS NULL AND t.id IS NULL;

-- Source: V10__integration_connector_sync_lease.sql
-- 订货宝连接器级分布式租约：统一约束ERP、CRM、Order及多实例对同一连接器的并发访问。
CREATE TABLE integration_connector_sync_lease (
    id            BINARY(16)   NOT NULL COMMENT '租约主键UUID',
    tenant_id     BINARY(16)   NOT NULL COMMENT '租户UUID',
    connector_id  BINARY(16)   NOT NULL COMMENT '订货宝连接器UUID',
    lease_token   CHAR(36)     NOT NULL COMMENT '本次持有者随机令牌，释放和续租必须精确匹配',
    owner_id      VARCHAR(128) NOT NULL COMMENT '持有租约的领域服务标识，仅用于审计',
    acquired_at   DATETIME(6)  NOT NULL COMMENT 'UTC首次获取时间',
    heartbeat_at  DATETIME(6)  NOT NULL COMMENT 'UTC最近续租时间',
    expires_at    DATETIME(6)  NOT NULL COMMENT 'UTC租约过期时间；进程退出后可自动回收',
    PRIMARY KEY (id),
    CONSTRAINT uk_integration_connector_sync_lease UNIQUE (tenant_id, connector_id),
    KEY idx_integration_connector_sync_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Integration订货宝连接器同步分布式租约';

-- Source: V11__raw_landing_connector_provenance.sql
-- integration_sync_task.connector_id 是可变当前状态，任务改绑后不能用于反推历史Raw来源。
-- 只有租户历史连接器记录总数始终为1时，connector_id才是可证明的唯一来源；否则保留NULL。
UPDATE integration_raw_landing raw
JOIN integration_dhb_connector connector
  ON connector.tenant_id = raw.tenant_id
LEFT JOIN integration_dhb_connector other
  ON other.tenant_id = connector.tenant_id AND other.id <> connector.id
SET raw.connector_id = connector.id
WHERE raw.connector_id IS NULL AND other.id IS NULL;

-- 回填结束后再重建唯一键，避免大表回填时逐行维护新六列索引。
-- 历史 connector_id 无法证明来源时保持 NULL；当前订货宝调用均必须携带连接器 UUID。
ALTER TABLE integration_raw_landing
    DROP INDEX uk_integration_raw_landing_revision,
    ADD CONSTRAINT uk_integration_raw_landing_revision UNIQUE (
        tenant_id, connector_id, source_system, source_object_type, source_id, payload_checksum
    );

-- 订单镜像和 Outbox 也必须延续相同来源身份，不能让第二个连接器覆盖第一个镜像。
ALTER TABLE integration_order_mirror
    ADD COLUMN connector_id BINARY(16) NULL AFTER tenant_id;

UPDATE integration_order_mirror mirror
JOIN integration_raw_landing raw
  ON raw.tenant_id = mirror.tenant_id AND raw.id = mirror.raw_landing_id
SET mirror.connector_id = raw.connector_id
WHERE mirror.connector_id IS NULL;

-- Raw也无法反推时，仅历史上始终只有一个连接器的租户可安全补齐；其余保持NULL并由API展示为未知来源。
UPDATE integration_order_mirror mirror
JOIN integration_dhb_connector connector
  ON connector.tenant_id = mirror.tenant_id
LEFT JOIN integration_dhb_connector other
  ON other.tenant_id = connector.tenant_id AND other.id <> connector.id
SET mirror.connector_id = connector.id
WHERE mirror.connector_id IS NULL AND other.id IS NULL;

ALTER TABLE integration_order_mirror
    DROP INDEX uk_integration_order_mirror_source,
    ADD CONSTRAINT uk_integration_order_mirror_source UNIQUE (
        tenant_id, connector_id, source_order_id
    ),
    ADD INDEX idx_integration_order_mirror_connector (
        tenant_id, connector_id, order_time
    );

-- Source: V12__external_object_mapping.sql
-- 外部对象映射表。
-- 订货宝等第三方系统只能通过本表映射到我们的 ERP/CRM/Order 业务对象；
-- 业务主表不得保存 dhb_*、source_*、run_id 等第三方流程字段，避免外部系统干扰自研流程。

CREATE TABLE integration_external_object_mapping (
    id                   BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    tenant_id            BINARY(16)      NOT NULL COMMENT '租户ID，来自Gateway签名上下文',
    connector_id         BINARY(16)      NULL COMMENT '订货宝连接器ID；历史或人工导入记录允许为空',
    source_system        VARCHAR(32)     NOT NULL COMMENT '外部系统编码，如DHB',
    source_object_type   VARCHAR(64)     NOT NULL COMMENT '外部对象类型，如PRODUCT/CUSTOMER/SALES_ORDER',
    source_object_id     VARCHAR(128)    NOT NULL COMMENT '外部对象ID',
    source_object_no     VARCHAR(128)    NULL COMMENT '外部对象编号，便于人工核对',
    internal_domain      VARCHAR(32)     NULL COMMENT '内部业务域：ERP/CRM/ORDER/DICTIONARY；未映射时为空',
    internal_object_type VARCHAR(64)     NULL COMMENT '内部对象类型，如PRODUCT/CUSTOMER/SALES_ORDER；未映射时为空',
    internal_object_id   BIGINT(20)      NULL COMMENT '内部对象ID；ERP/CRM/Order新业务表使用BIGINT主键',
    internal_object_no   VARCHAR(128)    NULL COMMENT '内部对象编号，便于人工核对',
    mapping_status       VARCHAR(24)     NOT NULL DEFAULT 'ACTIVE' COMMENT '映射状态：ACTIVE/REMOVED/CONFLICT/IGNORED',
    last_seen_run_id     BINARY(16)      NULL COMMENT '最近一次观察到该外部对象的同步批次ID',
    last_seen_at         DATETIME(6)     NULL COMMENT '最近一次观察到该外部对象的时间',
    source_deleted_at    DATETIME(6)     NULL COMMENT '检测到外部对象删除或不可见的时间；不等同于业务删除',
    payload_checksum     CHAR(64)        NULL COMMENT '最近一次外部原始数据摘要，用于判断是否变化',
    conflict_reason      VARCHAR(1000)   NULL COMMENT '冲突原因，映射状态为CONFLICT时用于运维排查',
    remark               VARCHAR(1000)   NULL COMMENT '备注',
    version              BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at           DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by           BINARY(16)      NULL COMMENT '创建人',
    updated_at           DATETIME(6)     NOT NULL COMMENT '更新时间',
    updated_by           BINARY(16)      NULL COMMENT '更新人',
    deleted_at           DATETIME(6)     NULL COMMENT '删除时间',
    deleted_by           BINARY(16)      NULL COMMENT '删除人',
    delete_reason        VARCHAR(512)    NULL COMMENT '删除原因',
    PRIMARY KEY (id),
    UNIQUE KEY uk_integration_external_source (
        tenant_id, connector_id, source_system, source_object_type, source_object_id
    ),
    KEY idx_integration_external_internal_no (
        tenant_id, internal_domain, internal_object_type, internal_object_no, deleted_at
    ),
    KEY idx_integration_external_status (
        tenant_id, source_system, mapping_status, updated_at
    ),
    KEY idx_integration_external_run (
        tenant_id, last_seen_run_id, last_seen_at
    ),
    CONSTRAINT ck_integration_external_mapping_status CHECK (
        mapping_status IN ('ACTIVE', 'REMOVED', 'CONFLICT', 'IGNORED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部对象与内部业务对象映射表';

-- Source: V13__integration_default_business_dictionary_tasks.sql
-- Integration V13：为已有启用连接器补齐字典/枚举映射同步目标。
-- 字典同步仍归同步中心统一编排，ERP/CRM/Order 业务页不直接展示订货宝技术字段。
INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_BUSINESS_DICTIONARY_DEFAULT',
       'BUSINESS_DICTIONARY', 'IDLE', NULL, 1,
       UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id = c.tenant_id
   AND t.connector_id = c.id
   AND t.object_type = 'BUSINESS_DICTIONARY'
   AND t.deleted_at IS NULL
 WHERE c.status = 'ACTIVE'
   AND c.deleted_at IS NULL
   AND t.id IS NULL;

-- Source: V14__upgrade_external_object_mapping_schema.sql
-- 将早期 integration_external_object_mapping 旧表结构升级为当前统一外部对象映射结构。
-- 背景：共享 DEV 曾应用过一个旧版 V12，表内使用 BIGINT/VARCHAR UUID、created_time/updated_time、deleted 等旧列。
-- 当前 MyBatis-Plus 实体和订货宝订单投影依赖 BINARY(16) UUID、connector_id、created_at/updated_at/deleted_at。

SET @needs_external_mapping_upgrade = (
    SELECT CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'integration_external_object_mapping'
       AND COLUMN_NAME = 'connector_id'
);

SET @backup_external_mapping_table_exists = (
    SELECT COUNT(*)
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'integration_external_object_mapping_legacy_v14'
);

SET @external_mapping_table_exists = (
    SELECT COUNT(*)
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'integration_external_object_mapping'
);

SET @rename_external_mapping_sql = IF(
    @needs_external_mapping_upgrade = 1 AND @backup_external_mapping_table_exists = 0,
    'RENAME TABLE integration_external_object_mapping TO integration_external_object_mapping_legacy_v14',
    'DO 0'
);
PREPARE rename_external_mapping_stmt FROM @rename_external_mapping_sql;
EXECUTE rename_external_mapping_stmt;
DEALLOCATE PREPARE rename_external_mapping_stmt;

CREATE TABLE IF NOT EXISTS integration_external_object_mapping (
    id                   BINARY(16)      NOT NULL COMMENT 'UUIDv7',
    tenant_id            BINARY(16)      NOT NULL COMMENT '租户ID，来自Gateway签名上下文',
    connector_id         BINARY(16)      NULL COMMENT '订货宝连接器ID；历史或人工导入记录允许为空',
    source_system        VARCHAR(32)     NOT NULL COMMENT '外部系统编码，如DHB',
    source_object_type   VARCHAR(64)     NOT NULL COMMENT '外部对象类型，如PRODUCT/CUSTOMER/SALES_ORDER',
    source_object_id     VARCHAR(128)    NOT NULL COMMENT '外部对象ID',
    source_object_no     VARCHAR(128)    NULL COMMENT '外部对象编号，便于人工核对',
    internal_domain      VARCHAR(32)     NULL COMMENT '内部业务域：ERP/CRM/ORDER/DICTIONARY；未映射时为空',
    internal_object_type VARCHAR(64)     NULL COMMENT '内部对象类型，如PRODUCT/CUSTOMER/SALES_ORDER；未映射时为空',
    internal_object_id   BIGINT(20)      NULL COMMENT '内部对象ID；ERP/CRM/Order新业务表使用BIGINT主键',
    internal_object_no   VARCHAR(128)    NULL COMMENT '内部对象编号，便于人工核对',
    mapping_status       VARCHAR(24)     NOT NULL DEFAULT 'ACTIVE' COMMENT '映射状态：ACTIVE/REMOVED/CONFLICT/IGNORED',
    last_seen_run_id     BINARY(16)      NULL COMMENT '最近一次观察到该外部对象的同步批次ID',
    last_seen_at         DATETIME(6)     NULL COMMENT '最近一次观察到该外部对象的时间',
    source_deleted_at    DATETIME(6)     NULL COMMENT '检测到外部对象删除或不可见的时间；不等同于业务删除',
    payload_checksum     CHAR(64)        NULL COMMENT '最近一次外部原始数据摘要，用于判断是否变化',
    conflict_reason      VARCHAR(1000)   NULL COMMENT '冲突原因，映射状态为CONFLICT时用于运维排查',
    remark               VARCHAR(1000)   NULL COMMENT '备注',
    version              BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at           DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by           BINARY(16)      NULL COMMENT '创建人',
    updated_at           DATETIME(6)     NOT NULL COMMENT '更新时间',
    updated_by           BINARY(16)      NULL COMMENT '更新人',
    deleted_at           DATETIME(6)     NULL COMMENT '删除时间',
    deleted_by           BINARY(16)      NULL COMMENT '删除人',
    delete_reason        VARCHAR(512)    NULL COMMENT '删除原因',
    PRIMARY KEY (id),
    UNIQUE KEY uk_integration_external_source (
        tenant_id, connector_id, source_system, source_object_type, source_object_id
    ),
    KEY idx_integration_external_internal_no (
        tenant_id, internal_domain, internal_object_type, internal_object_no, deleted_at
    ),
    KEY idx_integration_external_status (
        tenant_id, source_system, mapping_status, updated_at
    ),
    KEY idx_integration_external_run (
        tenant_id, last_seen_run_id, last_seen_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部对象与内部业务对象映射表';

SET @legacy_external_mapping_table_exists = (
    SELECT COUNT(*)
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'integration_external_object_mapping_legacy_v14'
);

SET @migrate_external_mapping_sql = IF(
    @needs_external_mapping_upgrade = 1 AND @legacy_external_mapping_table_exists = 1,
    'INSERT INTO integration_external_object_mapping (
        id, tenant_id, connector_id, source_system, source_object_type, source_object_id, source_object_no,
        internal_domain, internal_object_type, internal_object_id, internal_object_no, mapping_status,
        last_seen_run_id, last_seen_at, source_deleted_at, payload_checksum, conflict_reason, remark,
        version, created_at, created_by, updated_at, updated_by, deleted_at, deleted_by, delete_reason
     )
     SELECT
        UUID_TO_BIN(UUID()),
        UUID_TO_BIN(tenant_id),
        NULL,
        source_system,
        source_object_type,
        source_object_id,
        source_object_no,
        internal_domain,
        internal_object_type,
        internal_object_id,
        internal_object_no,
        mapping_status,
        CASE
            WHEN last_seen_run_id REGEXP ''^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$''
            THEN UUID_TO_BIN(last_seen_run_id)
            ELSE NULL
        END,
        last_seen_at,
        source_deleted_time,
        payload_checksum,
        NULL,
        remark,
        revision,
        created_time,
        CASE
            WHEN created_by REGEXP ''^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$''
            THEN UUID_TO_BIN(created_by)
            ELSE NULL
        END,
        updated_time,
        CASE
            WHEN updated_by REGEXP ''^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$''
            THEN UUID_TO_BIN(updated_by)
            ELSE NULL
        END,
        CASE WHEN deleted = 1 THEN updated_time ELSE NULL END,
        NULL,
        CASE WHEN deleted = 1 THEN ''LEGACY_DELETED_FLAG'' ELSE NULL END
       FROM integration_external_object_mapping_legacy_v14 legacy
      WHERE NOT EXISTS (
            SELECT 1
              FROM integration_external_object_mapping current_mapping
             WHERE current_mapping.tenant_id = UUID_TO_BIN(legacy.tenant_id)
               AND current_mapping.connector_id IS NULL
               AND current_mapping.source_system = legacy.source_system
               AND current_mapping.source_object_type = legacy.source_object_type
               AND current_mapping.source_object_id = legacy.source_object_id
      )',
    'DO 0'
);
PREPARE migrate_external_mapping_stmt FROM @migrate_external_mapping_sql;
EXECUTE migrate_external_mapping_stmt;
DEALLOCATE PREPARE migrate_external_mapping_stmt;

-- Source: V15__external_object_mapping_source_no_index.sql
-- 为外部对象编号兜底查询补索引。
-- 主链路仍优先使用 source_object_id；订单同步在订货宝只返回客户/商品编号时按 source_object_no 兜底查映射。

CREATE INDEX idx_integration_external_source_no
    ON integration_external_object_mapping (
        tenant_id, connector_id, source_system, source_object_type, source_object_no, mapping_status, deleted_at
    );

-- Source: V16__integration_dhb_orchestration_lease.sql
-- 订货宝统一编排批次租约：只约束同一租户连接器的编排批次，不占用领域服务自己的连接器租约。
CREATE TABLE integration_dhb_orchestration_lease (
    id            BINARY(16)   NOT NULL COMMENT '租约主键UUID',
    tenant_id     BINARY(16)   NOT NULL COMMENT '租户UUID',
    connector_id  BINARY(16)   NOT NULL COMMENT '订货宝连接器UUID',
    lease_token   CHAR(36)     NOT NULL COMMENT '本次持有者随机令牌，释放和续租必须精确匹配',
    owner_id      VARCHAR(128) NOT NULL COMMENT '持有租约的统一编排服务标识，仅用于审计',
    acquired_at   DATETIME(6)  NOT NULL COMMENT 'UTC首次获取时间',
    heartbeat_at  DATETIME(6)  NOT NULL COMMENT 'UTC最近续租时间',
    expires_at    DATETIME(6)  NOT NULL COMMENT 'UTC租约过期时间；进程退出后可自动回收',
    PRIMARY KEY (id),
    CONSTRAINT uk_integration_dhb_orchestration_lease UNIQUE (tenant_id, connector_id),
    KEY idx_integration_dhb_orchestration_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='Integration订货宝统一同步编排租约';

-- Source: V17__integration_manual_resolution.sql
-- Integration Schema V17：来源关系歧义的人工裁决入口。
--
-- 设计约束：
-- 1. 只处理无法由稳定来源字段自动判断的同步歧义，例如调拨出库匹配多个调拨入库。
-- 2. 人工裁决是明确输入和审计证据，不允许同步逻辑用时间最近等启发式猜测业务关系。
-- 3. Secret、Token、账号密码和完整Raw明文不得写入 evidence_json。

CREATE TABLE integration_manual_resolution (
    id                            BINARY(16)      NOT NULL,
    tenant_id                     BINARY(16)      NOT NULL,
    connector_id                  BINARY(16)      NOT NULL,
    source_system                 VARCHAR(32)     NOT NULL,
    resolution_type               VARCHAR(64)     NOT NULL,
    source_object_type            VARCHAR(64)     NOT NULL,
    source_id                     VARCHAR(160)    NOT NULL,
    selected_source_object_type   VARCHAR(64)     NOT NULL,
    selected_source_id            VARCHAR(160)    NOT NULL,
    selected_internal_object_type VARCHAR(64)     NULL,
    selected_internal_object_id   BIGINT UNSIGNED NULL,
    evidence_json                 JSON            NULL,
    reason                        VARCHAR(1000)   NOT NULL,
    status                        VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    version                       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at                    DATETIME(6)     NOT NULL,
    created_by                    BINARY(16)      NULL,
    updated_at                    DATETIME(6)     NOT NULL,
    updated_by                    BINARY(16)      NULL,
    CONSTRAINT pk_integration_manual_resolution PRIMARY KEY (id),
    CONSTRAINT ck_integration_manual_resolution_status CHECK (
        status IN ('ACTIVE', 'SUPERSEDED', 'CANCELLED')
    ),
    INDEX idx_integration_manual_resolution_lookup (
        tenant_id, source_system, connector_id, resolution_type, source_object_type, source_id, status
    ),
    INDEX idx_integration_manual_resolution_selected (
        tenant_id, source_system, selected_source_object_type, selected_source_id
    ),
    INDEX idx_integration_manual_resolution_queue (
        tenant_id, status, updated_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部来源关系歧义的人工裁决记录';

-- Source: V18__integration_feishu_import_batches.sql
-- 飞书导出数据导入批次。
-- Integration 先保存预检事实和附件缺口，正式投影到 CRM/ERP/Order/IAM 仍通过内部领域契约完成。

CREATE TABLE IF NOT EXISTS integration_feishu_import_batch (
    id                         BINARY(16)      NOT NULL COMMENT '导入批次ID',
    tenant_id                  BINARY(16)      NOT NULL COMMENT '租户ID',
    source_system              VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    source_url                 VARCHAR(2000)   NULL COMMENT '飞书Base或视图地址',
    original_file_name         VARCHAR(255)    NOT NULL COMMENT '上传文件名',
    file_size_bytes            BIGINT UNSIGNED NOT NULL COMMENT '文件大小',
    file_sha256                CHAR(64)        NOT NULL COMMENT '文件SHA-256摘要',
    status                     VARCHAR(32)     NOT NULL COMMENT '预检状态',
    total_sheets               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '工作表数量',
    total_rows                 BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '数据行数量',
    attachment_reference_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '附件引用数量',
    created_at                 DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by                 BINARY(16)      NULL COMMENT '创建人',
    updated_at                 DATETIME(6)     NOT NULL COMMENT '更新时间',
    updated_by                 BINARY(16)      NULL COMMENT '更新人',
    version                    BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_batch PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_batch_source CHECK (source_system = 'FEISHU'),
    CONSTRAINT ck_integration_feishu_import_batch_status CHECK (
        status IN ('PREFLIGHTED', 'PREFLIGHTED_WITH_WARNINGS', 'REJECTED', 'RUNNING',
                   'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')
    ),
    INDEX idx_integration_feishu_import_batch_tenant (
        tenant_id, created_at
    ),
    INDEX idx_integration_feishu_import_batch_file (
        tenant_id, file_sha256, created_at
    ),
    INDEX idx_integration_feishu_import_batch_status (
        tenant_id, status, updated_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入批次';

CREATE TABLE IF NOT EXISTS integration_feishu_import_table (
    id                         BINARY(16)      NOT NULL COMMENT '工作表预检ID',
    batch_id                   BINARY(16)      NOT NULL COMMENT '导入批次ID',
    tenant_id                  BINARY(16)      NOT NULL COMMENT '租户ID',
    sheet_name                 VARCHAR(255)    NOT NULL COMMENT '工作表名称',
    table_code                 VARCHAR(64)     NULL COMMENT '识别后的飞书表编码',
    domain_code                VARCHAR(32)     NULL COMMENT '内部业务域',
    object_type                VARCHAR(64)     NULL COMMENT '内部对象类型',
    mapping_status             VARCHAR(32)     NOT NULL COMMENT '映射预检状态',
    header_row_number          INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '表头行号',
    row_count                  BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '数据行数量',
    column_count               INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '列数量',
    attachment_reference_count BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '附件引用数量',
    headers_json               JSON            NOT NULL COMMENT '表头列表',
    attachment_fields_json     JSON            NOT NULL COMMENT '识别到的附件字段',
    created_at                 DATETIME(6)     NOT NULL COMMENT '创建时间',
    CONSTRAINT pk_integration_feishu_import_table PRIMARY KEY (id),
    CONSTRAINT fk_integration_feishu_import_table_batch FOREIGN KEY (batch_id)
        REFERENCES integration_feishu_import_batch (id),
    CONSTRAINT ck_integration_feishu_import_table_status CHECK (
        mapping_status IN ('READY', 'NEEDS_FIELD_MAPPING', 'UNMAPPED_TABLE')
    ),
    INDEX idx_integration_feishu_import_table_batch (
        batch_id, mapping_status
    ),
    INDEX idx_integration_feishu_import_table_domain (
        tenant_id, domain_code, object_type
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入工作表预检结果';

CREATE TABLE IF NOT EXISTS integration_feishu_import_issue (
    id            BINARY(16)    NOT NULL COMMENT '问题ID',
    batch_id      BINARY(16)    NOT NULL COMMENT '导入批次ID',
    tenant_id     BINARY(16)    NOT NULL COMMENT '租户ID',
    severity      VARCHAR(16)   NOT NULL COMMENT '严重级别',
    issue_type    VARCHAR(64)   NOT NULL COMMENT '问题类型',
    table_name    VARCHAR(255)  NULL COMMENT '工作表名称',
    source_row_number INT UNSIGNED  NULL COMMENT '来源行号',
    field_name    VARCHAR(255)  NULL COMMENT '字段名',
    message       VARCHAR(1000) NOT NULL COMMENT '问题说明',
    created_at    DATETIME(6)   NOT NULL COMMENT '创建时间',
    CONSTRAINT pk_integration_feishu_import_issue PRIMARY KEY (id),
    CONSTRAINT fk_integration_feishu_import_issue_batch FOREIGN KEY (batch_id)
        REFERENCES integration_feishu_import_batch (id),
    CONSTRAINT ck_integration_feishu_import_issue_severity CHECK (
        severity IN ('INFO', 'WARN', 'ERROR')
    ),
    INDEX idx_integration_feishu_import_issue_batch (
        batch_id, severity
    ),
    INDEX idx_integration_feishu_import_issue_tenant (
        tenant_id, severity, created_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入预检问题';

CREATE TABLE IF NOT EXISTS integration_feishu_import_raw_row (
    id                   BINARY(16)     NOT NULL COMMENT '原始行ID',
    batch_id             BINARY(16)     NOT NULL COMMENT '导入批次ID',
    table_id             BINARY(16)     NOT NULL COMMENT '工作表预检ID',
    tenant_id            BINARY(16)     NOT NULL COMMENT '租户ID',
    sheet_name           VARCHAR(255)   NOT NULL COMMENT '工作表名称',
    table_code           VARCHAR(64)    NULL COMMENT '识别后的飞书表编码',
    domain_code          VARCHAR(32)    NULL COMMENT '内部业务域',
    object_type          VARCHAR(64)    NULL COMMENT '内部对象类型',
    source_row_number    INT UNSIGNED   NOT NULL COMMENT '来源行号',
    source_document_no   VARCHAR(128)   NULL COMMENT '飞书来源单号',
    source_created_at    DATETIME(6)    NULL COMMENT '飞书来源创建时间',
    raw_row_hash         CHAR(64)       NOT NULL COMMENT '原始行SHA-256摘要',
    row_json             JSON           NOT NULL COMMENT '飞书原始行字段',
    attachment_refs_json JSON           NOT NULL COMMENT '飞书附件引用',
    import_status        VARCHAR(32)    NOT NULL DEFAULT 'IMPORTED' COMMENT '原始导入状态',
    projection_status    VARCHAR(32)    NOT NULL DEFAULT 'PENDING' COMMENT '领域投影状态',
    target_domain        VARCHAR(32)    NULL COMMENT '目标业务域',
    target_object_type   VARCHAR(64)    NULL COMMENT '目标对象类型',
    target_id            VARCHAR(64)    NULL COMMENT '目标业务对象ID',
    error_code           VARCHAR(64)    NULL COMMENT '失败或待映射编码',
    error_message        VARCHAR(1000)  NULL COMMENT '失败或待映射说明',
    created_at           DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at           DATETIME(6)    NOT NULL COMMENT '更新时间',
    updated_by           BINARY(16)     NULL COMMENT '更新人',
    version              BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_raw_row PRIMARY KEY (id),
    CONSTRAINT fk_integration_feishu_import_raw_row_batch FOREIGN KEY (batch_id)
        REFERENCES integration_feishu_import_batch (id),
    CONSTRAINT fk_integration_feishu_import_raw_row_table FOREIGN KEY (table_id)
        REFERENCES integration_feishu_import_table (id),
    CONSTRAINT ck_integration_feishu_import_raw_row_import_status CHECK (
        import_status IN ('IMPORTED', 'DROPPED')
    ),
    CONSTRAINT ck_integration_feishu_import_raw_row_projection_status CHECK (
        projection_status IN ('PENDING', 'PROJECTED', 'WAITING_MAPPING', 'SKIPPED', 'FAILED')
    ),
    UNIQUE KEY uk_integration_feishu_import_raw_row_source (
        tenant_id, batch_id, table_id, source_row_number
    ),
    KEY idx_integration_feishu_import_raw_row_batch_status (
        tenant_id, batch_id, projection_status
    ),
    KEY idx_integration_feishu_import_raw_row_source_no (
        tenant_id, table_code, source_document_no
    ),
    KEY idx_integration_feishu_import_raw_row_target (
        tenant_id, target_domain, target_object_type, target_id
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入原始行';

CREATE TABLE IF NOT EXISTS integration_feishu_import_field_mapping (
    id                 BINARY(16)    NOT NULL COMMENT '字段映射ID',
    tenant_id          BINARY(16)    NOT NULL COMMENT '租户ID',
    table_code         VARCHAR(64)   NOT NULL COMMENT '飞书表编码',
    source_field_name  VARCHAR(255)  NOT NULL COMMENT '飞书字段名',
    target_domain      VARCHAR(32)   NOT NULL COMMENT '目标业务域',
    target_object_type VARCHAR(64)   NOT NULL COMMENT '目标对象类型',
    target_field_name  VARCHAR(128)  NOT NULL COMMENT '目标字段名',
    mapping_kind       VARCHAR(32)   NOT NULL DEFAULT 'DIRECT' COMMENT '映射类型',
    required_flag      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否必填',
    enabled_flag       TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at         DATETIME(6)   NOT NULL COMMENT '创建时间',
    created_by         BINARY(16)    NULL COMMENT '创建人',
    updated_at         DATETIME(6)   NOT NULL COMMENT '更新时间',
    updated_by         BINARY(16)    NULL COMMENT '更新人',
    version            BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_field_mapping PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_field_mapping_kind CHECK (
        mapping_kind IN ('DIRECT', 'VALUE_MAP', 'EXPRESSION', 'LOOKUP')
    ),
    UNIQUE KEY uk_integration_feishu_import_field_mapping_source (
        tenant_id, table_code, source_field_name, target_domain, target_object_type,
        target_field_name
    ),
    KEY idx_integration_feishu_import_field_mapping_table (
        tenant_id, table_code, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入字段映射配置';

-- Source: V19__integration_feishu_import_templates_and_dedup.sql
-- 飞书导入模板、依赖图和原始行去重信息。
-- 模板负责识别 Excel 工作表、字段、依赖和去重策略；领域表写入仍通过各业务服务完成。

ALTER TABLE integration_feishu_import_batch
    ADD COLUMN duplicate_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重复行数量' AFTER total_rows;

ALTER TABLE integration_feishu_import_table
    ADD COLUMN duplicate_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重复行数量' AFTER row_count;

ALTER TABLE integration_feishu_import_raw_row
    ADD COLUMN deduplication_key VARCHAR(255) NULL COMMENT '导入去重键' AFTER raw_row_hash,
    ADD COLUMN duplicate_scope VARCHAR(32) NULL COMMENT '重复范围' AFTER deduplication_key,
    ADD COLUMN duplicate_of_raw_row_id BINARY(16) NULL COMMENT '重复来源原始行ID' AFTER duplicate_scope;

ALTER TABLE integration_feishu_import_raw_row
    ADD INDEX idx_integration_feishu_import_raw_row_dedup (
        tenant_id, deduplication_key, created_at
    ),
    ADD INDEX idx_integration_feishu_import_raw_row_import_projection (
        tenant_id, batch_id, import_status, projection_status
    );

CREATE TABLE IF NOT EXISTS integration_feishu_import_template (
    id                           BINARY(16)      NOT NULL COMMENT '模板ID',
    tenant_id                    BINARY(16)      NULL COMMENT '租户ID，空表示全局模板',
    source_system                VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    template_code                VARCHAR(64)     NOT NULL COMMENT '模板编码',
    template_name                VARCHAR(128)    NOT NULL COMMENT '模板名称',
    domain_code                  VARCHAR(32)     NOT NULL COMMENT '目标业务域',
    object_type                  VARCHAR(64)     NOT NULL COMMENT '目标对象类型',
    aliases_json                 JSON            NOT NULL COMMENT '工作表别名',
    required_headers_json        JSON            NOT NULL COMMENT '必需字段',
    source_document_fields_json  JSON            NOT NULL COMMENT '来源单号候选字段',
    source_created_fields_json   JSON            NOT NULL COMMENT '来源创建时间候选字段',
    deduplication_strategy       VARCHAR(32)     NOT NULL DEFAULT 'SOURCE_DOCUMENT_NO' COMMENT '去重策略',
    deduplication_fields_json    JSON            NOT NULL COMMENT '字段组合去重字段',
    ready_by_default_flag        TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否默认可投影',
    enabled_flag                 TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark                       VARCHAR(500)    NULL COMMENT '备注',
    created_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_template PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_template_source CHECK (source_system = 'FEISHU'),
    CONSTRAINT ck_integration_feishu_import_template_dedup CHECK (
        deduplication_strategy IN ('SOURCE_DOCUMENT_NO', 'FIELD_VALUES', 'ROW_HASH')
    ),
    UNIQUE KEY uk_integration_feishu_import_template_code (
        tenant_id, source_system, template_code
    ),
    KEY idx_integration_feishu_import_template_domain (
        tenant_id, domain_code, object_type, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入模板';

CREATE TABLE IF NOT EXISTS integration_feishu_import_template_dependency (
    id                           BINARY(16)      NOT NULL COMMENT '依赖ID',
    tenant_id                    BINARY(16)      NULL COMMENT '租户ID，空表示全局依赖',
    source_system                VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    template_code                VARCHAR(64)     NOT NULL COMMENT '当前模板编码',
    depends_on_template_code     VARCHAR(64)     NOT NULL COMMENT '依赖模板编码',
    relation_kind                VARCHAR(64)     NOT NULL COMMENT '依赖关系类型',
    source_reference_fields_json JSON            NOT NULL COMMENT '当前表引用字段',
    target_reference_fields_json JSON            NOT NULL COMMENT '依赖表匹配字段',
    required_flag                TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否必需依赖',
    enabled_flag                 TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_template_dependency PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_template_dependency_source CHECK (source_system = 'FEISHU'),
    UNIQUE KEY uk_integration_feishu_import_template_dependency (
        tenant_id, source_system, template_code, depends_on_template_code, relation_kind
    ),
    KEY idx_integration_feishu_import_template_dependency_template (
        tenant_id, source_system, template_code, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入模板依赖';

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
) VALUES
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_STAFF', '飞书销售人员',
     'HR', 'EMPLOYEE',
     JSON_ARRAY('销售人员信息', '渡江战役团队管理'),
     JSON_ARRAY('姓名'),
     JSON_ARRAY('销售姓名', '人员编号', '员工编号', '员工编码', '工号', 'ID', '手机号', '姓名'),
     JSON_ARRAY('创建时间', '新建时间', '入职日期', '销售姓名'),
     'FIELD_VALUES',
     JSON_ARRAY('手机号', '姓名', '销售姓名'),
     1, 1, '人员主数据落 HR，IAM 只做认证授权'),
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_REGION', '飞书区域城市',
     'CRM', 'REGION',
     JSON_ARRAY('大区数据'),
     JSON_ARRAY(),
     JSON_ARRAY('城市人仓', '销售区域', '城市', '编号', 'ID'),
     JSON_ARRAY('创建时间', '新建时间', '日期'),
     'FIELD_VALUES',
     JSON_ARRAY('销售区域', '城市', '城市人仓'),
     1, 1, '区域和城市作为 CRM 主数据'),
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_STORE', '飞书门店',
     'CRM', 'STORE',
     JSON_ARRAY('门店信息库'),
     JSON_ARRAY('门店编码', '门店名称'),
     JSON_ARRAY('门店编码', '门店编码名称', '商家编号', '商家编号名称', 'ID'),
     JSON_ARRAY('创建时间', '新建时间', '合作日期', '最后更新时间'),
     'SOURCE_DOCUMENT_NO',
     JSON_ARRAY('门店编码', '门店名称'),
     1, 1, '门店先落 CRM，再供订单引用'),
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_CUSTOMER', '飞书商家',
     'CRM', 'CUSTOMER',
     JSON_ARRAY('商家库'),
     JSON_ARRAY('商家编号'),
     JSON_ARRAY('商家编号', '商家编号名称', '客户编号', 'ID'),
     JSON_ARRAY('创建时间', '新建时间', '合作日期', '最后更新时间'),
     'SOURCE_DOCUMENT_NO',
     JSON_ARRAY('商家编号', '商家名称'),
     1, 1, '商家先落 CRM，再供订单引用'),
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_PRODUCT', '飞书产品',
     'ERP', 'PRODUCT',
     JSON_ARRAY('产品信息库'),
     JSON_ARRAY('产品编码', '产品名称'),
     JSON_ARRAY('产品编码', '产品编码名称', '商品编号', '商品编码', 'SKU编码', 'ID'),
     JSON_ARRAY('创建时间', '新建时间', '最后更新时间'),
     'SOURCE_DOCUMENT_NO',
     JSON_ARRAY('产品编码', '产品名称', '规格'),
     1, 1, '商品先落 ERP，再供订单行引用'),
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', '飞书销售订单',
     'ORDER', 'SALES_ORDER',
     JSON_ARRAY('销售订单表', '武汉销售订单表'),
     JSON_ARRAY('订单编号', '创建时间'),
     JSON_ARRAY('订单编号', '订单编号门店', '来源单号', 'ID'),
     JSON_ARRAY('创建时间', '新建时间', '下单时间', '订单时间', '销售日期'),
     'SOURCE_DOCUMENT_NO',
     JSON_ARRAY('订单编号'),
     1, 1, '订单依赖 CRM 客户/门店和 ERP 商品');

INSERT INTO integration_feishu_import_template_dependency (
    id, tenant_id, source_system, template_code, depends_on_template_code, relation_kind,
    source_reference_fields_json, target_reference_fields_json, required_flag, enabled_flag
) VALUES
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', 'FEISHU_STORE',
     'ORDER_CUSTOMER',
     JSON_ARRAY('关联门店', '门店', '客户名称', '客户', '门店编码'),
     JSON_ARRAY('门店编码', '门店名称', '客户名称'),
     1, 1),
    (UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', 'FEISHU_PRODUCT',
     'ORDER_PRODUCT',
     JSON_ARRAY('订单产品', '产品编号', '产品名称', '商品名称', '商品编码', 'SKU编码'),
     JSON_ARRAY('产品编码', '产品名称', '商品名称', '规格'),
     1, 1);

-- Source: V20__integration_feishu_import_batch_archive_key_comment.sql
-- This version already exists in the shared development database history from
-- an earlier local iteration. Keep a no-op migration here so Flyway can resolve
-- the applied version consistently; current Feishu import schema backfill lives
-- in V21.

-- Source: V21__integration_feishu_import_templates_and_dedup_backfill.sql
-- Backfill Feishu import template, dependency and deduplication schema.
-- The shared development database previously recorded V19/V20 before the final
-- template and dedup schema was present, so this migration is deliberately
-- idempotent and safe on both fresh and partially migrated databases.

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_batch ADD COLUMN duplicate_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''重复行数量'' AFTER total_rows',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_batch'
      AND column_name = 'duplicate_rows'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_table ADD COLUMN duplicate_rows BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''重复行数量'' AFTER row_count',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_table'
      AND column_name = 'duplicate_rows'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD COLUMN deduplication_key VARCHAR(255) NULL COMMENT ''导入去重键'' AFTER raw_row_hash',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND column_name = 'deduplication_key'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD COLUMN duplicate_scope VARCHAR(32) NULL COMMENT ''重复范围'' AFTER deduplication_key',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND column_name = 'duplicate_scope'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD COLUMN duplicate_of_raw_row_id BINARY(16) NULL COMMENT ''重复来源原始行ID'' AFTER duplicate_scope',
        'SELECT 1'
    )
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND column_name = 'duplicate_of_raw_row_id'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD INDEX idx_integration_feishu_import_raw_row_dedup (tenant_id, deduplication_key, created_at)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND index_name = 'idx_integration_feishu_import_raw_row_dedup'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql := (
    SELECT IF(
        COUNT(*) = 0,
        'ALTER TABLE integration_feishu_import_raw_row ADD INDEX idx_integration_feishu_import_raw_row_import_projection (tenant_id, batch_id, import_status, projection_status)',
        'SELECT 1'
    )
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'integration_feishu_import_raw_row'
      AND index_name = 'idx_integration_feishu_import_raw_row_import_projection'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS integration_feishu_import_template (
    id                           BINARY(16)      NOT NULL COMMENT '模板ID',
    tenant_id                    BINARY(16)      NULL COMMENT '租户ID，空表示全局模板',
    source_system                VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    template_code                VARCHAR(64)     NOT NULL COMMENT '模板编码',
    template_name                VARCHAR(128)    NOT NULL COMMENT '模板名称',
    domain_code                  VARCHAR(32)     NOT NULL COMMENT '目标业务域',
    object_type                  VARCHAR(64)     NOT NULL COMMENT '目标对象类型',
    aliases_json                 JSON            NOT NULL COMMENT '工作表别名',
    required_headers_json        JSON            NOT NULL COMMENT '必需字段',
    source_document_fields_json  JSON            NOT NULL COMMENT '来源单号候选字段',
    source_created_fields_json   JSON            NOT NULL COMMENT '来源创建时间候选字段',
    deduplication_strategy       VARCHAR(32)     NOT NULL DEFAULT 'SOURCE_DOCUMENT_NO' COMMENT '去重策略',
    deduplication_fields_json    JSON            NOT NULL COMMENT '字段组合去重字段',
    ready_by_default_flag        TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '是否默认可投影',
    enabled_flag                 TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    remark                       VARCHAR(500)    NULL COMMENT '备注',
    created_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_template PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_template_source CHECK (source_system = 'FEISHU'),
    CONSTRAINT ck_integration_feishu_import_template_dedup CHECK (
        deduplication_strategy IN ('SOURCE_DOCUMENT_NO', 'FIELD_VALUES', 'ROW_HASH')
    ),
    UNIQUE KEY uk_integration_feishu_import_template_code (
        tenant_id, source_system, template_code
    ),
    KEY idx_integration_feishu_import_template_domain (
        tenant_id, domain_code, object_type, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入模板';

CREATE TABLE IF NOT EXISTS integration_feishu_import_template_dependency (
    id                           BINARY(16)      NOT NULL COMMENT '依赖ID',
    tenant_id                    BINARY(16)      NULL COMMENT '租户ID，空表示全局依赖',
    source_system                VARCHAR(32)     NOT NULL DEFAULT 'FEISHU' COMMENT '来源系统',
    template_code                VARCHAR(64)     NOT NULL COMMENT '当前模板编码',
    depends_on_template_code     VARCHAR(64)     NOT NULL COMMENT '依赖模板编码',
    relation_kind                VARCHAR(64)     NOT NULL COMMENT '依赖关系类型',
    source_reference_fields_json JSON            NOT NULL COMMENT '当前表引用字段',
    target_reference_fields_json JSON            NOT NULL COMMENT '依赖表匹配字段',
    required_flag                TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否必需依赖',
    enabled_flag                 TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用',
    created_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_at                   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    CONSTRAINT pk_integration_feishu_import_template_dependency PRIMARY KEY (id),
    CONSTRAINT ck_integration_feishu_import_template_dependency_source CHECK (source_system = 'FEISHU'),
    UNIQUE KEY uk_integration_feishu_import_template_dependency (
        tenant_id, source_system, template_code, depends_on_template_code, relation_kind
    ),
    KEY idx_integration_feishu_import_template_dependency_template (
        tenant_id, source_system, template_code, enabled_flag
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书导入模板依赖';

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_STAFF', '飞书销售人员',
       'HR', 'EMPLOYEE',
       JSON_ARRAY('销售人员信息', '渡江战役团队管理'),
       JSON_ARRAY('姓名'),
       JSON_ARRAY('销售姓名', '人员编号', '员工编号', '员工编码', '工号', 'ID', '手机号', '姓名'),
       JSON_ARRAY('创建时间', '新建时间', '入职日期', '销售姓名'),
       'FIELD_VALUES',
       JSON_ARRAY('手机号', '姓名', '销售姓名'),
       1, 1, '人员主数据落 HR，IAM 只做认证授权'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_SALES_STAFF'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_REGION', '飞书区域城市',
       'CRM', 'REGION',
       JSON_ARRAY('大区数据'),
       JSON_ARRAY(),
       JSON_ARRAY('城市人仓', '销售区域', '城市', '编号', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '日期'),
       'FIELD_VALUES',
       JSON_ARRAY('销售区域', '城市', '城市人仓'),
       1, 1, '区域和城市作为 CRM 主数据'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_REGION'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_STORE', '飞书门店',
       'CRM', 'STORE',
       JSON_ARRAY('门店信息库'),
       JSON_ARRAY('门店编码', '门店名称'),
       JSON_ARRAY('门店编码', '门店编码名称', '商家编号', '商家编号名称', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '合作日期', '最后更新时间'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('门店编码', '门店名称'),
       1, 1, '门店先落 CRM，再供订单引用'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_STORE'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_CUSTOMER', '飞书商家',
       'CRM', 'CUSTOMER',
       JSON_ARRAY('商家库'),
       JSON_ARRAY('商家编号'),
       JSON_ARRAY('商家编号', '商家编号名称', '客户编号', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '合作日期', '最后更新时间'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('商家编号', '商家名称'),
       1, 1, '商家先落 CRM，再供订单引用'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_CUSTOMER'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_PRODUCT', '飞书产品',
       'ERP', 'PRODUCT',
       JSON_ARRAY('产品信息库'),
       JSON_ARRAY('产品编码', '产品名称'),
       JSON_ARRAY('产品编码', '产品编码名称', '商品编号', '商品编码', 'SKU编码', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '最后更新时间'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('产品编码', '产品名称', '规格'),
       1, 1, '商品先落 ERP，再供订单行引用'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_PRODUCT'
);

INSERT INTO integration_feishu_import_template (
    id, tenant_id, source_system, template_code, template_name, domain_code, object_type,
    aliases_json, required_headers_json, source_document_fields_json, source_created_fields_json,
    deduplication_strategy, deduplication_fields_json, ready_by_default_flag, enabled_flag, remark
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', '飞书销售订单',
       'ORDER', 'SALES_ORDER',
       JSON_ARRAY('销售订单表', '武汉销售订单表'),
       JSON_ARRAY('订单编号', '创建时间'),
       JSON_ARRAY('订单编号', '订单编号门店', '来源单号', 'ID'),
       JSON_ARRAY('创建时间', '新建时间', '下单时间', '订单时间', '销售日期'),
       'SOURCE_DOCUMENT_NO',
       JSON_ARRAY('订单编号'),
       1, 1, '订单依赖 CRM 客户/门店和 ERP 商品'
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template
    WHERE tenant_id IS NULL AND source_system = 'FEISHU' AND template_code = 'FEISHU_SALES_ORDER'
);

INSERT INTO integration_feishu_import_template_dependency (
    id, tenant_id, source_system, template_code, depends_on_template_code, relation_kind,
    source_reference_fields_json, target_reference_fields_json, required_flag, enabled_flag
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', 'FEISHU_STORE',
       'ORDER_CUSTOMER',
       JSON_ARRAY('关联门店', '门店', '客户名称', '客户', '门店编码'),
       JSON_ARRAY('门店编码', '门店名称', '客户名称'),
       1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template_dependency
    WHERE tenant_id IS NULL
      AND source_system = 'FEISHU'
      AND template_code = 'FEISHU_SALES_ORDER'
      AND depends_on_template_code = 'FEISHU_STORE'
      AND relation_kind = 'ORDER_CUSTOMER'
);

INSERT INTO integration_feishu_import_template_dependency (
    id, tenant_id, source_system, template_code, depends_on_template_code, relation_kind,
    source_reference_fields_json, target_reference_fields_json, required_flag, enabled_flag
)
SELECT UUID_TO_BIN(UUID()), NULL, 'FEISHU', 'FEISHU_SALES_ORDER', 'FEISHU_PRODUCT',
       'ORDER_PRODUCT',
       JSON_ARRAY('订单产品', '产品编号', '产品名称', '商品名称', '商品编码', 'SKU编码'),
       JSON_ARRAY('产品编码', '产品名称', '商品名称', '规格'),
       1, 1
WHERE NOT EXISTS (
    SELECT 1 FROM integration_feishu_import_template_dependency
    WHERE tenant_id IS NULL
      AND source_system = 'FEISHU'
      AND template_code = 'FEISHU_SALES_ORDER'
      AND depends_on_template_code = 'FEISHU_PRODUCT'
      AND relation_kind = 'ORDER_PRODUCT'
);

-- Source: V22__integration_feishu_online_capture.sql
-- 只追加完整在线分页证据，不触发导入、附件下载或领域投影；时间字段统一 UTC。
CREATE TABLE integration_feishu_online_capture (
    id             BINARY(16)   NOT NULL COMMENT '采集证据ID',
    tenant_id      BINARY(16)   NOT NULL COMMENT '授权租户ID',
    actor_id       BINARY(16)   NOT NULL COMMENT '发起采集的用户ID',
    source_id      VARCHAR(128) NOT NULL COMMENT '服务端来源配置ID',
    source_name    VARCHAR(255) NOT NULL COMMENT '采集时来源名称',
    source_url     VARCHAR(2000) NOT NULL COMMENT '采集时来源Base地址',
    started_at     DATETIME(6)  NOT NULL COMMENT '开始时间UTC',
    completed_at   DATETIME(6)  NOT NULL COMMENT '完成时间UTC',
    complete       BOOLEAN      NOT NULL COMMENT '仅表示分页完整，不表示跨页原子快照',
    filtered       BOOLEAN      NOT NULL COMMENT '任一表指定view时为true',
    checksum       CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'payload_json UTF-8 SHA-256',
    record_count   INT          NOT NULL COMMENT '所有指定表采集行数',
    page_count     INT          NOT NULL COMMENT '记录请求总页数，不含授权请求',
    payload_json   LONGTEXT     NOT NULL COMMENT '规范化JSON，原始fields保留',
    CONSTRAINT pk_feishu_online_capture PRIMARY KEY (id),
    CONSTRAINT ck_feishu_online_capture_complete CHECK (complete = TRUE AND completed_at >= started_at),
    CONSTRAINT ck_feishu_online_capture_count CHECK (record_count BETWEEN 0 AND 20000 AND page_count BETWEEN 1 AND 200),
    CONSTRAINT ck_feishu_online_capture_json CHECK (JSON_VALID(payload_json)),
    CONSTRAINT ck_feishu_online_capture_size CHECK (OCTET_LENGTH(payload_json) <= 33554432),
    INDEX idx_feishu_online_capture_tenant (tenant_id, completed_at),
    INDEX idx_feishu_online_capture_source (tenant_id, source_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书在线只读对账证据';

-- Source: V23__integration_dictionary_source_mapping.sql
-- 来源枚举映射由 Integration 持有，标准字典仍通过 Settings API 读取。
CREATE TABLE integration_dictionary_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    source_system VARCHAR(24) NOT NULL,
    source_scope VARCHAR(120) NOT NULL COMMENT '来源表或同步对象范围',
    dictionary_code VARCHAR(50) NOT NULL,
    source_field VARCHAR(120) NOT NULL,
    source_value VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    source_value_hash BINARY(32) NOT NULL,
    target_dictionary_code VARCHAR(50) NULL,
    target_item_code VARCHAR(50) NULL,
    mapping_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    manual_override TINYINT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    first_seen DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(50) NOT NULL,
    updated_by VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_dictionary_source (tenant_id,source_system,source_scope,dictionary_code,source_field,source_value_hash),
    KEY idx_dictionary_mapping_pending (tenant_id,dictionary_code,mapping_status),
    CONSTRAINT ck_dictionary_mapping_status CHECK (mapping_status IN ('PENDING','MAPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户来源枚举映射及待映射值';

-- Source: V24__dhb_object_sync_checkpoint.sql
CREATE TABLE integration_dhb_object_checkpoint (
 tenant_id CHAR(36) NOT NULL,connector_id CHAR(36) NOT NULL,object_type VARCHAR(40) NOT NULL,
 successful_to DATETIME(6) NOT NULL,last_run_id CHAR(36) NOT NULL,updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,object_type)
);

-- Source: V25__dhb_page_sync_jobs.sql
CREATE TABLE integration_dhb_page_job (
    tenant_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    connector_id CHAR(36) NOT NULL,
    scope VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL,
    stage VARCHAR(300) NOT NULL,
    active_slot TINYINT NULL,
    started_at DATETIME(6) NOT NULL,
    heartbeat_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    result_json JSON NULL,
    PRIMARY KEY (tenant_id, job_id),
    UNIQUE KEY uk_dhb_page_active (tenant_id, connector_id, active_slot),
    KEY idx_dhb_page_latest (tenant_id, connector_id, scope, started_at)
);

-- Source: V26__dhb_incremental_work.sql
CREATE TABLE integration_dhb_incremental_work (
    tenant_id CHAR(36) NOT NULL,
    connector_id CHAR(36) NOT NULL,
    object_type VARCHAR(24) NOT NULL,
    source_id VARCHAR(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    payload JSON NOT NULL,
    source_updated_at DATETIME(6) NULL,
    applied TINYINT NOT NULL DEFAULT 0,
    checked_at DATETIME(6) NULL,
    retry_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, connector_id, object_type, source_id),
    KEY idx_dhb_work_pending (tenant_id, connector_id, object_type, applied, retry_at)
);

-- Source: V27__sync_schedule.sql
CREATE TABLE integration_sync_schedule (
 tenant_id CHAR(36) NOT NULL, scope_key VARCHAR(64) NOT NULL,
 enabled BOOLEAN NOT NULL, mode VARCHAR(20) NOT NULL, interval_minutes INT NULL, daily_time CHAR(5) NULL,
 version BIGINT NOT NULL DEFAULT 1, next_run_at DATETIME(6) NULL, running_job_id CHAR(36) NULL,
 heartbeat_at DATETIME(6) NULL, last_started_at DATETIME(6) NULL, last_finished_at DATETIME(6) NULL,
 last_status VARCHAR(24) NULL, last_message VARCHAR(500) NULL,
 updated_at DATETIME(6) NOT NULL, updated_by CHAR(36) NOT NULL,
 PRIMARY KEY (tenant_id,scope_key), KEY idx_schedule_due(enabled,next_run_at),
 CHECK (mode IN ('FIXED_DELAY','DAILY')),
 CHECK ((mode='FIXED_DELAY' AND interval_minutes BETWEEN 5 AND 1440) OR (mode='DAILY' AND daily_time IS NOT NULL))
);
CREATE TABLE integration_sync_schedule_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id CHAR(36) NOT NULL, scope_key VARCHAR(64) NOT NULL,
 version BIGINT NOT NULL, actor_id CHAR(36) NOT NULL, changed_at DATETIME(6) NOT NULL,
 enabled BOOLEAN NOT NULL, mode VARCHAR(20) NOT NULL, interval_minutes INT NULL, daily_time CHAR(5) NULL,
 UNIQUE KEY uk_schedule_audit(tenant_id,scope_key,version)
);

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
