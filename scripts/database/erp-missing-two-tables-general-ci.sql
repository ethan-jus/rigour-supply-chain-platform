-- Manual supplement for an ERP database where these two tables are missing.
-- Select the intended ERP database before running this file.
-- erp_product and erp_product_variant must already exist with their original primary keys.
-- Existing tables are not dropped or altered. No business data or Flyway history is inserted.
-- Stop on the first error. This is not a script to rerun over existing tables.

-- Source: V31__erp_order_execution_receipt.sql
CREATE TABLE erp_order_execution_receipt (
    tenant_id VARCHAR(64) NOT NULL,execution_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,request_hash VARCHAR(64) NOT NULL,
    stock_out_id BIGINT NULL,stock_out_no VARCHAR(64) NULL,stock_out_time DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,execution_id),UNIQUE KEY uk_erp_execution_order(tenant_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V33__erp_customer_type_price.sql
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
