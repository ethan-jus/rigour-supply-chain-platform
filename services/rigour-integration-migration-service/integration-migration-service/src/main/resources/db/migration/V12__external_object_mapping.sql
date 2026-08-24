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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部对象与内部业务对象映射表';
