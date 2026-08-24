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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部对象与内部业务对象映射表';

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
