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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入批次';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入工作表预检结果';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入预检问题';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入原始行';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书导入字段映射配置';
