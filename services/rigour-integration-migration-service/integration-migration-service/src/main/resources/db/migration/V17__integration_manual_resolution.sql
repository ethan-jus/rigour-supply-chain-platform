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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部来源关系歧义的人工裁决记录';
