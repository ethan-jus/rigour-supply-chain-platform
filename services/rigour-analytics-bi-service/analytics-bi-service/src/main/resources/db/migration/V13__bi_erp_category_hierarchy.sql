-- ERP分类全量投影，含无成交分类；只在BI刷新事务中维护。
CREATE TABLE bi_product_category_dim (
    tenant_id VARCHAR(64) NOT NULL,
    category_id BIGINT NOT NULL,
    category_code VARCHAR(64) NULL,
    category_name VARCHAR(160) NOT NULL,
    parent_id BIGINT NULL,
    category_level INT NULL,
    ordinal INT NULL,
    deleted INT NOT NULL DEFAULT 0,
    source_updated_time DATETIME(6) NULL,
    synced_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, category_id),
    KEY idx_bi_category_parent (tenant_id, parent_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_product_category_closure (
    tenant_id VARCHAR(64) NOT NULL,
    ancestor_id BIGINT NOT NULL,
    descendant_id BIGINT NOT NULL,
    depth INT NOT NULL,
    PRIMARY KEY (tenant_id, ancestor_id, descendant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
