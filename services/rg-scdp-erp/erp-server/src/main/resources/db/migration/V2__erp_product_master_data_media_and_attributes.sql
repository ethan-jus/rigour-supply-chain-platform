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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品标签分组';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品图片及COS对象 key';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品及SKU多价格类型';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品多层级单位与条码';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品库存上下限与安全库存';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品可扩展来源字段';
