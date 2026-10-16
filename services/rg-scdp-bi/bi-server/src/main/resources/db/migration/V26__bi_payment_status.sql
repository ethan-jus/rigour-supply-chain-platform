-- Narrow status projection: never infer a received payment from its amount alone.
CREATE TABLE bi_payment_status (
    id BIGINT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    payment_status_code VARCHAR(64) NULL,
    PRIMARY KEY (tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单回款状态投影';
