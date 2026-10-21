-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__erp_product_master_data.sql
-- ERP Core Schema V1：商品主数据规范模型与订货宝来源绑定。
-- Integration 是订货宝 Raw Landing 的唯一写者；ERP 只保存规范业务字段和幂等摘要。

CREATE TABLE erp_brand (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    brand_code         VARCHAR(128)  NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    english_name       VARCHAR(160)  NULL,
    logo_url           VARCHAR(1000) NULL,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         CHAR(36)      NULL,
    updated_by         CHAR(36)      NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_brand_code UNIQUE (tenant_id, brand_code),
    KEY idx_erp_brand_name (tenant_id, name),
    KEY idx_erp_brand_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP品牌主表';

CREATE TABLE erp_category (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    parent_id          CHAR(36)      NULL,
    category_code      VARCHAR(128)  NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    category_level     INT UNSIGNED  NOT NULL DEFAULT 1,
    sort_order         INT           NOT NULL DEFAULT 0,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         CHAR(36)      NULL,
    updated_by         CHAR(36)      NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_category_code UNIQUE (tenant_id, category_code),
    CONSTRAINT fk_erp_category_parent FOREIGN KEY (parent_id) REFERENCES erp_category (id),
    KEY idx_erp_category_parent (tenant_id, parent_id, sort_order),
    KEY idx_erp_category_status (tenant_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品分类树';

CREATE TABLE erp_specification (
    id                    CHAR(36)      NOT NULL,
    tenant_id             VARCHAR(64)   NOT NULL,
    specification_code    VARCHAR(128)  NOT NULL,
    name                  VARCHAR(120)  NOT NULL,
    status                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state       VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin         VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json       JSON          NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_by            CHAR(36)      NULL,
    updated_by            CHAR(36)      NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_specification_code UNIQUE (tenant_id, specification_code),
    KEY idx_erp_specification_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP规格维度主表';

CREATE TABLE erp_specification_value (
    id                    CHAR(36)      NOT NULL,
    tenant_id             VARCHAR(64)   NOT NULL,
    specification_id      CHAR(36)      NOT NULL,
    value_code            VARCHAR(128)  NOT NULL,
    value_name            VARCHAR(120)  NOT NULL,
    sort_order            INT           NOT NULL DEFAULT 0,
    status                VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state       VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin         VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json       JSON          NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_by            CHAR(36)      NULL,
    updated_by            CHAR(36)      NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_specification_value_code
        UNIQUE (tenant_id, specification_id, value_code),
    CONSTRAINT fk_erp_specification_value_spec FOREIGN KEY (specification_id)
        REFERENCES erp_specification (id),
    KEY idx_erp_specification_value_name (tenant_id, specification_id, value_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP规格值主表';

CREATE TABLE erp_tag (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    tag_code           VARCHAR(128)  NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    color              VARCHAR(32)   NULL,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         CHAR(36)      NULL,
    updated_by         CHAR(36)      NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_tag_code UNIQUE (tenant_id, tag_code),
    KEY idx_erp_tag_name (tenant_id, name),
    KEY idx_erp_tag_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品标签主表';

CREATE TABLE erp_product_spu (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    spu_code           VARCHAR(128)  NOT NULL,
    name               VARCHAR(200)  NOT NULL,
    brand_id           CHAR(36)      NULL,
    base_unit          VARCHAR(40)   NULL,
    default_barcode    VARCHAR(160)  NULL,
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    internal_status    VARCHAR(24)   NOT NULL DEFAULT 'DRAFT',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         CHAR(36)      NULL,
    updated_by         CHAR(36)      NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_code UNIQUE (tenant_id, spu_code),
    CONSTRAINT fk_erp_product_spu_brand FOREIGN KEY (brand_id) REFERENCES erp_brand (id),
    KEY idx_erp_product_spu_name (tenant_id, name),
    KEY idx_erp_product_spu_status (tenant_id, internal_status, updated_at),
    KEY idx_erp_product_spu_brand (tenant_id, brand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP规范商品SPU主表';

CREATE TABLE erp_product_sku (
    id                         CHAR(36)      NOT NULL,
    tenant_id                  VARCHAR(64)   NOT NULL,
    spu_id                     CHAR(36)      NOT NULL,
    sku_code                   VARCHAR(128)  NOT NULL,
    barcode                    VARCHAR(160)  NULL,
    unit                       VARCHAR(40)   NULL,
    specification_summary_json JSON          NULL,
    attributes_json            JSON          NULL,
    ownership_state            VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    internal_status            VARCHAR(24)   NOT NULL DEFAULT 'DRAFT',
    record_origin              VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    version                    BIGINT        NOT NULL DEFAULT 0,
    created_by                 CHAR(36)      NULL,
    updated_by                 CHAR(36)      NULL,
    created_at                 DATETIME(6)   NOT NULL,
    updated_at                 DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_sku_code UNIQUE (tenant_id, sku_code),
    CONSTRAINT fk_erp_product_sku_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    KEY idx_erp_product_sku_spu (tenant_id, spu_id),
    KEY idx_erp_product_sku_barcode (tenant_id, barcode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP规范SKU主表';

CREATE TABLE erp_product_spu_category (
    id          CHAR(36)     NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    spu_id      CHAR(36)     NOT NULL,
    category_id CHAR(36)     NOT NULL,
    is_primary  TINYINT(1)   NOT NULL DEFAULT 0,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_category UNIQUE (tenant_id, spu_id, category_id),
    CONSTRAINT fk_erp_product_spu_category_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_spu_category_category FOREIGN KEY (category_id) REFERENCES erp_category (id),
    KEY idx_erp_product_spu_category_primary (tenant_id, spu_id, is_primary)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP SPU分类关系';

CREATE TABLE erp_product_spu_specification (
    id               CHAR(36)     NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    spu_id           CHAR(36)     NOT NULL,
    specification_id CHAR(36)     NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_specification
        UNIQUE (tenant_id, spu_id, specification_id),
    CONSTRAINT fk_erp_product_spu_specification_spu FOREIGN KEY (spu_id)
        REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_spu_specification_spec FOREIGN KEY (specification_id)
        REFERENCES erp_specification (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP SPU规格维度关系';

CREATE TABLE erp_product_sku_specification_value (
    id               CHAR(36)     NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    sku_id           CHAR(36)     NOT NULL,
    specification_id CHAR(36)     NOT NULL,
    value_id         CHAR(36)     NOT NULL,
    sort_order       INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_sku_spec UNIQUE (tenant_id, sku_id, specification_id),
    CONSTRAINT uk_erp_product_sku_value UNIQUE (tenant_id, sku_id, value_id),
    CONSTRAINT fk_erp_product_sku_spec_value_sku FOREIGN KEY (sku_id)
        REFERENCES erp_product_sku (id),
    CONSTRAINT fk_erp_product_sku_spec_value_spec FOREIGN KEY (specification_id)
        REFERENCES erp_specification (id),
    CONSTRAINT fk_erp_product_sku_spec_value_value FOREIGN KEY (value_id)
        REFERENCES erp_specification_value (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP SKU规格值关系';

CREATE TABLE erp_product_spu_tag (
    id          CHAR(36)     NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL,
    spu_id      CHAR(36)     NOT NULL,
    tag_id      CHAR(36)     NOT NULL,
    assigned_by CHAR(36)     NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_spu_tag UNIQUE (tenant_id, spu_id, tag_id),
    CONSTRAINT fk_erp_product_spu_tag_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_spu_tag_tag FOREIGN KEY (tag_id) REFERENCES erp_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP SPU标签关系';

CREATE TABLE erp_master_data_sync_run (
    id               CHAR(36)      NOT NULL,
    tenant_id        VARCHAR(64)   NOT NULL,
    connector_id     CHAR(36)      NULL,
    source_system    VARCHAR(32)   NOT NULL,
    object_type      VARCHAR(32)   NOT NULL,
    trigger_type     VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',
    status           VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    window_from      DATETIME(6)   NULL,
    window_to        DATETIME(6)   NULL,
    max_pages        INT UNSIGNED  NOT NULL DEFAULT 100,
    page_size        INT UNSIGNED  NOT NULL DEFAULT 100,
    fetched_count    BIGINT        NOT NULL DEFAULT 0,
    created_count    BIGINT        NOT NULL DEFAULT 0,
    changed_count    BIGINT        NOT NULL DEFAULT 0,
    duplicate_count  BIGINT        NOT NULL DEFAULT 0,
    rejected_count   BIGINT        NOT NULL DEFAULT 0,
    error_code       VARCHAR(64)   NULL,
    error_message    VARCHAR(2000) NULL,
    started_at       DATETIME(6)   NOT NULL,
    finished_at      DATETIME(6)   NULL,
    created_by       CHAR(36)      NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_erp_master_data_sync_run_tenant_time (tenant_id, started_at),
    KEY idx_erp_master_data_sync_run_status (tenant_id, status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品主数据同步批次';

CREATE TABLE erp_master_data_sync_checkpoint (
    id                  CHAR(36)      NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL,
    connector_id        CHAR(36)      NOT NULL,
    source_system       VARCHAR(32)   NOT NULL,
    object_type         VARCHAR(32)   NOT NULL,
    cursor_type         VARCHAR(24)   NOT NULL DEFAULT 'PAGE_TOKEN',
    cursor_value        VARCHAR(1024) NULL,
    source_updated_at   DATETIME(6)   NULL,
    last_success_run_id CHAR(36)      NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_data_sync_checkpoint
        UNIQUE (tenant_id, connector_id, source_system, object_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品主数据同步游标';

CREATE TABLE erp_master_source_binding (
    id                  CHAR(36)      NOT NULL,
    tenant_id           VARCHAR(64)   NOT NULL,
    source_system       VARCHAR(32)   NOT NULL,
    source_object_type  VARCHAR(32)   NOT NULL,
    source_object_id    VARCHAR(128)  NOT NULL,
    target_type         VARCHAR(32)   NOT NULL,
    target_id           CHAR(36)      NOT NULL,
    source_code         VARCHAR(128)  NULL,
    source_name         VARCHAR(200)  NULL,
    source_status       VARCHAR(32)   NULL,
    source_putaway      VARCHAR(16)   NULL,
    source_updated_at   DATETIME(6)   NULL,
    source_payload_hash CHAR(64)      NOT NULL,
    last_sync_run_id    CHAR(36)      NULL,
    synced_at           DATETIME(6)   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_source_binding_source
        UNIQUE (tenant_id, source_system, source_object_type, source_object_id),
    CONSTRAINT uk_erp_master_source_binding_target
        UNIQUE (tenant_id, source_system, target_type, target_id),
    KEY idx_erp_master_source_binding_target (tenant_id, target_type, target_id),
    KEY idx_erp_master_source_binding_code (tenant_id, source_system, source_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品主数据外部来源绑定';

-- Source: V2__erp_product_master_data_media_and_attributes.sql
-- ERP Core Schema V2：承接订货宝商品完整归一化字段。
-- V1 已可能在共享环境执行，V2 只做增量扩展，不回写或重建 V1。
-- 图片只保存我方 COS 私桶 object_key；第三方 URL 只在 Integration 拉取阶段使用。

ALTER TABLE erp_product_spu
    ADD COLUMN model VARCHAR(160) NULL COMMENT '订货宝商品型号' AFTER name,
    ADD COLUMN subtitle VARCHAR(500) NULL COMMENT '订货宝商品副标题' AFTER model,
    ADD COLUMN keywords VARCHAR(1000) NULL COMMENT '订货宝商品关键词' AFTER subtitle,
    ADD COLUMN goods_allocation VARCHAR(255) NULL COMMENT '订货宝货位/存放信息' AFTER keywords,
    ADD COLUMN main_image_key VARCHAR(512) NULL COMMENT 'COS 私桶主图对象 key' AFTER goods_allocation,
    ADD COLUMN source_multi_id VARCHAR(512) NULL COMMENT '订货宝 multi_id 原值' AFTER main_image_key;

ALTER TABLE erp_product_sku
    ADD COLUMN source_options_id VARCHAR(512) NULL COMMENT '订货宝 options_id' AFTER sku_code,
    ADD COLUMN middle_barcode VARCHAR(160) NULL COMMENT '中包装条码' AFTER barcode,
    ADD COLUMN big_barcode VARCHAR(160) NULL COMMENT '大包装条码' AFTER middle_barcode;

ALTER TABLE erp_category
    ADD COLUMN source_parent_id VARCHAR(128) NULL COMMENT '订货宝父分类 ID' AFTER parent_id,
    ADD COLUMN source_category_number VARCHAR(128) NULL COMMENT '订货宝分类编码' AFTER category_code,
    ADD COLUMN source_default_flag TINYINT(1) NULL COMMENT '订货宝默认分类标记' AFTER sort_order;

ALTER TABLE erp_brand
    ADD COLUMN source_brand_number VARCHAR(128) NULL COMMENT '订货宝品牌编码' AFTER brand_code,
    ADD COLUMN source_sort_order INT NULL COMMENT '订货宝品牌排序' AFTER source_brand_number,
    ADD COLUMN source_description VARCHAR(1000) NULL COMMENT '订货宝品牌说明' AFTER source_sort_order;

ALTER TABLE erp_specification
    ADD COLUMN source_parent_id VARCHAR(128) NULL COMMENT '订货宝父级规格 ID' AFTER specification_code;

ALTER TABLE erp_specification_value
    ADD COLUMN source_parent_id VARCHAR(128) NULL COMMENT '订货宝父级规格 ID' AFTER specification_id;

CREATE TABLE erp_tag_group (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    group_code         VARCHAR(128)  NOT NULL,
    name               VARCHAR(120)  NOT NULL,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_tag_group_code UNIQUE (tenant_id, group_code),
    KEY idx_erp_tag_group_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品标签分组';

ALTER TABLE erp_tag
    ADD COLUMN tag_group_id CHAR(36) NULL COMMENT 'ERP标签分组 ID' AFTER id,
    ADD COLUMN source_group_id VARCHAR(128) NULL COMMENT '订货宝标签分组 ID' AFTER tag_code,
    ADD COLUMN source_group_name VARCHAR(120) NULL COMMENT '订货宝标签分组名称' AFTER source_group_id,
    ADD COLUMN source_sort_order INT NULL COMMENT '订货宝标签排序' AFTER source_group_name,
    ADD COLUMN source_relation_count INT NULL COMMENT '订货宝标签关联数量快照' AFTER source_sort_order,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '订货宝标签创建时间' AFTER source_relation_count,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '订货宝标签更新时间' AFTER source_created_at,
    ADD CONSTRAINT fk_erp_tag_group FOREIGN KEY (tag_group_id) REFERENCES erp_tag_group (id),
    ADD KEY idx_erp_tag_group (tenant_id, tag_group_id);

CREATE TABLE erp_product_image (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    spu_id             CHAR(36)      NOT NULL,
    sku_id             CHAR(36)      NULL,
    source_resource_id VARCHAR(128)  NULL,
    source_goods_id    VARCHAR(128)  NULL,
    original_name      VARCHAR(255)  NULL,
    source_file_name   VARCHAR(512)  NULL,
    object_key         VARCHAR(512)  NOT NULL COMMENT 'COS 私桶对象 key',
    sort_order         INT           NOT NULL DEFAULT 0,
    is_primary         TINYINT(1)    NOT NULL DEFAULT 0,
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_image_object UNIQUE (tenant_id, object_key),
    CONSTRAINT fk_erp_product_image_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_image_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_product_image_spu (tenant_id, spu_id, sort_order),
    KEY idx_erp_product_image_sku (tenant_id, sku_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品图片及COS对象 key';

CREATE TABLE erp_product_price (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    target_type        VARCHAR(16)   NOT NULL COMMENT 'SPU 或 SKU',
    target_id          CHAR(36)      NOT NULL,
    spu_id             CHAR(36)      NULL,
    sku_id             CHAR(36)      NULL,
    price_type         VARCHAR(24)   NOT NULL COMMENT 'ORDER/MARKET/PURCHASE/OTHER',
    unit_level         VARCHAR(16)   NOT NULL COMMENT 'BASE/MIDDLE/BIG',
    amount             DECIMAL(20,6) NOT NULL,
    source_field       VARCHAR(64)   NULL COMMENT '订货宝来源字段',
    currency           VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_price_target UNIQUE
        (tenant_id, target_type, target_id, price_type, unit_level),
    CONSTRAINT fk_erp_product_price_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_price_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_product_price_spu (tenant_id, spu_id),
    KEY idx_erp_product_price_sku (tenant_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品及SKU多价格类型';

CREATE TABLE erp_product_unit (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    target_type        VARCHAR(16)   NOT NULL COMMENT 'SPU 或 SKU',
    target_id          CHAR(36)      NOT NULL,
    spu_id             CHAR(36)      NULL,
    sku_id             CHAR(36)      NULL,
    unit_level         VARCHAR(16)   NOT NULL COMMENT 'BASE/MIDDLE/BIG',
    unit_name          VARCHAR(40)   NULL,
    barcode            VARCHAR(160)  NULL,
    conversion_to_base DECIMAL(20,6) NULL,
    source_field       VARCHAR(64)   NULL,
    sort_order         INT           NOT NULL DEFAULT 0,
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_unit_target UNIQUE (tenant_id, target_type, target_id, unit_level),
    CONSTRAINT fk_erp_product_unit_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_unit_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_product_unit_spu (tenant_id, spu_id),
    KEY idx_erp_product_unit_sku (tenant_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品多层级单位与条码';

CREATE TABLE erp_product_inventory_policy (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    spu_id             CHAR(36)      NOT NULL,
    lower_bound        DECIMAL(20,6) NULL,
    upper_bound        DECIMAL(20,6) NULL,
    safety_stock       DECIMAL(20,6) NULL,
    source_lower_field VARCHAR(64)   NULL,
    source_upper_field VARCHAR(64)   NULL,
    source_safe_field  VARCHAR(64)   NULL,
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_inventory_policy UNIQUE (tenant_id, spu_id),
    CONSTRAINT fk_erp_product_inventory_policy_spu FOREIGN KEY (spu_id)
        REFERENCES erp_product_spu (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品库存上下限与安全库存';

CREATE TABLE erp_product_custom_field (
    id                 CHAR(36)      NOT NULL,
    tenant_id          VARCHAR(64)   NOT NULL,
    target_type        VARCHAR(16)   NOT NULL COMMENT 'SPU 或 SKU',
    target_id          CHAR(36)      NOT NULL,
    spu_id             CHAR(36)      NULL,
    sku_id             CHAR(36)      NULL,
    field_key          VARCHAR(128)  NOT NULL,
    field_value        TEXT          NULL,
    source_field       VARCHAR(128)  NULL,
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(16)   NOT NULL DEFAULT 'IMPORTED',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_product_custom_field UNIQUE (tenant_id, target_type, target_id, field_key),
    CONSTRAINT fk_erp_product_custom_field_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_product_custom_field_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_product_custom_field_spu (tenant_id, spu_id),
    KEY idx_erp_product_custom_field_sku (tenant_id, sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品可扩展来源字段';

-- Source: V3__erp_supply_chain_data.sql
-- ERP Core Schema V3：供应商、采购、采购退货、入库、仓库与库存规范模型。
-- Integration 独占订货宝协议与 Raw Landing；ERP 仅保存可查询的规范字段、来源快照和幂等键。

CREATE TABLE erp_supplier (
    id                       CHAR(36)       NOT NULL,
    tenant_id                VARCHAR(64)    NOT NULL,
    supplier_code            VARCHAR(128)   NOT NULL,
    name                     VARCHAR(200)   NOT NULL,
    source_supplier_id       VARCHAR(128)   NULL,
    source_supplier_guid     VARCHAR(128)   NULL,
    area_name                VARCHAR(200)   NULL,
    address_masked           VARCHAR(500)   NULL,
    contact_name             VARCHAR(120)   NULL,
    mobile_masked            VARCHAR(80)    NULL,
    phone_masked             VARCHAR(80)    NULL,
    email_masked             VARCHAR(200)   NULL,
    account_name             VARCHAR(200)   NULL,
    bank_name                VARCHAR(200)   NULL,
    bank_account_last4       VARCHAR(16)    NULL,
    invoice_title            VARCHAR(240)   NULL,
    taxpayer_number_masked   VARCHAR(80)    NULL,
    remark                   VARCHAR(1000)  NULL,
    internal_status          VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE',
    ownership_state          VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin            VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED',
    source_updated_at        DATETIME(6)    NULL,
    source_synced_at         DATETIME(6)    NOT NULL,
    attributes_json          JSON           NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    created_by               CHAR(36)       NULL,
    updated_by               CHAR(36)       NULL,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_supplier_code UNIQUE (tenant_id, supplier_code),
    CONSTRAINT uk_erp_supplier_source UNIQUE (tenant_id, source_supplier_id),
    KEY idx_erp_supplier_name (tenant_id, name),
    KEY idx_erp_supplier_status (tenant_id, internal_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP供应商档案';

CREATE TABLE erp_warehouse (
    id                       CHAR(36)       NOT NULL,
    tenant_id                VARCHAR(64)    NOT NULL,
    warehouse_code           VARCHAR(128)   NOT NULL,
    name                     VARCHAR(200)   NOT NULL,
    source_warehouse_id      VARCHAR(128)   NULL,
    source_warehouse_guid    VARCHAR(128)   NULL,
    source_status            VARCHAR(24)    NULL,
    source_default_flag      TINYINT(1)     NOT NULL DEFAULT 0,
    acreage                  DECIMAL(20,6)  NULL,
    phone_masked             VARCHAR(80)    NULL,
    address                  VARCHAR(500)   NULL,
    collaborator_source_id   VARCHAR(128)   NULL,
    remark                   VARCHAR(1000)  NULL,
    internal_status          VARCHAR(24)    NOT NULL DEFAULT 'ACTIVE',
    ownership_state          VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin            VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED',
    source_synced_at         DATETIME(6)    NOT NULL,
    attributes_json          JSON           NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    created_by               CHAR(36)       NULL,
    updated_by               CHAR(36)       NULL,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_warehouse_code UNIQUE (tenant_id, warehouse_code),
    CONSTRAINT uk_erp_warehouse_source UNIQUE (tenant_id, source_warehouse_id),
    KEY idx_erp_warehouse_name (tenant_id, name),
    KEY idx_erp_warehouse_status (tenant_id, internal_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP仓库档案';

CREATE TABLE erp_purchase_order (
    id                       CHAR(36)       NOT NULL,
    tenant_id                VARCHAR(64)    NOT NULL,
    purchase_order_no        VARCHAR(128)   NOT NULL,
    source_purchase_id       VARCHAR(128)   NULL,
    supplier_id              CHAR(36)       NULL,
    warehouse_id             CHAR(36)       NULL,
    supplier_code_snapshot   VARCHAR(128)   NULL,
    supplier_name_snapshot   VARCHAR(200)   NULL,
    warehouse_code_snapshot  VARCHAR(128)   NULL,
    warehouse_name_snapshot  VARCHAR(200)   NULL,
    staff_source_id          VARCHAR(128)   NULL,
    staff_name               VARCHAR(120)   NULL,
    source_status            VARCHAR(32)    NULL,
    source_status_name       VARCHAR(80)    NULL,
    source_payment_status    VARCHAR(32)    NULL,
    source_payment_name      VARCHAR(80)    NULL,
    internal_status          VARCHAR(24)    NOT NULL DEFAULT 'DRAFT',
    delivery_at              DATETIME(6)    NULL,
    source_created_at        DATETIME(6)    NULL,
    source_updated_at        DATETIME(6)    NULL,
    total_amount             DECIMAL(20,6)  NOT NULL DEFAULT 0,
    paid_amount              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    goods_count              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    source_downloaded        TINYINT(1)     NOT NULL DEFAULT 0,
    remark                   VARCHAR(1000)  NULL,
    internal_communication   VARCHAR(2000)  NULL,
    ownership_state          VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin            VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED',
    source_synced_at         DATETIME(6)    NOT NULL,
    attributes_json          JSON           NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    created_by               CHAR(36)       NULL,
    updated_by               CHAR(36)       NULL,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_purchase_order_no UNIQUE (tenant_id, purchase_order_no),
    CONSTRAINT uk_erp_purchase_order_source UNIQUE (tenant_id, source_purchase_id),
    CONSTRAINT fk_erp_purchase_order_supplier FOREIGN KEY (supplier_id) REFERENCES erp_supplier (id),
    CONSTRAINT fk_erp_purchase_order_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_warehouse (id),
    KEY idx_erp_purchase_order_supplier (tenant_id, supplier_id, source_created_at),
    KEY idx_erp_purchase_order_status (tenant_id, internal_status, source_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购单主表';

CREATE TABLE erp_purchase_order_line (
    id                         CHAR(36)       NOT NULL,
    tenant_id                  VARCHAR(64)    NOT NULL,
    purchase_order_id          CHAR(36)       NOT NULL,
    source_line_id             VARCHAR(128)   NOT NULL,
    spu_id                     CHAR(36)       NULL,
    sku_id                     CHAR(36)       NULL,
    source_goods_id            VARCHAR(128)   NULL,
    source_goods_guid          VARCHAR(128)   NULL,
    source_goods_code          VARCHAR(128)   NULL,
    source_goods_name          VARCHAR(240)   NULL,
    source_options_id          VARCHAR(128)   NULL,
    source_options_goods_code  VARCHAR(128)   NULL,
    options_summary            VARCHAR(500)   NULL,
    base_quantity              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    unit_price                 DECIMAL(20,6)  NOT NULL DEFAULT 0,
    purchase_unit_code         VARCHAR(40)    NULL,
    purchase_unit_name         VARCHAR(80)    NULL,
    purchase_unit_quantity     DECIMAL(20,6)  NOT NULL DEFAULT 0,
    warehoused_quantity        DECIMAL(20,6)  NOT NULL DEFAULT 0,
    returned_quantity          DECIMAL(20,6)  NOT NULL DEFAULT 0,
    remark                     VARCHAR(1000)  NULL,
    attributes_json            JSON           NULL,
    created_at                 DATETIME(6)    NOT NULL,
    updated_at                 DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_purchase_order_line UNIQUE (tenant_id, purchase_order_id, source_line_id),
    CONSTRAINT fk_erp_purchase_order_line_order FOREIGN KEY (purchase_order_id) REFERENCES erp_purchase_order (id),
    CONSTRAINT fk_erp_purchase_order_line_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_purchase_order_line_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_purchase_order_line_goods (tenant_id, source_goods_code, source_options_goods_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购单明细';

CREATE TABLE erp_purchase_return (
    id                       CHAR(36)       NOT NULL,
    tenant_id                VARCHAR(64)    NOT NULL,
    purchase_return_no       VARCHAR(128)   NOT NULL,
    source_return_id         VARCHAR(128)   NULL,
    supplier_id              CHAR(36)       NULL,
    warehouse_id             CHAR(36)       NULL,
    supplier_code_snapshot   VARCHAR(128)   NULL,
    supplier_name_snapshot   VARCHAR(200)   NULL,
    warehouse_code_snapshot  VARCHAR(128)   NULL,
    warehouse_name_snapshot  VARCHAR(200)   NULL,
    staff_source_id          VARCHAR(128)   NULL,
    staff_name               VARCHAR(120)   NULL,
    source_status            VARCHAR(32)    NULL,
    source_status_name       VARCHAR(80)    NULL,
    internal_status          VARCHAR(24)    NOT NULL DEFAULT 'DRAFT',
    return_amount            DECIMAL(20,6)  NOT NULL DEFAULT 0,
    discount_amount          DECIMAL(20,6)  NOT NULL DEFAULT 0,
    return_reason            VARCHAR(1000)  NULL,
    source_created_at        DATETIME(6)    NULL,
    return_send_at           DATETIME(6)    NULL,
    internal_communication   VARCHAR(2000)  NULL,
    remark                   VARCHAR(1000)  NULL,
    detail_count             INT            NOT NULL DEFAULT 0,
    contact_name             VARCHAR(120)   NULL,
    contact_phone_masked     VARCHAR(80)    NULL,
    contact_address_masked   VARCHAR(500)   NULL,
    city_ids_json            JSON           NULL,
    city_names_json          JSON           NULL,
    source_device            VARCHAR(80)    NULL,
    parent_return_source_id  VARCHAR(128)   NULL,
    parent_company_source_id VARCHAR(128)   NULL,
    source_downloaded        TINYINT(1)     NOT NULL DEFAULT 0,
    ownership_state          VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin            VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED',
    source_synced_at         DATETIME(6)    NOT NULL,
    attributes_json          JSON           NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    created_by               CHAR(36)       NULL,
    updated_by               CHAR(36)       NULL,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_purchase_return_no UNIQUE (tenant_id, purchase_return_no),
    CONSTRAINT uk_erp_purchase_return_source UNIQUE (tenant_id, source_return_id),
    CONSTRAINT fk_erp_purchase_return_supplier FOREIGN KEY (supplier_id) REFERENCES erp_supplier (id),
    CONSTRAINT fk_erp_purchase_return_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_warehouse (id),
    KEY idx_erp_purchase_return_status (tenant_id, internal_status, source_created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购退货单主表';

CREATE TABLE erp_purchase_return_line (
    id                         CHAR(36)       NOT NULL,
    tenant_id                  VARCHAR(64)    NOT NULL,
    purchase_return_id         CHAR(36)       NOT NULL,
    source_line_id             VARCHAR(128)   NOT NULL,
    spu_id                     CHAR(36)       NULL,
    sku_id                     CHAR(36)       NULL,
    purchase_order_line_id     CHAR(36)       NULL,
    source_goods_id            VARCHAR(128)   NULL,
    source_goods_code          VARCHAR(128)   NULL,
    source_goods_name          VARCHAR(240)   NULL,
    source_options_id          VARCHAR(128)   NULL,
    source_options_goods_code  VARCHAR(128)   NULL,
    options_summary            VARCHAR(500)   NULL,
    requested_quantity         DECIMAL(20,6)  NOT NULL DEFAULT 0,
    confirmed_quantity         DECIMAL(20,6)  NOT NULL DEFAULT 0,
    return_price               DECIMAL(20,6)  NOT NULL DEFAULT 0,
    confirmed_price            DECIMAL(20,6)  NOT NULL DEFAULT 0,
    return_unit_code           VARCHAR(40)    NULL,
    return_unit_name           VARCHAR(80)    NULL,
    return_unit_quantity       DECIMAL(20,6)  NOT NULL DEFAULT 0,
    confirmed_unit_quantity    DECIMAL(20,6)  NOT NULL DEFAULT 0,
    conversion_number          DECIMAL(20,6)  NOT NULL DEFAULT 0,
    amount                     DECIMAL(20,6)  NOT NULL DEFAULT 0,
    cost_price                 DECIMAL(20,6)  NOT NULL DEFAULT 0,
    purchase_order_no          VARCHAR(128)   NULL,
    category_name_snapshot     VARCHAR(160)   NULL,
    brand_name_snapshot        VARCHAR(160)   NULL,
    remark                     VARCHAR(1000)  NULL,
    attributes_json            JSON           NULL,
    created_at                 DATETIME(6)    NOT NULL,
    updated_at                 DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_purchase_return_line UNIQUE (tenant_id, purchase_return_id, source_line_id),
    CONSTRAINT fk_erp_purchase_return_line_return FOREIGN KEY (purchase_return_id) REFERENCES erp_purchase_return (id),
    CONSTRAINT fk_erp_purchase_return_line_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_purchase_return_line_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    CONSTRAINT fk_erp_purchase_return_line_purchase FOREIGN KEY (purchase_order_line_id) REFERENCES erp_purchase_order_line (id),
    KEY idx_erp_purchase_return_line_goods (tenant_id, source_goods_code, source_options_goods_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购退货单明细';

CREATE TABLE erp_warehousing_receipt (
    id                       CHAR(36)       NOT NULL,
    tenant_id                VARCHAR(64)    NOT NULL,
    warehousing_no           VARCHAR(128)   NOT NULL,
    source_warehousing_id    VARCHAR(128)   NULL,
    warehouse_id             CHAR(36)       NULL,
    supplier_id              CHAR(36)       NULL,
    warehouse_name_snapshot  VARCHAR(200)   NULL,
    supplier_name_snapshot   VARCHAR(200)   NULL,
    source_type_id           VARCHAR(32)    NULL,
    source_type_name         VARCHAR(100)   NULL,
    source_status            VARCHAR(32)    NULL,
    source_status_name       VARCHAR(80)    NULL,
    internal_status          VARCHAR(24)    NOT NULL DEFAULT 'DRAFT',
    staff_name               VARCHAR(120)   NULL,
    client_source_id         VARCHAR(128)   NULL,
    account_source_id        VARCHAR(128)   NULL,
    collaborator_source_id   VARCHAR(128)   NULL,
    collaborator_name        VARCHAR(200)   NULL,
    logistics_source_id      VARCHAR(128)   NULL,
    express_number           VARCHAR(160)   NULL,
    storage_at               DATETIME(6)    NULL,
    source_created_at        DATETIME(6)    NULL,
    source_updated_at        DATETIME(6)    NULL,
    freight_amount           DECIMAL(20,6)  NOT NULL DEFAULT 0,
    total_amount             DECIMAL(20,6)  NOT NULL DEFAULT 0,
    cost_amount              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    source_api_flag          TINYINT(1)     NOT NULL DEFAULT 0,
    split_type               VARCHAR(32)    NULL,
    remark                   VARCHAR(1000)  NULL,
    ownership_state          VARCHAR(32)    NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin            VARCHAR(16)    NOT NULL DEFAULT 'IMPORTED',
    source_synced_at         DATETIME(6)    NOT NULL,
    attributes_json          JSON           NULL,
    version                  BIGINT         NOT NULL DEFAULT 0,
    created_by               CHAR(36)       NULL,
    updated_by               CHAR(36)       NULL,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_warehousing_no UNIQUE (tenant_id, warehousing_no),
    CONSTRAINT uk_erp_warehousing_source UNIQUE (tenant_id, source_warehousing_id),
    CONSTRAINT fk_erp_warehousing_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_warehouse (id),
    CONSTRAINT fk_erp_warehousing_supplier FOREIGN KEY (supplier_id) REFERENCES erp_supplier (id),
    KEY idx_erp_warehousing_status (tenant_id, internal_status, storage_at),
    KEY idx_erp_warehousing_warehouse (tenant_id, warehouse_id, storage_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP入库单主表';

CREATE TABLE erp_warehousing_receipt_line (
    id                         CHAR(36)       NOT NULL,
    tenant_id                  VARCHAR(64)    NOT NULL,
    warehousing_receipt_id     CHAR(36)       NOT NULL,
    source_line_id             VARCHAR(128)   NOT NULL,
    spu_id                     CHAR(36)       NULL,
    sku_id                     CHAR(36)       NULL,
    source_goods_id            VARCHAR(128)   NULL,
    source_goods_code          VARCHAR(128)   NULL,
    source_goods_name          VARCHAR(240)   NULL,
    source_options_id          VARCHAR(128)   NULL,
    source_options_goods_code  VARCHAR(128)   NULL,
    options_summary            VARCHAR(500)   NULL,
    base_quantity              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    unit_quantity              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    unit_code                  VARCHAR(40)    NULL,
    unit_name                  VARCHAR(80)    NULL,
    conversion_number          DECIMAL(20,6)  NOT NULL DEFAULT 0,
    cost_price                 DECIMAL(20,6)  NOT NULL DEFAULT 0,
    unit_cost_price            DECIMAL(20,6)  NOT NULL DEFAULT 0,
    purchase_price             DECIMAL(20,6)  NOT NULL DEFAULT 0,
    wholesale_price            DECIMAL(20,6)  NOT NULL DEFAULT 0,
    allocation                 VARCHAR(200)   NULL,
    barcode                    VARCHAR(160)   NULL,
    goods_model                VARCHAR(160)   NULL,
    source_real_quantity       DECIMAL(20,6)  NULL,
    source_available_quantity  DECIMAL(20,6)  NULL,
    collaborator_source_id     VARCHAR(128)   NULL,
    collaborator_name          VARCHAR(200)   NULL,
    remark                     VARCHAR(1000)  NULL,
    attributes_json            JSON           NULL,
    created_at                 DATETIME(6)    NOT NULL,
    updated_at                 DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_warehousing_line UNIQUE (tenant_id, warehousing_receipt_id, source_line_id),
    CONSTRAINT fk_erp_warehousing_line_receipt FOREIGN KEY (warehousing_receipt_id) REFERENCES erp_warehousing_receipt (id),
    CONSTRAINT fk_erp_warehousing_line_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_warehousing_line_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_warehousing_line_goods (tenant_id, source_goods_code, source_options_goods_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP入库单明细';

CREATE TABLE erp_warehousing_purchase_link (
    id                       CHAR(36)      NOT NULL,
    tenant_id                VARCHAR(64)   NOT NULL,
    warehousing_receipt_id   CHAR(36)      NOT NULL,
    purchase_order_id        CHAR(36)      NULL,
    source_purchase_id       VARCHAR(128)  NULL,
    purchase_order_no        VARCHAR(128)  NOT NULL,
    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_warehousing_purchase_link UNIQUE (tenant_id, warehousing_receipt_id, purchase_order_no),
    CONSTRAINT fk_erp_warehousing_purchase_receipt FOREIGN KEY (warehousing_receipt_id) REFERENCES erp_warehousing_receipt (id),
    CONSTRAINT fk_erp_warehousing_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES erp_purchase_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP入库单与采购单关系';

CREATE TABLE erp_inventory_balance (
    id                         CHAR(36)       NOT NULL,
    tenant_id                  VARCHAR(64)    NOT NULL,
    warehouse_id               CHAR(36)       NULL,
    spu_id                     CHAR(36)       NULL,
    sku_id                     CHAR(36)       NULL,
    source_warehouse_key       VARCHAR(160)   NOT NULL,
    source_warehouse_guid      VARCHAR(128)   NULL,
    source_warehouse_code      VARCHAR(128)   NULL,
    source_warehouse_name      VARCHAR(200)   NULL,
    source_product_key         VARCHAR(160)   NOT NULL,
    source_goods_guid          VARCHAR(128)   NULL,
    source_goods_code          VARCHAR(128)   NULL,
    source_goods_name          VARCHAR(240)   NULL,
    source_variant_key         VARCHAR(320)   NOT NULL,
    first_option_guid          VARCHAR(128)   NULL,
    first_option_code          VARCHAR(128)   NULL,
    first_option_name          VARCHAR(160)   NULL,
    second_option_guid         VARCHAR(128)   NULL,
    second_option_code         VARCHAR(128)   NULL,
    second_option_name         VARCHAR(160)   NULL,
    real_quantity              DECIMAL(20,6)  NOT NULL DEFAULT 0,
    available_quantity         DECIMAL(20,6)  NOT NULL DEFAULT 0,
    reserved_quantity          DECIMAL(20,6)  NOT NULL DEFAULT 0,
    in_transit_quantity        DECIMAL(20,6)  NOT NULL DEFAULT 0,
    calculation_origin         VARCHAR(32)    NOT NULL DEFAULT 'DHB_SNAPSHOT',
    source_synced_at           DATETIME(6)    NOT NULL,
    attributes_json            JSON           NULL,
    version                    BIGINT         NOT NULL DEFAULT 0,
    created_at                 DATETIME(6)    NOT NULL,
    updated_at                 DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_inventory_balance UNIQUE
        (tenant_id, source_warehouse_key, source_product_key, source_variant_key),
    CONSTRAINT fk_erp_inventory_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_warehouse (id),
    CONSTRAINT fk_erp_inventory_spu FOREIGN KEY (spu_id) REFERENCES erp_product_spu (id),
    CONSTRAINT fk_erp_inventory_sku FOREIGN KEY (sku_id) REFERENCES erp_product_sku (id),
    KEY idx_erp_inventory_goods (tenant_id, source_goods_code, source_variant_key),
    KEY idx_erp_inventory_warehouse (tenant_id, warehouse_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP库存余额快照';

-- Source: V4__erp_sku_specification_summary_text.sql
-- ERP SKU 规格摘要是展示文本，不是 JSON；结构化规格继续由规格及规格值关系表表达。
-- V1 已在共享环境执行，因此通过增量迁移修正字段类型和名称。

ALTER TABLE erp_product_sku
    MODIFY COLUMN specification_summary_json VARCHAR(500) NULL
        COMMENT '展示用规格组合名称，结构化规格以关系表为准';

ALTER TABLE erp_product_sku
    RENAME COLUMN specification_summary_json TO specification_summary;

-- 已有来源绑定保存了规格组合名称，用它消除历史 JSON 字符串可能包含的引号。
UPDATE erp_product_sku sku
SET specification_summary = (
    SELECT binding.source_name
    FROM erp_master_source_binding binding
    WHERE binding.tenant_id = sku.tenant_id
      AND binding.source_system = 'DINGHUOBAO'
      AND binding.source_object_type = 'PRODUCT_SKU'
      AND binding.target_id = sku.id
)
WHERE EXISTS (
    SELECT 1
    FROM erp_master_source_binding binding
    WHERE binding.tenant_id = sku.tenant_id
      AND binding.source_system = 'DINGHUOBAO'
      AND binding.source_object_type = 'PRODUCT_SKU'
      AND binding.target_id = sku.id
      AND binding.source_name IS NOT NULL
);

-- Source: V5__erp_product_order_constraints.sql
-- getGoodsList 的最低订量属于商品经营字段，落入 ERP 当前商品模型。

ALTER TABLE erp_product_spu
    ADD COLUMN minimum_order DECIMAL(20,6) NULL COMMENT '订货宝最低订货量' AFTER default_barcode,
    ADD COLUMN minimum_order_unit VARCHAR(32) NULL COMMENT '订货宝最低订货量单位原值' AFTER minimum_order;

-- 让已同步商品在下一次同步时重新应用新增字段；来源 ID 与 ERP 主键保持不变。
UPDATE erp_master_source_binding
SET source_payload_hash = SHA2(CONCAT(source_payload_hash, ':ERP_PRODUCT_V5'), 256),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE source_system = 'DINGHUOBAO'
  AND source_object_type = 'PRODUCT_SPU';

-- Source: V6__erp_private_media_object_keys.sql
-- 所有图片只保存我方 COS 私桶 object key，禁止业务表保存订货宝或永久公开 URL。
ALTER TABLE erp_brand
    RENAME COLUMN logo_url TO logo_object_key;

-- Source: V7__erp_master_data_sync_lock.sql
-- ERP Core Schema V7：限制同一租户同一对象类型的同步批次并发执行。
CREATE TABLE erp_master_data_sync_lock (
    id            CHAR(36)     NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    source_system VARCHAR(32)  NOT NULL,
    object_type   VARCHAR(32)  NOT NULL,
    run_id        CHAR(36)     NOT NULL,
    acquired_at   DATETIME(6)  NOT NULL,
    expires_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_erp_master_data_sync_lock_scope
        UNIQUE (tenant_id, source_system, object_type),
    KEY idx_erp_master_data_sync_lock_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP同步互斥锁';

-- Source: V8__erp_purchase_source_identity.sql
ALTER TABLE erp_purchase_order
    ADD COLUMN source_supplier_id VARCHAR(128) NULL AFTER source_purchase_id,
    ADD COLUMN source_warehouse_id VARCHAR(128) NULL AFTER source_supplier_id;

ALTER TABLE erp_purchase_return
    ADD COLUMN source_supplier_id VARCHAR(128) NULL AFTER source_return_id,
    ADD COLUMN source_warehouse_id VARCHAR(128) NULL AFTER source_supplier_id;

-- Source: V9__erp_warehousing_source_identity.sql
ALTER TABLE erp_warehousing_receipt
    ADD COLUMN source_warehouse_id VARCHAR(128) NULL AFTER source_warehousing_id,
    ADD COLUMN source_supplier_id VARCHAR(128) NULL AFTER source_warehouse_id;

-- Source: V10__erp_product_response_fields.sql
ALTER TABLE erp_product_spu
    ADD COLUMN source_category_id VARCHAR(128) NULL AFTER source_multi_id,
    ADD COLUMN source_brand_id VARCHAR(128) NULL AFTER source_category_id,
    ADD COLUMN conversion_barcode VARCHAR(128) NULL AFTER source_brand_id;

ALTER TABLE erp_product_sku
    ADD COLUMN first_specification_value_source_id VARCHAR(128) NULL AFTER source_options_id,
    ADD COLUMN second_specification_value_source_id VARCHAR(128) NULL AFTER first_specification_value_source_id;

-- Source: V11__erp_supplier_full_bank_account.sql
-- ERP supplier bank account is currently required in full by the internal portal.
ALTER TABLE erp_supplier
    ADD COLUMN bank_account VARCHAR(200) NULL AFTER bank_name;

-- Source: V12__erp_supplier_full_contact_and_tax_fields.sql
-- The internal ERP portal currently requires complete supplier contact and tax data.
ALTER TABLE erp_supplier
    ADD COLUMN address VARCHAR(500) NULL AFTER area_name,
    ADD COLUMN mobile VARCHAR(80) NULL AFTER mobile_masked,
    ADD COLUMN phone VARCHAR(80) NULL AFTER phone_masked,
    ADD COLUMN email VARCHAR(200) NULL AFTER email_masked,
    ADD COLUMN taxpayer_number VARCHAR(80) NULL AFTER taxpayer_number_masked;

-- Source: V13__erp_supplier_remove_masked_fields.sql
-- Supplier data is now stored and returned only in complete form for the internal portal.
ALTER TABLE erp_supplier
    DROP COLUMN address_masked,
    DROP COLUMN mobile_masked,
    DROP COLUMN phone_masked,
    DROP COLUMN email_masked,
    DROP COLUMN bank_account_last4,
    DROP COLUMN taxpayer_number_masked;

-- Source: V14__erp_warehouse_and_return_full_contact.sql
-- Add complete contact columns without renaming old columns so rolling deployments remain compatible.
ALTER TABLE erp_warehouse
    ADD COLUMN phone VARCHAR(80) NULL AFTER phone_masked;

UPDATE erp_warehouse
SET phone = phone_masked
WHERE phone IS NULL AND phone_masked IS NOT NULL;

ALTER TABLE erp_purchase_return
    ADD COLUMN contact_phone VARCHAR(80) NULL AFTER contact_phone_masked,
    ADD COLUMN contact_address VARCHAR(500) NULL AFTER contact_address_masked;

UPDATE erp_purchase_return
SET contact_phone = contact_phone_masked,
    contact_address = contact_address_masked
WHERE (contact_phone IS NULL AND contact_phone_masked IS NOT NULL)
   OR (contact_address IS NULL AND contact_address_masked IS NOT NULL);

-- Source: V15__erp_source_presence_reconciliation.sql
ALTER TABLE erp_master_source_binding
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'PRESENT' AFTER source_payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL AFTER source_presence,
    ADD KEY idx_erp_source_presence (
        tenant_id, source_system, source_object_type, source_presence, source_absent_at
    );

-- Source: V16__erp_sync_dictionary_audit.sql
-- ERP 同步批次记录本次使用的字典内容版本和未映射来源枚举。
-- 未映射不阻断来源数据落库，批次使用 SUCCEEDED_WITH_WARNINGS 明确提示人工治理。
ALTER TABLE erp_master_data_sync_run
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
        COMMENT '批次状态：RUNNING/SUCCEEDED/SUCCEEDED_WITH_WARNINGS/FAILED',
    ADD COLUMN unmapped_count BIGINT NOT NULL DEFAULT 0
        COMMENT '已落库但未找到唯一有效字典映射的来源枚举出现次数'
        AFTER rejected_count,
    ADD COLUMN dict_snapshot_json JSON NULL
        COMMENT '本批次使用的字典编码及整本内容版本'
        AFTER unmapped_count,
    ADD COLUMN mapping_issues_json JSON NULL
        COMMENT '按字典、字段和来源值聚合的未映射项，不含完整来源报文'
        AFTER dict_snapshot_json;

-- Source: V17__erp_scheduled_skip_audit.sql
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

-- Source: V18__erp_source_presence_unknown_default.sql
-- V15历史行被默认为PRESENT，但它们未经升级后的权威快照确认。
-- 仅降级PRESENT；保留SOURCE_ABSENT及其首次缺失时间作为核对证据。
ALTER TABLE erp_master_source_binding
    ALTER COLUMN source_presence SET DEFAULT 'UNKNOWN';

UPDATE erp_master_source_binding
   SET source_presence = 'UNKNOWN',
       source_absent_at = NULL
 WHERE source_presence = 'PRESENT';

-- Source: V19__erp_internal_product_inventory_baseline.sql
-- ERP 自研业务基线：商品、采购、仓库库存、入库、出库、调拨。
-- 本迁移不继续扩展旧订货宝同步表；订货宝后续只通过 Integration 映射到这些业务表。
-- 表名、字段名统一 lower_snake_case；业务编码和状态值统一 UPPER_SNAKE_CASE；删除统一逻辑删。

CREATE TABLE erp_product_category (
    id                 BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id          VARCHAR(64)   NOT NULL COMMENT '租户ID',
    parent_id          BIGINT(20)    NULL COMMENT '父级分类ID',
    category_code      VARCHAR(50)   NOT NULL COMMENT '商品分类编码，由ERP编码规则生成',
    category_name      VARCHAR(120)  NOT NULL COMMENT '商品分类名称',
    category_level     INT           NOT NULL DEFAULT 1 COMMENT '分类层级，根节点为1',
    ordinal            INT           NOT NULL DEFAULT 0 COMMENT '展示顺序，数值越小越靠前',
    remark             VARCHAR(500)  NULL COMMENT '备注',
    revision           INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by         VARCHAR(50)   NULL COMMENT '创建人',
    created_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by         VARCHAR(50)   NULL COMMENT '更新人',
    updated_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted            INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_category_code (tenant_id, category_code),
    CONSTRAINT fk_erp_product_category_parent FOREIGN KEY (parent_id) REFERENCES erp_product_category (id),
    KEY idx_erp_product_category_parent (tenant_id, parent_id, deleted, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品分类';

CREATE TABLE erp_product_brand (
    id                 BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id          VARCHAR(64)   NOT NULL COMMENT '租户ID',
    brand_code         VARCHAR(50)   NOT NULL COMMENT '商品品牌编码，由ERP编码规则生成',
    brand_name         VARCHAR(120)  NOT NULL COMMENT '商品品牌名称',
    remark             VARCHAR(500)  NULL COMMENT '备注',
    revision           INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by         VARCHAR(50)   NULL COMMENT '创建人',
    created_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by         VARCHAR(50)   NULL COMMENT '更新人',
    updated_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted            INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_brand_code (tenant_id, brand_code),
    KEY idx_erp_product_brand_name (tenant_id, brand_name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品品牌';

CREATE TABLE erp_product_tag (
    id                 BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id          VARCHAR(64)   NOT NULL COMMENT '租户ID',
    tag_code           VARCHAR(50)   NOT NULL COMMENT '商品标签编码，由ERP编码规则生成',
    tag_name           VARCHAR(120)  NOT NULL COMMENT '商品标签名称',
    tag_type_code      VARCHAR(64)   NULL COMMENT '标签类型，关联 PRODUCT_TAG_TYPE 字典项',
    remark             VARCHAR(500)  NULL COMMENT '备注',
    revision           INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by         VARCHAR(50)   NULL COMMENT '创建人',
    created_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by         VARCHAR(50)   NULL COMMENT '更新人',
    updated_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted            INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_tag_code (tenant_id, tag_code),
    KEY idx_erp_product_tag_name (tenant_id, tag_name, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品标签';

CREATE TABLE erp_supplier_profile (
    id                 BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id          VARCHAR(64)   NOT NULL COMMENT '租户ID',
    supplier_code      VARCHAR(50)   NOT NULL COMMENT '供应商编码，由ERP编码规则生成',
    supplier_name      VARCHAR(200)  NOT NULL COMMENT '供应商名称',
    contact_name       VARCHAR(100)  NULL COMMENT '联系人',
    contact_phone      VARCHAR(50)   NULL COMMENT '联系电话',
    address            VARCHAR(1000) NULL COMMENT '供应商地址',
    bank_name          VARCHAR(100)  NULL COMMENT '开户银行',
    bank_account_no    VARCHAR(100)  NULL COMMENT '银行账号',
    status_code        VARCHAR(64)   NOT NULL DEFAULT 'ACTIVE' COMMENT '供应商状态，关联 SUPPLIER_STATUS 字典项',
    remark             VARCHAR(1000) NULL COMMENT '备注',
    revision           INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by         VARCHAR(50)   NULL COMMENT '创建人',
    created_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by         VARCHAR(50)   NULL COMMENT '更新人',
    updated_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted            INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_supplier_profile_code (tenant_id, supplier_code),
    KEY idx_erp_supplier_profile_name (tenant_id, supplier_name, deleted),
    KEY idx_erp_supplier_profile_status (tenant_id, status_code, deleted, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP供应商档案';

CREATE TABLE erp_inventory_warehouse (
    id                 BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id          VARCHAR(64)   NOT NULL COMMENT '租户ID',
    warehouse_code     VARCHAR(50)   NOT NULL COMMENT '仓库编码，由ERP编码规则生成',
    warehouse_name     VARCHAR(120)  NOT NULL COMMENT '仓库名称',
    region_code        VARCHAR(64)   NULL COMMENT '仓库归属地区，关联 REGION 字典项',
    warehouse_type_code VARCHAR(64)  NOT NULL DEFAULT 'CITY' COMMENT '仓库类型：DEFAULT/CITY/OTHER',
    default_flag       TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否默认仓库：0否，1是',
    address            VARCHAR(1000) NULL COMMENT '仓库地址',
    contact_name       VARCHAR(100)  NULL COMMENT '联系人',
    contact_phone      VARCHAR(50)   NULL COMMENT '联系电话',
    remark             VARCHAR(1000) NULL COMMENT '备注',
    revision           INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by         VARCHAR(50)   NULL COMMENT '创建人',
    created_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by         VARCHAR(50)   NULL COMMENT '更新人',
    updated_time       DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted            INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_inventory_warehouse_code (tenant_id, warehouse_code),
    KEY idx_erp_inventory_warehouse_region (tenant_id, region_code, deleted),
    KEY idx_erp_inventory_warehouse_default (tenant_id, default_flag, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP仓库';

CREATE TABLE erp_product (
    id                          BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                   VARCHAR(64)    NOT NULL COMMENT '租户ID',
    product_code                VARCHAR(50)    NOT NULL COMMENT '商品编码，由ERP编码规则生成',
    product_name                VARCHAR(200)   NULL COMMENT '商品名称；草稿允许暂为空，提交时由应用服务校验',
    category_id                 BIGINT(20)     NULL COMMENT '商品分类ID',
    brand_id                    BIGINT(20)     NULL COMMENT '商品品牌ID',
    product_specification       VARCHAR(500)   NULL COMMENT '商品规格说明',
    unit_code                   VARCHAR(64)    NULL COMMENT '商品单位，关联 PRODUCT_UNIT 字典项；草稿允许暂为空，提交时由应用服务校验',
    min_order_quantity          DECIMAL(24,6)  NULL COMMENT '起订量',
    order_multiple_flag         TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否整倍订货：0否，1是',
    order_multiple_quantity     DECIMAL(24,6)  NULL COMMENT '整倍订货数量',
    sale_type_code              VARCHAR(64)    NOT NULL DEFAULT 'SPOT' COMMENT '售卖类型，关联 PRODUCT_SALE_TYPE 字典项',
    shelf_status_code           VARCHAR(64)    NOT NULL DEFAULT 'OFF_SHELF' COMMENT '上架状态，关联 PRODUCT_SHELF_STATUS 字典项',
    tag_codes_json              JSON           NULL COMMENT '商品标签编码数组，来源商品标签列表',
    limit_quantity              DECIMAL(24,6)  NULL COMMENT '限购量',
    default_warehouse_id        BIGINT(20)     NULL COMMENT '默认归属仓库ID',
    image_keys_json             JSON           NULL COMMENT '商品图片COS key数组，最多24张，区分主图和详情图',
    recommend_product_ids_json  JSON           NULL COMMENT '商品页关联推荐商品ID数组',
    submit_status_code          VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '提交状态，关联 PRODUCT_SUBMIT_STATUS 字典项',
    remark                      VARCHAR(1000)  NULL COMMENT '商品备注',
    revision                    INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by                  VARCHAR(50)    NULL COMMENT '创建人',
    created_time                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by                  VARCHAR(50)    NULL COMMENT '更新人',
    updated_time                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                     INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_code (tenant_id, product_code),
    CONSTRAINT fk_erp_product_category FOREIGN KEY (category_id) REFERENCES erp_product_category (id),
    CONSTRAINT fk_erp_product_brand FOREIGN KEY (brand_id) REFERENCES erp_product_brand (id),
    CONSTRAINT fk_erp_product_default_warehouse FOREIGN KEY (default_warehouse_id) REFERENCES erp_inventory_warehouse (id),
    KEY idx_erp_product_name (tenant_id, product_name, deleted),
    KEY idx_erp_product_category (tenant_id, category_id, deleted),
    KEY idx_erp_product_brand (tenant_id, brand_id, deleted),
    KEY idx_erp_product_shelf (tenant_id, shelf_status_code, updated_time),
    KEY idx_erp_product_submit (tenant_id, submit_status_code, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品主表';

CREATE TABLE erp_product_variant (
    id                      BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id               VARCHAR(64)    NOT NULL COMMENT '租户ID',
    product_id              BIGINT(20)     NOT NULL COMMENT '商品ID',
    variant_code            VARCHAR(50)    NOT NULL COMMENT '商品规格编码，由ERP编码规则生成',
    specification_snapshot  VARCHAR(500)   NULL COMMENT '规格快照，如口味/包装组合',
    unit_code               VARCHAR(64)    NULL COMMENT '订货单位，关联 PRODUCT_UNIT 字典项；草稿允许暂为空，提交时由应用服务校验',
    sale_price              DECIMAL(24,6)  NULL COMMENT '订货价；草稿允许暂为空，提交时由应用服务校验',
    market_price            DECIMAL(24,6)  NULL COMMENT '市场价',
    purchase_price          DECIMAL(24,6)  NULL COMMENT '采购参考价',
    min_order_quantity      DECIMAL(24,6)  NULL COMMENT '当前规格起订量',
    order_multiple_quantity DECIMAL(24,6)  NULL COMMENT '当前规格整倍订货数量',
    limit_quantity          DECIMAL(24,6)  NULL COMMENT '当前规格限购量',
    default_flag            TINYINT(1)     NOT NULL DEFAULT 0 COMMENT '是否默认规格：0否，1是',
    remark                  VARCHAR(1000)  NULL COMMENT '备注',
    revision                INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by              VARCHAR(50)    NULL COMMENT '创建人',
    created_time            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by              VARCHAR(50)    NULL COMMENT '更新人',
    updated_time            DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                 INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_variant_code (tenant_id, variant_code),
    CONSTRAINT fk_erp_product_variant_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    KEY idx_erp_product_variant_product (tenant_id, product_id, deleted),
    KEY idx_erp_product_variant_price (tenant_id, sale_price, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品规格价格';

CREATE TABLE erp_procurement_order (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    procurement_no           VARCHAR(50)    NOT NULL COMMENT '采购订单号，由ERP编码规则生成',
    supplier_id              BIGINT(20)     NOT NULL COMMENT '供应商ID',
    target_warehouse_id      BIGINT(20)     NOT NULL COMMENT '计划入库仓库ID',
    status_code              VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '采购状态，关联 PURCHASE_STATUS 字典项',
    expected_arrival_time    DATETIME(6)    NULL COMMENT '预计到货时间',
    total_quantity           DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '采购总数量，由明细汇总',
    total_amount             DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '采购总金额，由明细汇总',
    remark                   VARCHAR(1000)  NULL COMMENT '备注',
    revision                 INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by               VARCHAR(50)    NULL COMMENT '创建人',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by               VARCHAR(50)    NULL COMMENT '更新人',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_procurement_no (tenant_id, procurement_no),
    CONSTRAINT fk_erp_procurement_supplier FOREIGN KEY (supplier_id) REFERENCES erp_supplier_profile (id),
    CONSTRAINT fk_erp_procurement_warehouse FOREIGN KEY (target_warehouse_id) REFERENCES erp_inventory_warehouse (id),
    KEY idx_erp_procurement_supplier (tenant_id, supplier_id, created_time),
    KEY idx_erp_procurement_status (tenant_id, status_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购订单';

CREATE TABLE erp_procurement_order_line (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    procurement_order_id  BIGINT(20)     NOT NULL COMMENT '采购订单ID',
    line_no               INT            NOT NULL COMMENT '行号',
    product_id            BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)     NOT NULL COMMENT '商品规格ID',
    product_code_snapshot VARCHAR(50)    NULL COMMENT '商品编码快照',
    variant_code_snapshot VARCHAR(50)    NULL COMMENT '商品规格编码快照',
    product_name_snapshot VARCHAR(200)   NOT NULL COMMENT '商品名称快照',
    unit_code             VARCHAR(64)    NOT NULL COMMENT '采购单位，关联 PRODUCT_UNIT 字典项',
    quantity              DECIMAL(24,6)  NOT NULL COMMENT '采购数量',
    unit_price            DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '采购单价',
    line_amount           DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '采购金额',
    received_quantity     DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '已入库数量',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_erp_procurement_line_no (tenant_id, procurement_order_id, line_no, deleted),
    CONSTRAINT fk_erp_procurement_line_order FOREIGN KEY (procurement_order_id) REFERENCES erp_procurement_order (id),
    CONSTRAINT fk_erp_procurement_line_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_procurement_line_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_procurement_line_product (tenant_id, product_id, product_variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购订单明细';

CREATE TABLE erp_stock_balance (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    warehouse_id          BIGINT(20)     NOT NULL COMMENT '仓库ID',
    product_id            BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)     NOT NULL COMMENT '商品规格ID；无规格商品也应创建默认规格',
    available_quantity    DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '可用库存',
    locked_quantity       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '锁定库存',
    in_transit_quantity   DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '在途库存',
    revision              INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_stock_balance (tenant_id, warehouse_id, product_id, product_variant_id),
    CONSTRAINT fk_erp_stock_balance_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_inventory_warehouse (id),
    CONSTRAINT fk_erp_stock_balance_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_stock_balance_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_stock_balance_product (tenant_id, product_id, product_variant_id),
    KEY idx_erp_stock_balance_warehouse (tenant_id, warehouse_id, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP库存余额表';

CREATE TABLE erp_stock_flow (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    flow_no               VARCHAR(50)    NOT NULL COMMENT '库存流水号，由ERP编码规则生成',
    warehouse_id          BIGINT(20)     NOT NULL COMMENT '仓库ID',
    product_id            BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)     NOT NULL COMMENT '商品规格ID；无规格商品也应创建默认规格',
    business_type_code    VARCHAR(64)    NOT NULL COMMENT '业务类型：PURCHASE_IN/SALES_OUT/TRANSFER_OUT/TRANSFER_IN/ADJUST',
    business_order_id     BIGINT(20)     NULL COMMENT '业务单据ID',
    business_order_no     VARCHAR(50)    NULL COMMENT '业务单据号',
    quantity_delta        DECIMAL(24,6)  NOT NULL COMMENT '库存变动数量，入库为正，出库为负',
    before_quantity       DECIMAL(24,6)  NOT NULL COMMENT '变动前可用库存',
    after_quantity        DECIMAL(24,6)  NOT NULL COMMENT '变动后可用库存',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    created_by            VARCHAR(50)    NULL COMMENT '创建人',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_stock_flow_no (tenant_id, flow_no),
    CONSTRAINT fk_erp_stock_flow_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_inventory_warehouse (id),
    CONSTRAINT fk_erp_stock_flow_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_stock_flow_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_stock_flow_product (tenant_id, product_id, product_variant_id, created_time),
    KEY idx_erp_stock_flow_business (tenant_id, business_type_code, business_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP库存流水表';

CREATE TABLE erp_stock_in_order (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    stock_in_no           VARCHAR(50)    NOT NULL COMMENT '入库单号，由ERP编码规则生成',
    stock_in_type_code    VARCHAR(64)    NOT NULL COMMENT '入库类型，关联 STOCK_IN_TYPE 字典项',
    procurement_order_id  BIGINT(20)     NULL COMMENT '采购订单ID',
    procurement_no        VARCHAR(50)    NULL COMMENT '采购订单号快照',
    transfer_order_id     BIGINT(20)     NULL COMMENT '调拨单ID；调拨入库时用于追溯来源调拨单',
    transfer_order_no     VARCHAR(50)    NULL COMMENT '调拨单号快照',
    warehouse_id          BIGINT(20)     NOT NULL COMMENT '入库仓库ID',
    supplier_id           BIGINT(20)     NULL COMMENT '供应商ID',
    status_code           VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '入库单状态，关联 STOCK_IN_STATUS 字典项',
    stock_in_time         DATETIME(6)    NULL COMMENT '确认入库时间',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    revision              INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)    NULL COMMENT '创建人',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)    NULL COMMENT '更新人',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_stock_in_no (tenant_id, stock_in_no),
    CONSTRAINT fk_erp_stock_in_procurement FOREIGN KEY (procurement_order_id) REFERENCES erp_procurement_order (id),
    CONSTRAINT fk_erp_stock_in_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_inventory_warehouse (id),
    CONSTRAINT fk_erp_stock_in_supplier FOREIGN KEY (supplier_id) REFERENCES erp_supplier_profile (id),
    KEY idx_erp_stock_in_warehouse (tenant_id, warehouse_id, stock_in_time),
    KEY idx_erp_stock_in_procurement (tenant_id, procurement_order_id),
    KEY idx_erp_stock_in_transfer (tenant_id, transfer_order_no),
    KEY idx_erp_stock_in_status (tenant_id, status_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP入库单';

CREATE TABLE erp_stock_in_order_line (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    stock_in_order_id     BIGINT(20)     NOT NULL COMMENT '入库单ID',
    line_no               INT            NOT NULL COMMENT '行号',
    procurement_order_line_id BIGINT(20) NULL COMMENT '采购订单明细ID；采购入库时用于回写已入库数量',
    transfer_order_line_id BIGINT(20)    NULL COMMENT '调拨单明细ID；调拨入库时用于追溯来源调拨明细',
    product_id            BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)     NOT NULL COMMENT '商品规格ID；无规格商品也应创建默认规格',
    product_code_snapshot VARCHAR(50)    NULL COMMENT '商品编码快照',
    variant_code_snapshot VARCHAR(50)    NULL COMMENT '规格编码快照',
    product_name_snapshot VARCHAR(200)   NULL COMMENT '商品名称快照',
    unit_code             VARCHAR(64)    NOT NULL COMMENT '单位，关联 PRODUCT_UNIT 字典项',
    quantity              DECIMAL(24,6)  NOT NULL COMMENT '入库数量',
    unit_price            DECIMAL(24,6)  NULL COMMENT '入库单价',
    amount                DECIMAL(24,6)  NULL COMMENT '入库金额',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_erp_stock_in_line_no (tenant_id, stock_in_order_id, line_no, deleted),
    CONSTRAINT fk_erp_stock_in_line_order FOREIGN KEY (stock_in_order_id) REFERENCES erp_stock_in_order (id),
    CONSTRAINT fk_erp_stock_in_line_procurement_line FOREIGN KEY (procurement_order_line_id) REFERENCES erp_procurement_order_line (id),
    CONSTRAINT fk_erp_stock_in_line_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_stock_in_line_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_stock_in_line_procurement (tenant_id, procurement_order_line_id),
    KEY idx_erp_stock_in_line_transfer (tenant_id, transfer_order_line_id),
    KEY idx_erp_stock_in_line_product (tenant_id, product_id, product_variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP入库单明细';

CREATE TABLE erp_stock_out_order (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    stock_out_no          VARCHAR(50)    NOT NULL COMMENT '出库单号，由ERP编码规则生成',
    stock_out_type_code   VARCHAR(64)    NOT NULL COMMENT '出库类型，关联 STOCK_OUT_TYPE 字典项',
    warehouse_id          BIGINT(20)     NOT NULL COMMENT '出库仓库ID',
    sales_order_id        BIGINT(20)     NULL COMMENT '销售订单ID，跨Order服务引用',
    sales_order_no        VARCHAR(50)    NULL COMMENT '销售订单号快照',
    transfer_order_id     BIGINT(20)     NULL COMMENT '调拨单ID',
    transfer_order_no     VARCHAR(50)    NULL COMMENT '调拨单号快照',
    customer_id           BIGINT(20)     NULL COMMENT '客户ID，跨CRM服务引用',
    customer_name_snapshot VARCHAR(200)  NULL COMMENT '客户名称快照',
    status_code           VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '出库单状态，关联 STOCK_OUT_STATUS 字典项',
    stock_out_time        DATETIME(6)    NULL COMMENT '确认出库时间',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    revision              INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)    NULL COMMENT '创建人',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)    NULL COMMENT '更新人',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_stock_out_no (tenant_id, stock_out_no),
    CONSTRAINT fk_erp_stock_out_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_inventory_warehouse (id),
    KEY idx_erp_stock_out_sales (tenant_id, sales_order_no),
    KEY idx_erp_stock_out_transfer (tenant_id, transfer_order_no),
    KEY idx_erp_stock_out_warehouse (tenant_id, warehouse_id, stock_out_time),
    KEY idx_erp_stock_out_status (tenant_id, status_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP出库单';

CREATE TABLE erp_stock_out_order_line (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    stock_out_order_id    BIGINT(20)     NOT NULL COMMENT '出库单ID',
    line_no               INT            NOT NULL COMMENT '行号',
    sales_order_line_id   BIGINT(20)     NULL COMMENT '销售订单明细ID；销售出库时用于追溯来源销售明细，跨Order服务引用不建外键',
    transfer_order_line_id BIGINT(20)    NULL COMMENT '调拨单明细ID；调拨出库时用于追溯来源调拨明细',
    product_id            BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)     NOT NULL COMMENT '商品规格ID；无规格商品也应创建默认规格',
    product_code_snapshot VARCHAR(50)    NULL COMMENT '商品编码快照',
    variant_code_snapshot VARCHAR(50)    NULL COMMENT '规格编码快照',
    product_name_snapshot VARCHAR(200)   NULL COMMENT '商品名称快照',
    unit_code             VARCHAR(64)    NOT NULL COMMENT '单位，关联 PRODUCT_UNIT 字典项',
    quantity              DECIMAL(24,6)  NOT NULL COMMENT '出库数量',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_erp_stock_out_line_no (tenant_id, stock_out_order_id, line_no, deleted),
    CONSTRAINT fk_erp_stock_out_line_order FOREIGN KEY (stock_out_order_id) REFERENCES erp_stock_out_order (id),
    CONSTRAINT fk_erp_stock_out_line_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_stock_out_line_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_stock_out_line_sales (tenant_id, sales_order_line_id),
    KEY idx_erp_stock_out_line_transfer (tenant_id, transfer_order_line_id),
    KEY idx_erp_stock_out_line_product (tenant_id, product_id, product_variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP出库单明细';

CREATE TABLE erp_transfer_order (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    transfer_no           VARCHAR(50)    NOT NULL COMMENT '调拨单号，由ERP编码规则生成',
    source_warehouse_id   BIGINT(20)     NOT NULL COMMENT '来源仓库ID',
    target_warehouse_id   BIGINT(20)     NOT NULL COMMENT '目标仓库ID',
    status_code           VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '调拨单状态，关联 TRANSFER_STATUS 字典项',
    stock_out_time        DATETIME(6)    NULL COMMENT '确认调拨出库时间',
    stock_in_time         DATETIME(6)    NULL COMMENT '确认调拨入库时间',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    revision              INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)    NULL COMMENT '创建人',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)    NULL COMMENT '更新人',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_transfer_no (tenant_id, transfer_no),
    CONSTRAINT fk_erp_transfer_source_warehouse FOREIGN KEY (source_warehouse_id) REFERENCES erp_inventory_warehouse (id),
    CONSTRAINT fk_erp_transfer_target_warehouse FOREIGN KEY (target_warehouse_id) REFERENCES erp_inventory_warehouse (id),
    KEY idx_erp_transfer_source (tenant_id, source_warehouse_id, created_time),
    KEY idx_erp_transfer_target (tenant_id, target_warehouse_id, created_time),
    KEY idx_erp_transfer_status (tenant_id, status_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP调拨单';

CREATE TABLE erp_transfer_order_line (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    transfer_order_id     BIGINT(20)     NOT NULL COMMENT '调拨单ID',
    line_no               INT            NOT NULL COMMENT '行号',
    product_id            BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)     NOT NULL COMMENT '商品规格ID；无规格商品也应创建默认规格',
    product_code_snapshot VARCHAR(50)    NULL COMMENT '商品编码快照',
    variant_code_snapshot VARCHAR(50)    NULL COMMENT '规格编码快照',
    product_name_snapshot VARCHAR(200)   NULL COMMENT '商品名称快照',
    unit_code             VARCHAR(64)    NOT NULL COMMENT '单位，关联 PRODUCT_UNIT 字典项',
    quantity              DECIMAL(24,6)  NOT NULL COMMENT '调拨数量',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_erp_transfer_line_no (tenant_id, transfer_order_id, line_no, deleted),
    CONSTRAINT fk_erp_transfer_line_order FOREIGN KEY (transfer_order_id) REFERENCES erp_transfer_order (id),
    CONSTRAINT fk_erp_transfer_line_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_transfer_line_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_transfer_line_product (tenant_id, product_id, product_variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP调拨单明细';

-- Source: V20__erp_inventory_warehouse_status.sql
-- ERP仓库补充业务状态字段；V19 已暂存但不改写，继续用新迁移推进。
ALTER TABLE erp_inventory_warehouse
    ADD COLUMN status_code VARCHAR(64) NOT NULL DEFAULT 'ACTIVE'
        COMMENT '仓库状态，关联 WAREHOUSE_STATUS 字典项' AFTER contact_phone,
    ADD KEY idx_erp_inventory_warehouse_status (tenant_id, status_code, deleted, updated_time);

-- Source: V21__erp_product_specification_baseline.sql
-- ERP 商品规格自研业务表。
--
-- 业务口径：
-- 1. erp_product_specification 是我方商品中心维护的多规格主表。
-- 2. erp_product_specification_value 是规格下的子规格值，例如球杆型号下的 10、20、30。
-- 3. 订货宝规格后续只通过映射导入这些表，不再直接驱动 ERP 商品中心流程。
-- 4. 删除统一逻辑删，编码和状态值统一使用大写。

CREATE TABLE erp_product_specification (
    id                    BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)   NOT NULL COMMENT '租户ID',
    specification_code    VARCHAR(50)   NOT NULL COMMENT '多规格编号，业务可见且租户内唯一',
    specification_name    VARCHAR(120)  NOT NULL COMMENT '多规格名称',
    status_code           VARCHAR(64)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态，关联 PRODUCT_SPECIFICATION_STATUS 字典项',
    revision              INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)   NULL COMMENT '创建人',
    created_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)   NULL COMMENT '更新人',
    updated_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_specification_code (tenant_id, specification_code),
    KEY idx_erp_product_specification_name (tenant_id, specification_name, deleted),
    KEY idx_erp_product_specification_status (tenant_id, status_code, deleted, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品规格';

CREATE TABLE erp_product_specification_value (
    id                    BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)   NOT NULL COMMENT '租户ID',
    specification_id      BIGINT(20)    NOT NULL COMMENT '商品规格ID',
    value_code            VARCHAR(50)   NOT NULL COMMENT '子规格编号，所属规格下唯一',
    value_name            VARCHAR(120)  NOT NULL COMMENT '子规格名称',
    ordinal               INT           NOT NULL DEFAULT 0 COMMENT '排序值，数值越小越靠前',
    status_code           VARCHAR(64)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态，关联 PRODUCT_SPECIFICATION_VALUE_STATUS 字典项',
    revision              INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)   NULL COMMENT '创建人',
    created_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)   NULL COMMENT '更新人',
    updated_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_product_specification_value_code (tenant_id, specification_id, value_code),
    CONSTRAINT fk_erp_product_specification_value_spec
        FOREIGN KEY (specification_id) REFERENCES erp_product_specification (id),
    KEY idx_erp_product_specification_value_name (tenant_id, specification_id, value_name, deleted),
    KEY idx_erp_product_specification_value_sort (tenant_id, specification_id, deleted, ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP商品规格值';

-- Source: V22__erp_purchase_return_order_baseline.sql
-- ERP 自研采购退货业务表。
-- 采购退货是采购域业务单据，不再落到旧订货宝投影表，也不直接混入通用出库单。
-- 后续需要扣减库存时，由采购退货单生成或关联出库动作。

CREATE TABLE erp_purchase_return_order (
    id                            BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                     VARCHAR(64)    NOT NULL COMMENT '租户ID',
    purchase_return_no            VARCHAR(50)    NOT NULL COMMENT '采购退货单号，由ERP编码规则生成',
    supplier_id                   BIGINT(20)     NOT NULL COMMENT '供应商ID',
    warehouse_id                  BIGINT(20)     NOT NULL COMMENT '退货出库仓库ID',
    operator_staff_code           VARCHAR(50)    NULL COMMENT '经办员工编码，关联IAM员工中心员工编码',
    operator_staff_name_snapshot  VARCHAR(100)   NULL COMMENT '经办员工名称快照',
    status_code                   VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '采购退货状态，关联 PURCHASE_RETURN_STATUS 字典项',
    total_quantity                DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '退货总数量，由明细汇总或来源换算',
    total_amount                  DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '退货总金额',
    discount_amount               DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '优惠/折让金额',
    return_time                   DATETIME(6)    NULL COMMENT '退货发出时间',
    contact_name                  VARCHAR(100)   NULL COMMENT '联系人',
    contact_phone                 VARCHAR(50)    NULL COMMENT '联系电话',
    contact_address               VARCHAR(1000)  NULL COMMENT '联系地址',
    reason                        VARCHAR(1000)  NULL COMMENT '退货原因',
    remark                        VARCHAR(1000)  NULL COMMENT '备注',
    revision                      INT            NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by                    VARCHAR(50)    NULL COMMENT '创建人',
    created_time                  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by                    VARCHAR(50)    NULL COMMENT '更新人',
    updated_time                  DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                       INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_purchase_return_order_no (tenant_id, purchase_return_no),
    CONSTRAINT fk_erp_purchase_return_order_supplier FOREIGN KEY (supplier_id) REFERENCES erp_supplier_profile (id),
    CONSTRAINT fk_erp_purchase_return_order_warehouse FOREIGN KEY (warehouse_id) REFERENCES erp_inventory_warehouse (id),
    KEY idx_erp_purchase_return_order_supplier (tenant_id, supplier_id, created_time),
    KEY idx_erp_purchase_return_order_warehouse (tenant_id, warehouse_id, return_time),
    KEY idx_erp_purchase_return_order_staff (tenant_id, operator_staff_code, created_time),
    KEY idx_erp_purchase_return_order_status (tenant_id, status_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购退货单';

CREATE TABLE erp_purchase_return_order_line (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    purchase_return_order_id   BIGINT(20)     NOT NULL COMMENT '采购退货单ID',
    line_no                    INT            NOT NULL COMMENT '行号',
    procurement_order_id       BIGINT(20)     NULL COMMENT '关联采购订单ID；可由来源采购单号映射到ERP自研采购单',
    procurement_no_snapshot    VARCHAR(50)    NULL COMMENT '关联采购订单号快照',
    product_id                 BIGINT(20)     NOT NULL COMMENT '商品ID',
    product_variant_id         BIGINT(20)     NOT NULL COMMENT '商品规格ID',
    product_code_snapshot      VARCHAR(50)    NULL COMMENT '商品编码快照',
    variant_code_snapshot      VARCHAR(50)    NULL COMMENT '商品规格编码快照',
    product_name_snapshot      VARCHAR(200)   NOT NULL COMMENT '商品名称快照',
    unit_code                  VARCHAR(64)    NOT NULL COMMENT '退货单位，关联 PRODUCT_UNIT 字典项',
    requested_quantity         DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '申请退货数量',
    returned_quantity          DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '确认退货数量',
    unit_price                 DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '退货单价',
    confirmed_unit_price       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '确认退货单价',
    line_amount                DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '明细金额',
    cost_price                 DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '成本价快照',
    remark                     VARCHAR(1000)  NULL COMMENT '备注',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_erp_purchase_return_line_no (tenant_id, purchase_return_order_id, line_no, deleted),
    KEY idx_erp_purchase_return_line_procurement (tenant_id, procurement_order_id),
    KEY idx_erp_purchase_return_line_product (tenant_id, product_id, product_variant_id),
    CONSTRAINT fk_erp_purchase_return_line_order FOREIGN KEY (purchase_return_order_id) REFERENCES erp_purchase_return_order (id),
    CONSTRAINT fk_erp_purchase_return_line_procurement FOREIGN KEY (procurement_order_id) REFERENCES erp_procurement_order (id),
    CONSTRAINT fk_erp_purchase_return_line_product FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_purchase_return_line_variant FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP采购退货单明细';

-- Source: V23__drop_legacy_dhb_erp_projection_tables.sql
-- 删除旧订货宝导向的 ERP 投影表。
-- 新方案以 ERP 自研业务表为主：商品、规格、供应商、仓库、采购、采购退货、出入库、库存均使用 V19+ 新表。
-- 统一同步审计和来源绑定表仍保留，用于幂等、对账和来源追溯。
-- 旧表物理删除由 docs/LEGACY_DHB_TABLE_CLEANUP.sql 在确认备份和切流后使用 DBA 账号清理。
-- 日常 Flyway 迁移账号不授予 DROP 权限，自动迁移只记录新模型切换完成。
SELECT 1 AS legacy_erp_projection_cleanup_deferred;

-- Source: V24__erp_stock_out_source_identity.sql
-- ERP出库单补充外部来源幂等键。
-- 订货宝出库/发货单按 ships_num 唯一投影，避免同一销售订单多次发货时被 sales_order_id 限制，
-- 也避免同步重试重复扣减库存。

ALTER TABLE erp_stock_out_order
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER stock_out_no,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源出库/发货单号；订货宝为ships_num' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_stock_out_source (tenant_id, source_system_code, source_document_no),
    ADD KEY idx_erp_stock_out_source (tenant_id, source_system_code, source_document_no);

-- Source: V25__erp_transfer_order_source_identity.sql
-- ERP调拨单补充外部来源幂等键。
-- 订货宝调拨出库暂无独立调拨单接口时，按 ships_num 反推我方调拨单并保持重复同步幂等。

ALTER TABLE erp_transfer_order
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER transfer_no,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源调拨出库单号；订货宝为ships_num' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_transfer_source (tenant_id, source_system_code, source_document_no),
    ADD KEY idx_erp_transfer_external_source (tenant_id, source_system_code, source_document_no);

-- Source: V26__erp_connector_scoped_source_identity.sql
-- ERP 外部来源幂等键补充 Integration connector 维度。
-- erp_master_source_binding 是外部来源绑定表，connector_id 保持非空以避免 MySQL UNIQUE + NULL 放开重复。
-- 出库/调拨业务表保留 NULL，兼容人工单据和旧历史单据；订货宝同步入口在服务层强制非空。

ALTER TABLE erp_master_source_binding
    ADD COLUMN connector_id CHAR(36) NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000'
        COMMENT 'Integration连接器ID；旧来源绑定无法追溯时使用全0占位' AFTER tenant_id;

UPDATE erp_master_source_binding binding
    JOIN erp_master_data_sync_run run
      ON run.tenant_id = binding.tenant_id
     AND run.id = binding.last_sync_run_id
   SET binding.connector_id = run.connector_id
 WHERE run.connector_id IS NOT NULL
   AND binding.connector_id = '00000000-0000-0000-0000-000000000000';

ALTER TABLE erp_master_source_binding
    DROP INDEX uk_erp_master_source_binding_source,
    DROP INDEX uk_erp_master_source_binding_target,
    ADD UNIQUE KEY uk_erp_master_source_binding_source (
        tenant_id, connector_id, source_system, source_object_type, source_object_id
    ),
    ADD UNIQUE KEY uk_erp_master_source_binding_target (
        tenant_id, connector_id, source_system, target_type, target_id
    ),
    ADD KEY idx_erp_master_source_binding_connector (
        tenant_id, connector_id, source_system, source_object_type
    );

ALTER TABLE erp_stock_out_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；订货宝同步非空，人工或旧历史单据可为空' AFTER stock_out_no,
    DROP INDEX uk_erp_stock_out_source,
    DROP INDEX idx_erp_stock_out_source,
    ADD UNIQUE KEY uk_erp_stock_out_source (
        tenant_id, connector_id, source_system_code, source_document_no
    ),
    ADD KEY idx_erp_stock_out_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

ALTER TABLE erp_transfer_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；订货宝同步非空，人工或旧历史单据可为空' AFTER transfer_no,
    DROP INDEX uk_erp_transfer_source,
    DROP INDEX idx_erp_transfer_external_source,
    ADD UNIQUE KEY uk_erp_transfer_source (
        tenant_id, connector_id, source_system_code, source_document_no
    ),
    ADD KEY idx_erp_transfer_external_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

-- Source: V27__erp_unified_audit_columns.sql
-- ERP V27：统一仍在使用的 ERP 业务/同步表本地审计字段命名。
-- source_created_at/source_updated_at 是订货宝来源证据字段，保留原名。

ALTER TABLE erp_master_data_sync_checkpoint RENAME COLUMN version TO revision;
ALTER TABLE erp_master_data_sync_checkpoint RENAME COLUMN created_at TO created_time;
ALTER TABLE erp_master_data_sync_checkpoint RENAME COLUMN updated_at TO updated_time;
ALTER TABLE erp_master_data_sync_checkpoint ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_master_data_sync_checkpoint ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_data_sync_checkpoint ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_master_source_binding RENAME COLUMN version TO revision;
ALTER TABLE erp_master_source_binding RENAME COLUMN created_at TO created_time;
ALTER TABLE erp_master_source_binding RENAME COLUMN updated_at TO updated_time;
ALTER TABLE erp_master_source_binding ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_master_source_binding ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_source_binding ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_master_data_sync_run RENAME COLUMN created_at TO created_time;
ALTER TABLE erp_master_data_sync_run RENAME COLUMN updated_at TO updated_time;
ALTER TABLE erp_master_data_sync_run ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER finished_at;
ALTER TABLE erp_master_data_sync_run ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_data_sync_run ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_master_data_sync_lock ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER expires_at;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间' AFTER created_by;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间' AFTER updated_by;
ALTER TABLE erp_master_data_sync_lock ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_procurement_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_procurement_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_procurement_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_purchase_return_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_purchase_return_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_purchase_return_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_stock_balance ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_stock_balance ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_stock_balance ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_stock_flow ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_stock_flow ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE erp_stock_flow ADD COLUMN updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间' AFTER updated_by;
ALTER TABLE erp_stock_flow ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE erp_stock_in_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_stock_in_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_stock_in_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_stock_out_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_stock_out_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_stock_out_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE erp_transfer_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE erp_transfer_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE erp_transfer_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

-- Source: V28__erp_transfer_operator_staff_fields.sql
-- ERP 调拨单补充出库/入库经办人快照。
-- 订货宝调拨出库和调拨入库可能由不同业务人员操作，不能只用一个经办人字段覆盖。

ALTER TABLE erp_transfer_order
    ADD COLUMN outbound_operator_staff_code VARCHAR(50) NULL
        COMMENT '调拨出库经办员工编码，关联IAM员工中心员工编码' AFTER target_warehouse_id,
    ADD COLUMN outbound_operator_staff_name_snapshot VARCHAR(100) NULL
        COMMENT '调拨出库经办员工名称快照' AFTER outbound_operator_staff_code,
    ADD COLUMN inbound_operator_staff_code VARCHAR(50) NULL
        COMMENT '调拨入库经办员工编码，关联IAM员工中心员工编码' AFTER outbound_operator_staff_name_snapshot,
    ADD COLUMN inbound_operator_staff_name_snapshot VARCHAR(100) NULL
        COMMENT '调拨入库经办员工名称快照' AFTER inbound_operator_staff_code,
    ADD KEY idx_erp_transfer_outbound_operator (tenant_id, outbound_operator_staff_code, created_time),
    ADD KEY idx_erp_transfer_inbound_operator (tenant_id, inbound_operator_staff_code, created_time);

-- Source: V29__erp_procurement_and_stock_in_source_identity.sql
-- ERP 采购订单和入库单补充外部来源识别字段。
-- Portal 用这些字段区分订货宝同步投影和 ERP 内部可操作单据，并支持来源追溯。

ALTER TABLE erp_procurement_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；外部同步单据非空，人工或旧历史单据可为空' AFTER procurement_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL
        COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL
        COMMENT '来源采购单号；订货宝为采购订单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_procurement_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

ALTER TABLE erp_stock_in_order
    ADD COLUMN connector_id CHAR(36) NULL
        COMMENT 'Integration连接器ID；外部同步单据非空，人工或旧历史单据可为空' AFTER stock_in_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL
        COMMENT '来源系统编码；订货宝同步时为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL
        COMMENT '来源入库凭证号；订货宝为入库单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_erp_stock_in_source (
        tenant_id, connector_id, source_system_code, source_document_no
    );

UPDATE erp_procurement_order procurement
    JOIN erp_master_source_binding binding
      ON binding.tenant_id = procurement.tenant_id
     AND binding.target_type = 'PROCUREMENT_ORDER'
     AND binding.target_id = CAST(procurement.id AS CHAR)
     AND binding.source_system = 'DINGHUOBAO'
     AND binding.source_object_type = 'PURCHASE_ORDER'
     AND binding.source_presence = 'PRESENT'
     AND COALESCE(binding.deleted, 0) = 0
   SET procurement.connector_id = binding.connector_id,
       procurement.source_system_code = binding.source_system,
       procurement.source_document_no = COALESCE(binding.source_code, binding.source_object_id)
 WHERE procurement.source_system_code IS NULL
   AND binding.connector_id IS NOT NULL
   AND COALESCE(binding.source_code, binding.source_object_id) IS NOT NULL;

UPDATE erp_stock_in_order stock_in
    JOIN erp_master_source_binding binding
      ON binding.tenant_id = stock_in.tenant_id
     AND binding.target_type = 'STOCK_IN_ORDER'
     AND binding.target_id = CAST(stock_in.id AS CHAR)
     AND binding.source_system = 'DINGHUOBAO'
     AND binding.source_object_type = 'WAREHOUSING_RECEIPT'
     AND binding.source_presence = 'PRESENT'
     AND COALESCE(binding.deleted, 0) = 0
   SET stock_in.connector_id = binding.connector_id,
       stock_in.source_system_code = binding.source_system,
       stock_in.source_document_no = COALESCE(binding.source_code, binding.source_object_id)
 WHERE stock_in.source_system_code IS NULL
   AND binding.connector_id IS NOT NULL
   AND COALESCE(binding.source_code, binding.source_object_id) IS NOT NULL;

-- Source: V30__erp_feishu_product_source_fields.sql
-- ERP 商品承接飞书产品主数据来源字段。
-- 结构化分类/品牌ID仍走ERP主数据；飞书原文快照用于导入闭环、筛选和后续补主数据。

ALTER TABLE erp_product
    ADD COLUMN business_line_name VARCHAR(120) NULL COMMENT '业务线名称，飞书业务线原文' AFTER product_name,
    ADD COLUMN category_name_snapshot VARCHAR(120) NULL COMMENT '商品分类名称快照，飞书品类原文' AFTER category_id,
    ADD COLUMN brand_name_snapshot VARCHAR(120) NULL COMMENT '商品品牌名称快照，飞书品牌原文' AFTER brand_id,
    ADD COLUMN industry_name VARCHAR(120) NULL COMMENT '行业名称，飞书行业原文' AFTER brand_name_snapshot,
    ADD COLUMN source_status_name VARCHAR(80) NULL COMMENT '来源商品状态名称' AFTER shelf_status_code,
    ADD COLUMN source_system_code VARCHAR(32) NULL COMMENT '来源系统编码，如FEISHU' AFTER remark,
    ADD COLUMN source_tenant_key VARCHAR(128) NULL COMMENT '来源租户/表标识' AFTER source_system_code,
    ADD COLUMN source_product_id VARCHAR(128) NULL COMMENT '来源商品ID' AFTER source_tenant_key,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源单号/来源商品编码' AFTER source_product_id,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源创建时间' AFTER source_document_no,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源更新时间' AFTER source_created_at,
    ADD COLUMN source_payload_hash CHAR(64) NULL COMMENT '来源字段SHA-256摘要' AFTER source_updated_at,
    ADD COLUMN source_payload_json JSON NULL COMMENT '来源原始字段快照' AFTER source_payload_hash,
    ADD KEY idx_erp_product_business_line (tenant_id, business_line_name, deleted),
    ADD KEY idx_erp_product_source_doc (tenant_id, source_system_code, source_document_no),
    ADD UNIQUE KEY uk_erp_product_source (tenant_id, source_system_code, source_tenant_key, source_product_id);

-- Source: V31__erp_order_execution_receipt.sql
CREATE TABLE erp_order_execution_receipt (
    tenant_id VARCHAR(64) NOT NULL,execution_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,request_hash VARCHAR(64) NOT NULL,
    stock_out_id BIGINT NULL,stock_out_no VARCHAR(64) NULL,stock_out_time DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,execution_id),UNIQUE KEY uk_erp_execution_order(tenant_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V32__erp_product_ordinal.sql
-- ERP商品补充排序字段，供商品管理列表按业务顺序展示。
-- 存量行统一落 0，数值越小越靠前；排序值相同时列表仍按 updated_time 倒序兜底。
ALTER TABLE erp_product
    ADD COLUMN ordinal INT NOT NULL DEFAULT 0
        COMMENT '排序值，数值越小越靠前' AFTER shelf_status_code,
    ADD KEY idx_erp_product_ordinal (tenant_id, deleted, ordinal);

-- Source: V33__erp_customer_type_price.sql
-- ERP 客户类型等级价：按商品规格 + 客户类型维护协议订货价。
--
-- 业务口径：
-- 1. 一个商品规格在一个客户类型下最多一条等级价，由唯一键兜底。
-- 2. customer_type_code 引用 CRM 客户类型业务编码；名称不落库，展示时按 CRM 数据转换。
-- 3. 订货宝接口只提供等级价设置接口、没有读取接口，等级价由本地维护和导入建立。
-- 4. 删除统一逻辑删；同一规格与客户类型重新维护时复用原记录恢复，不新增重复行。

CREATE TABLE erp_customer_type_price (
    id                    BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)   NOT NULL COMMENT '租户ID',
    product_id            BIGINT(20)    NOT NULL COMMENT '商品ID',
    product_variant_id    BIGINT(20)    NOT NULL COMMENT '商品规格ID',
    customer_type_code    VARCHAR(128)  NOT NULL COMMENT '客户类型编码，引用CRM客户类型type_code',
    sale_price            DECIMAL(24,6) NOT NULL COMMENT '等级订货价（小单位）',
    remark                VARCHAR(500)  NULL COMMENT '备注',
    revision              INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by            VARCHAR(50)   NULL COMMENT '创建人',
    created_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)   NULL COMMENT '更新人',
    updated_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_erp_customer_type_price_variant_type (tenant_id, product_variant_id, customer_type_code),
    CONSTRAINT fk_erp_customer_type_price_product
        FOREIGN KEY (product_id) REFERENCES erp_product (id),
    CONSTRAINT fk_erp_customer_type_price_variant
        FOREIGN KEY (product_variant_id) REFERENCES erp_product_variant (id),
    KEY idx_erp_customer_type_price_type (tenant_id, customer_type_code, deleted, updated_time),
    KEY idx_erp_customer_type_price_product (tenant_id, product_id, deleted, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='ERP客户类型等级价';

-- Source: V34__erp_product_big_unit.sql
-- ERP 商品大包装单位与换算：等级价按基础单位维护，展示时支持按大单位换算展示。
--
-- 业务口径：
-- 1. big_unit_code 是大包装单位名称快照，如方便面「箱」，基础单位为「桶」。
-- 2. base_to_big_rate 是基础单位到大包装单位的换算率，如 1箱=12桶 时为 12，可为空。
-- 3. 订货宝同步时从 getGoodsList 的 bigunits/conversionnumber 落库；手工商品可后续在商品表单维护。

ALTER TABLE erp_product
    ADD COLUMN big_unit_code VARCHAR(50) NULL COMMENT '大包装单位名称，如箱' AFTER unit_code,
    ADD COLUMN base_to_big_rate DECIMAL(24,6) NULL COMMENT '基础单位到大包装单位换算率，如1箱=12桶时为12' AFTER big_unit_code;

-- Source: V35__erp_product_middle_unit.sql
-- ERP 商品中包装单位与换算：与基础单位、大包装单位共同构成商品单位口径。
--
-- 业务口径：
-- 1. middle_unit_code 是中包装单位编码，关联 PRODUCT_UNIT 字典项，如「打」。
-- 2. base_to_middle_rate 是基础单位到中包装单位的换算率，如 1打=6桶 时为 6，可为空。
-- 3. 订货宝同步时从 getGoodsList 的 middle_units/base2middle_unit_rate 落库。

ALTER TABLE erp_product
    ADD COLUMN middle_unit_code VARCHAR(50) NULL COMMENT '中包装单位编码，关联PRODUCT_UNIT字典项' AFTER unit_code,
    ADD COLUMN base_to_middle_rate DECIMAL(24,6) NULL COMMENT '基础单位到中包装单位换算率，如1打=6桶时为6' AFTER middle_unit_code;

-- Source: V36__erp_product_statistics_unit.sql
-- ERP 商品默认统计单位：订单明细等页面按该层级换算数量与单价，金额口径不变。
--
-- 业务口径：
-- 1. statistics_unit_level 复用现有三级单位：BASE=基础单位、MIDDLE=中包装单位、BIG=大包装单位。
-- 2. 为空按 BASE 处理，保持存量商品行为不变。
-- 3. 订货宝按箱售卖时，中包装单位通常就是箱，默认统计单位选 MIDDLE 即按箱统计。
ALTER TABLE erp_product
    ADD COLUMN statistics_unit_level VARCHAR(16) NULL COMMENT '默认统计单位层级：BASE/MIDDLE/BIG，空按基础单位' AFTER base_to_big_rate;

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
