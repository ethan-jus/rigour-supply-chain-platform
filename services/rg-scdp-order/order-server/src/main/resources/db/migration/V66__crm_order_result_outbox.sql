ALTER TABLE order_sales_order
 ADD COLUMN crm_fact_revision BIGINT NOT NULL DEFAULT 0,
 ADD KEY ix_order_crm_seed(crm_fact_revision,store_id,id);
CREATE TABLE order_crm_result_outbox (
 id BIGINT NOT NULL,
 tenant_id VARCHAR(64) NOT NULL,
 order_id BIGINT NOT NULL,
 source_revision BIGINT NOT NULL,
 payload_json JSON NOT NULL,
 status_code VARCHAR(24) NOT NULL DEFAULT 'PENDING',
 retry_count INT NOT NULL DEFAULT 0,
 next_retry_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,
 delivered_at DATETIME(6) NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_order_crm_result(tenant_id,order_id,source_revision),
 KEY ix_order_crm_delivery(status_code,next_retry_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
