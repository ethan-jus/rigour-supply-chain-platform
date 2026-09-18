-- 不根据今天的客户或组织补造历史归属。存量订单由迁移预检逐项核对。
ALTER TABLE order_sales_order ADD UNIQUE KEY uk_order_tenant_id(tenant_id,id),
    ADD COLUMN selected_warehouse_id BIGINT NULL,
    ADD COLUMN warehouse_selected_by VARCHAR(64) NULL,
    ADD COLUMN warehouse_selected_at DATETIME(6) NULL;
CREATE TABLE order_attribution_snapshot (
    tenant_id VARCHAR(64) NOT NULL,order_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,employee_code VARCHAR(50) NULL,employee_name VARCHAR(128) NULL,
    department_id BIGINT NULL,department_name VARCHAR(160) NULL,department_path JSON NOT NULL,
    region_code VARCHAR(128) NULL,region_path JSON NOT NULL,source_version VARCHAR(64) NOT NULL,
    customer_revision BIGINT NOT NULL,employee_revision BIGINT NOT NULL,organization_version BIGINT NOT NULL,
    resolved_at DATETIME(6) NOT NULL,frozen_at DATETIME(6) NULL,revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(tenant_id,order_id),CONSTRAINT ck_order_attribution_state CHECK(state IN ('DRAFT','FROZEN','REVIEW')),
    CONSTRAINT fk_order_attribution_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE order_fulfillment_execution (
    tenant_id VARCHAR(64) NOT NULL,order_id BIGINT NOT NULL,execution_id VARCHAR(36) NOT NULL,
    warehouse_id BIGINT NOT NULL,order_revision INT NOT NULL,request_json JSON NOT NULL,request_hash VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    erp_stock_out_id BIGINT NULL,erp_stock_out_no VARCHAR(64) NULL,erp_stock_out_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,order_id),UNIQUE KEY uk_order_execution(tenant_id,execution_id),
    CONSTRAINT ck_order_execution_state CHECK(status IN ('PREPARED','EXECUTING','ERP_CONFIRMED','COMPLETED','REVIEW')),
    CONSTRAINT fk_order_execution_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
