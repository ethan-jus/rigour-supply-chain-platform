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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品规格';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ERP商品规格值';
