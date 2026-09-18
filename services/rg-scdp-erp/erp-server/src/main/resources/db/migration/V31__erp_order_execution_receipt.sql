CREATE TABLE erp_order_execution_receipt (
    tenant_id VARCHAR(64) NOT NULL,execution_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,request_hash VARCHAR(64) NOT NULL,
    stock_out_id BIGINT NULL,stock_out_no VARCHAR(64) NULL,stock_out_time DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,execution_id),UNIQUE KEY uk_erp_execution_order(tenant_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
