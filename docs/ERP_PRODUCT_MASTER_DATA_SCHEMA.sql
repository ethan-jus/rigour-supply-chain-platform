-- ERP 商品主数据最终模型（设计草案）
--
-- 重要：本文件只用于评审，不是 Flyway migration，也不会被服务自动执行。
-- 目标数据库：MySQL 8.x；命名和字段类型遵循现有 order-center migration 风格。
--
-- 设计原则：
-- 1. ERP 规范主数据与订货宝来源事实分离。
-- 2. Integration 保存 Raw Landing；ERP 不复制完整订货宝原始报文。
-- 3. source_binding 的 target_id 是多态引用，不能建立数据库外键，必须由 ERP 事务校验。
-- 4. tenant_id 由认证/网关上下文确定，所有写入和查询都必须带租户条件。

CREATE TABLE erp_product_spu (
    id                    CHAR(36)       NOT NULL COMMENT 'ERP SPU UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    spu_code              VARCHAR(128)   NOT NULL COMMENT 'ERP内部SPU编码',
    name                  VARCHAR(200)   NOT NULL COMMENT 'ERP商品名称',
    brand_id              CHAR(36)       NULL COMMENT 'erp_brand.id',
    base_unit             VARCHAR(40)    NULL COMMENT '基础单位',
    default_barcode       VARCHAR(160)   NULL COMMENT '来源商品默认条码，不作为全局唯一键',
    minimum_order         DECIMAL(20,6)  NULL COMMENT '最低订货量',
    minimum_order_unit    VARCHAR(32)    NULL COMMENT '最低订货量单位原值',
    ownership_state       VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY' COMMENT '数据主权状态',
    internal_status       VARCHAR(24)    NOT NULL DEFAULT 'DRAFT' COMMENT 'ERP内部状态',
    record_origin         VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED' COMMENT '记录来源',
    attributes_json       JSON           NULL COMMENT '暂未标准化的扩展属性，不替代规范列',
    version               BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by            CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by            CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at            DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at            DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_code UNIQUE (tenant_id, spu_code),
    CONSTRAINT ck_erp_product_spu_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_product_spu_status CHECK (internal_status IN
        ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_product_spu_origin CHECK (record_origin IN
        ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_product_spu_name (tenant_id, name),
    KEY idx_erp_product_spu_status (tenant_id, internal_status, updated_at),
    KEY idx_erp_product_spu_brand (tenant_id, brand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP规范商品/SPU主表';

CREATE TABLE erp_product_sku (
    id                         CHAR(36)       NOT NULL COMMENT 'ERP SKU UUID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    spu_id                     CHAR(36)       NOT NULL COMMENT 'erp_product_spu.id',
    sku_code                   VARCHAR(128)   NOT NULL COMMENT 'ERP内部SKU编码',
    barcode                    VARCHAR(160)   NULL COMMENT '商品条码，不假设全局唯一',
    unit                       VARCHAR(40)    NULL COMMENT '销售/库存单位',
    specification_summary      VARCHAR(500)   NULL COMMENT '展示用规格组合名称，结构化规格以关系表为准',
    attributes_json            JSON           NULL COMMENT '暂未标准化的扩展属性',
    ownership_state            VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY' COMMENT '数据主权状态',
    internal_status            VARCHAR(24)    NOT NULL DEFAULT 'DRAFT' COMMENT 'ERP内部状态',
    record_origin              VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED' COMMENT '记录来源',
    version                    BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by                 CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by                 CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at                 DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at                 DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_sku_code UNIQUE (tenant_id, sku_code),
    CONSTRAINT fk_erp_product_sku_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT ck_erp_product_sku_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_product_sku_status CHECK (internal_status IN
        ('DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_product_sku_origin CHECK (record_origin IN
        ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_product_sku_spu (tenant_id, spu_id),
    KEY idx_erp_product_sku_barcode (tenant_id, barcode),
    KEY idx_erp_product_sku_status (tenant_id, internal_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP规范SKU主表';

CREATE TABLE erp_category (
    id                    CHAR(36)       NOT NULL COMMENT 'ERP分类UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    parent_id             CHAR(36)       NULL COMMENT '父分类ID，根分类为空',
    category_code         VARCHAR(128)   NOT NULL COMMENT 'ERP分类编码',
    name                  VARCHAR(120)   NOT NULL COMMENT '分类名称',
    category_level        INT UNSIGNED  NOT NULL DEFAULT 1 COMMENT '冗余层级',
    sort_order             INT            NOT NULL DEFAULT 0 COMMENT '同级排序',
    status                 VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE' COMMENT '分类状态',
    ownership_state        VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY' COMMENT '数据主权状态',
    record_origin          VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED' COMMENT '记录来源',
    attributes_json        JSON           NULL COMMENT '扩展属性',
    version                BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by             CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by             CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_category_code UNIQUE (tenant_id, category_code),
    CONSTRAINT fk_erp_category_parent FOREIGN KEY (parent_id) REFERENCES erp_category (id),
    CONSTRAINT ck_erp_category_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_category_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_category_origin CHECK (record_origin IN ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_category_parent (tenant_id, parent_id, sort_order),
    KEY idx_erp_category_status (tenant_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP商品分类树';

CREATE TABLE erp_brand (
    id                    CHAR(36)       NOT NULL COMMENT 'ERP品牌UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    brand_code            VARCHAR(128)   NOT NULL COMMENT 'ERP品牌编码',
    name                  VARCHAR(120)   NOT NULL COMMENT '品牌名称',
    english_name          VARCHAR(160)   NULL COMMENT '英文名称',
    logo_object_key       VARCHAR(1000)  NULL COMMENT '我方COS私桶对象key，不保存外部URL',
    status                VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE' COMMENT '品牌状态',
    ownership_state       VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY' COMMENT '数据主权状态',
    record_origin         VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED' COMMENT '记录来源',
    attributes_json       JSON           NULL COMMENT '扩展属性',
    version               BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by            CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by            CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at            DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at            DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_brand_code UNIQUE (tenant_id, brand_code),
    CONSTRAINT ck_erp_brand_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_brand_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_brand_origin CHECK (record_origin IN ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_brand_name (tenant_id, name),
    KEY idx_erp_brand_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP品牌主表';

ALTER TABLE erp_product_spu
    ADD CONSTRAINT fk_erp_product_spu_brand FOREIGN KEY (brand_id) REFERENCES erp_brand (id);

CREATE TABLE erp_specification (
    id                    CHAR(36)       NOT NULL COMMENT 'ERP规格维度UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    specification_code    VARCHAR(128)   NOT NULL COMMENT 'ERP规格维度编码',
    name                  VARCHAR(120)   NOT NULL COMMENT '规格维度名称',
    status                VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE' COMMENT '规格维度状态',
    ownership_state       VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY' COMMENT '数据主权状态',
    record_origin         VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED' COMMENT '记录来源',
    attributes_json       JSON           NULL COMMENT '扩展属性',
    version               BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by            CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by            CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at            DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at            DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_specification_code UNIQUE (tenant_id, specification_code),
    CONSTRAINT ck_erp_specification_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_specification_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_specification_origin CHECK (record_origin IN ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_specification_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP规格维度主表';

CREATE TABLE erp_specification_value (
    id                    CHAR(36)       NOT NULL COMMENT 'ERP规格值UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    specification_id      CHAR(36)       NOT NULL COMMENT 'erp_specification.id',
    value_code            VARCHAR(128)   NOT NULL COMMENT 'ERP规格值编码',
    value_name            VARCHAR(120)   NOT NULL COMMENT '规格值名称',
    sort_order             INT            NOT NULL DEFAULT 0 COMMENT '展示排序',
    status                VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE' COMMENT '规格值状态',
    ownership_state       VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY' COMMENT '数据主权状态',
    record_origin         VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED' COMMENT '记录来源',
    attributes_json       JSON           NULL COMMENT '扩展属性',
    version               BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by            CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by            CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at            DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at            DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_specification_value_code UNIQUE (tenant_id, specification_id, value_code),
    CONSTRAINT fk_erp_specification_value_spec FOREIGN KEY (specification_id)
        REFERENCES erp_specification (id),
    CONSTRAINT ck_erp_specification_value_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_specification_value_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_specification_value_origin CHECK (record_origin IN ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_specification_value_name (tenant_id, specification_id, value_name),
    KEY idx_erp_specification_value_status (tenant_id, specification_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP规格值主表';

CREATE TABLE erp_product_spu_category (
    id                    CHAR(36)       NOT NULL COMMENT 'SPU分类关系UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    spu_id                CHAR(36)       NOT NULL COMMENT 'erp_product_spu.id',
    category_id           CHAR(36)       NOT NULL COMMENT 'erp_category.id',
    is_primary             TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否主分类',
    sort_order             INT            NOT NULL DEFAULT 0 COMMENT '展示排序',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_category UNIQUE (tenant_id, spu_id, category_id),
    CONSTRAINT fk_erp_product_spu_category_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_spu_category_category FOREIGN KEY (category_id) REFERENCES erp_category (id),
    KEY idx_erp_product_spu_category_primary (tenant_id, spu_id, is_primary, sort_order),
    KEY idx_erp_product_spu_category_category (tenant_id, category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP SPU与分类关系';

CREATE TABLE erp_product_spu_specification (
    id                    CHAR(36)       NOT NULL COMMENT 'SPU规格关系UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    spu_id                CHAR(36)       NOT NULL COMMENT 'erp_product_spu.id',
    specification_id      CHAR(36)       NOT NULL COMMENT 'erp_specification.id',
    sort_order             INT            NOT NULL DEFAULT 0 COMMENT '规格维度排序',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_specification UNIQUE (tenant_id, spu_id, specification_id),
    CONSTRAINT fk_erp_product_spu_specification_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_spu_specification_spec FOREIGN KEY (specification_id)
        REFERENCES erp_specification (id),
    KEY idx_erp_product_spu_specification_spec (tenant_id, specification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP SPU可用规格维度关系';

CREATE TABLE erp_product_sku_specification_value (
    id                    CHAR(36)       NOT NULL COMMENT 'SKU规格值关系UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    sku_id                CHAR(36)       NOT NULL COMMENT 'erp_product_sku.id',
    specification_id      CHAR(36)       NOT NULL COMMENT 'erp_specification.id',
    value_id              CHAR(36)       NOT NULL COMMENT 'erp_specification_value.id',
    sort_order             INT            NOT NULL DEFAULT 0 COMMENT '展示排序',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_sku_spec UNIQUE (tenant_id, sku_id, specification_id),
    CONSTRAINT uk_erp_product_sku_value UNIQUE (tenant_id, sku_id, value_id),
    CONSTRAINT fk_erp_product_sku_spec_value_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    CONSTRAINT fk_erp_product_sku_spec_value_spec FOREIGN KEY (specification_id)
        REFERENCES erp_specification (id),
    CONSTRAINT fk_erp_product_sku_spec_value_value FOREIGN KEY (value_id)
        REFERENCES erp_specification_value (id),
    KEY idx_erp_product_sku_spec_value_value (tenant_id, value_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP SKU与规格值关系';

CREATE TABLE erp_tag (
    id                    CHAR(36)       NOT NULL COMMENT 'ERP标签UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    tag_code              VARCHAR(128)   NOT NULL COMMENT 'ERP标签编码',
    name                  VARCHAR(120)   NOT NULL COMMENT '标签名称',
    color                 VARCHAR(32)    NULL COMMENT '展示颜色',
    status                VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE' COMMENT '标签状态',
    ownership_state       VARCHAR(32)    NOT NULL DEFAULT 'INTERNAL_ONLY' COMMENT '数据主权状态',
    record_origin         VARCHAR(16)    NOT NULL DEFAULT 'SELF_BUILT' COMMENT '记录来源',
    attributes_json       JSON           NULL COMMENT '扩展属性',
    version               BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_by            CHAR(36)       NULL COMMENT '创建人或服务ID',
    updated_by            CHAR(36)       NULL COMMENT '更新人或服务ID',
    created_at            DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at            DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_tag_code UNIQUE (tenant_id, tag_code),
    CONSTRAINT ck_erp_tag_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_erp_tag_ownership CHECK (ownership_state IN
        ('EXTERNAL_PRIMARY', 'SHADOW_VALIDATING', 'INTERNAL_PRIMARY_SYNC_BACK', 'INTERNAL_ONLY')),
    CONSTRAINT ck_erp_tag_origin CHECK (record_origin IN ('SELF_BUILT', 'IMPORTED', 'MIXED')),
    KEY idx_erp_tag_name (tenant_id, name),
    KEY idx_erp_tag_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP商品标签主表';

CREATE TABLE erp_product_spu_tag (
    id                    CHAR(36)       NOT NULL COMMENT 'SPU标签关系UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    spu_id                CHAR(36)       NOT NULL COMMENT 'erp_product_spu.id',
    tag_id                CHAR(36)       NOT NULL COMMENT 'erp_tag.id',
    assigned_by           CHAR(36)       NULL COMMENT '分配人或服务ID',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_tag UNIQUE (tenant_id, spu_id, tag_id),
    CONSTRAINT fk_erp_product_spu_tag_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_spu_tag_tag FOREIGN KEY (tag_id) REFERENCES erp_tag (id),
    KEY idx_erp_product_spu_tag_tag (tenant_id, tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP SPU与标签关系';

CREATE TABLE erp_master_data_sync_run (
    id                    CHAR(36)       NOT NULL COMMENT '同步批次UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    connector_id          CHAR(36)       NULL COMMENT 'Integration连接器ID',
    source_system         VARCHAR(32)    NOT NULL COMMENT '来源系统',
    object_type           VARCHAR(32)    NOT NULL COMMENT '同步对象类型',
    trigger_type          VARCHAR(16)    NOT NULL DEFAULT 'MANUAL' COMMENT '触发方式',
    status                VARCHAR(16)    NOT NULL DEFAULT 'RUNNING' COMMENT '批次状态',
    window_from           DATETIME(6)    NULL COMMENT '增量窗口起点',
    window_to             DATETIME(6)    NULL COMMENT '增量窗口终点',
    max_pages             INT UNSIGNED   NOT NULL DEFAULT 100 COMMENT '最大分页数',
    page_size             INT UNSIGNED   NOT NULL DEFAULT 100 COMMENT '单页大小',
    fetched_count         BIGINT         NOT NULL DEFAULT 0 COMMENT 'Integration返回行数',
    created_count         BIGINT         NOT NULL DEFAULT 0 COMMENT '新增数',
    changed_count         BIGINT         NOT NULL DEFAULT 0 COMMENT '变更数',
    duplicate_count       BIGINT         NOT NULL DEFAULT 0 COMMENT '重复数',
    rejected_count        BIGINT         NOT NULL DEFAULT 0 COMMENT '拒绝数',
    error_code            VARCHAR(64)    NULL COMMENT '脱敏稳定错误码',
    error_message         VARCHAR(2000)  NULL COMMENT '脱敏错误信息',
    started_at             DATETIME(6)    NOT NULL COMMENT '开始时间',
    finished_at            DATETIME(6)    NULL COMMENT '结束时间',
    created_by             CHAR(36)       NULL COMMENT '触发人或服务ID',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT ck_erp_master_data_sync_run_object CHECK (object_type IN
        ('PRODUCT_SPU', 'PRODUCT_SKU', 'CATEGORY', 'BRAND', 'SPECIFICATION',
         'SPECIFICATION_VALUE', 'TAG', 'MASTER_DATA')),
    CONSTRAINT ck_erp_master_data_sync_run_trigger CHECK (trigger_type IN
        ('MANUAL', 'SCHEDULED', 'RETRY')),
    CONSTRAINT ck_erp_master_data_sync_run_status CHECK (status IN
        ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'CANCELLED')),
    KEY idx_erp_master_data_sync_run_tenant_time (tenant_id, started_at),
    KEY idx_erp_master_data_sync_run_status (tenant_id, status, started_at),
    KEY idx_erp_master_data_sync_run_object (tenant_id, source_system, object_type, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP商品主数据同步批次';

CREATE TABLE erp_master_data_sync_checkpoint (
    id                    CHAR(36)       NOT NULL COMMENT '同步游标UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    connector_id          CHAR(36)       NOT NULL COMMENT 'Integration连接器ID',
    source_system         VARCHAR(32)    NOT NULL COMMENT '来源系统',
    object_type           VARCHAR(32)    NOT NULL COMMENT '同步对象类型',
    cursor_type           VARCHAR(24)    NOT NULL DEFAULT 'TIME_WINDOW' COMMENT '游标类型',
    cursor_value          VARCHAR(1024)  NULL COMMENT '来源游标或分页令牌',
    source_updated_at     DATETIME(6)    NULL COMMENT '已确认的来源更新时间',
    last_success_run_id   CHAR(36)       NULL COMMENT '最近成功批次ID',
    version               BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at             DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at             DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_data_sync_checkpoint UNIQUE
        (tenant_id, connector_id, source_system, object_type),
    CONSTRAINT ck_erp_master_data_sync_checkpoint_object CHECK (object_type IN
        ('PRODUCT_SPU', 'PRODUCT_SKU', 'CATEGORY', 'BRAND', 'SPECIFICATION',
         'SPECIFICATION_VALUE', 'TAG', 'MASTER_DATA')),
    CONSTRAINT ck_erp_master_data_sync_checkpoint_cursor CHECK (cursor_type IN
        ('TIME_WINDOW', 'PAGE_TOKEN', 'SOURCE_VERSION')),
    KEY idx_erp_master_data_sync_checkpoint_success (tenant_id, source_system, object_type, source_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP商品主数据增量同步游标';

CREATE TABLE erp_master_source_binding (
    id                    CHAR(36)       NOT NULL COMMENT '外部来源绑定UUID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    source_system         VARCHAR(32)    NOT NULL COMMENT '来源系统',
    source_object_type    VARCHAR(32)    NOT NULL COMMENT '来源对象类型',
    source_object_id      VARCHAR(128)   NOT NULL COMMENT '来源对象ID',
    target_type            VARCHAR(32)    NOT NULL COMMENT 'ERP目标对象类型',
    target_id              CHAR(36)       NOT NULL COMMENT 'ERP目标对象ID，多态引用',
    source_code            VARCHAR(128)   NULL COMMENT '来源编码快照',
    source_name            VARCHAR(200)   NULL COMMENT '来源名称快照',
    source_status          VARCHAR(32)    NULL COMMENT '来源状态原值',
    source_putaway         VARCHAR(16)    NULL COMMENT '订货宝上下架原值',
    source_updated_at      DATETIME(6)    NULL COMMENT '来源更新时间',
    source_payload_hash    CHAR(64)       NOT NULL COMMENT '归一化来源字段摘要',
    last_sync_run_id       CHAR(36)       NULL COMMENT '最近同步批次ID',
    synced_at              DATETIME(6)    NOT NULL COMMENT '最近成功处理时间',
    version                BIGINT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at              DATETIME(6)    NOT NULL COMMENT '创建时间',
    updated_at              DATETIME(6)    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_source_binding_source UNIQUE
        (tenant_id, source_system, source_object_type, source_object_id),
    CONSTRAINT uk_erp_master_source_binding_target UNIQUE
        (tenant_id, source_system, target_type, target_id),
    CONSTRAINT ck_erp_master_source_binding_source_type CHECK (source_object_type IN
        ('PRODUCT_SPU', 'PRODUCT_SKU', 'CATEGORY', 'BRAND', 'SPECIFICATION',
         'SPECIFICATION_VALUE', 'TAG')),
    CONSTRAINT ck_erp_master_source_binding_target_type CHECK (target_type IN
        ('SPU', 'SKU', 'CATEGORY', 'BRAND', 'SPECIFICATION', 'SPECIFICATION_VALUE', 'TAG')),
    KEY idx_erp_master_source_binding_target (tenant_id, target_type, target_id),
    KEY idx_erp_master_source_binding_code (tenant_id, source_system, source_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
COMMENT='ERP商品主数据外部来源绑定';
