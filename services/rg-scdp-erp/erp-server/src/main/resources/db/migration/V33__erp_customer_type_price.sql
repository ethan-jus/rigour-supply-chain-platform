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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP客户类型等级价';
