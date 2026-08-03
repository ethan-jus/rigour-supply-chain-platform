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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝连接配置；Secret只存引用，不存明文';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝同步任务定义';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝原始回执，用于重放与对账';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝订单只读镜像，供数据管理与BI准备';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同步日志；错误信息不得包含Secret或Raw Payload';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝到订单镜像的字段映射';
