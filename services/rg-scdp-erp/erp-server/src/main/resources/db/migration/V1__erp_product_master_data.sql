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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP品牌主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品分类树';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP规格维度主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP规格值主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品标签主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP规范商品SPU主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP规范SKU主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP SPU分类关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP SPU规格维度关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP SKU规格值关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP SPU标签关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品主数据同步批次';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品主数据同步游标';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品主数据外部来源绑定';
