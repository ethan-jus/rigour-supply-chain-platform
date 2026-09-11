-- Analytics BI：ERP 商品维度快照。
--
-- 商品销售看板按商品模式以 ERP 有效商品为底表，再左连订单行事实。

CREATE TABLE bi_product_dim (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    product_id                 BIGINT(20)     NOT NULL COMMENT 'ERP商品ID',
    product_code               VARCHAR(50)    NULL COMMENT '商品编码快照',
    product_name               VARCHAR(200)   NULL COMMENT '商品名称快照',
    product_category_id        BIGINT(20)     NULL COMMENT '商品分类ID快照',
    product_category_code      VARCHAR(50)    NULL COMMENT '商品分类编码快照',
    product_category_name      VARCHAR(120)   NULL COMMENT '商品分类名称快照',
    brand_id                   BIGINT(20)     NULL COMMENT '商品品牌ID快照',
    brand_code                 VARCHAR(50)    NULL COMMENT '商品品牌编码快照',
    brand_name                 VARCHAR(120)   NULL COMMENT '商品品牌名称快照',
    shelf_status_code          VARCHAR(64)    NULL COMMENT '上架状态编码快照',
    submit_status_code         VARCHAR(64)    NULL COMMENT '提交状态编码快照',
    source_updated_time        DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time                DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_product_dim_source (tenant_id, product_id),
    KEY idx_bi_product_dim_category (tenant_id, product_category_id, deleted),
    KEY idx_bi_product_dim_brand (tenant_id, brand_id, deleted),
    KEY idx_bi_product_dim_status (tenant_id, shelf_status_code, submit_status_code, deleted),
    KEY idx_bi_product_dim_updated (tenant_id, source_updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI商品维度';
