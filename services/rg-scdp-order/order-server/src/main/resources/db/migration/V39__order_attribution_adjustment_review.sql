-- 历史归属采用明确证据、双人复核和不可变的调整前后记录，普通更新不能覆盖。
CREATE TABLE order_attribution_adjustment (
 tenant_id VARCHAR(64) NOT NULL, id VARCHAR(36) NOT NULL, order_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', expected_order_revision INT NOT NULL,
 expected_snapshot_revision BIGINT NOT NULL, before_json JSON NOT NULL, proposed_json JSON NOT NULL,
 source_system_code VARCHAR(64) NULL, source_order_no VARCHAR(128) NULL,
 evidence_ref VARCHAR(1000) NOT NULL, evidence_text VARCHAR(4000) NOT NULL, reason VARCHAR(1000) NOT NULL,
 proposed_by VARCHAR(64) NOT NULL, proposed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 reviewed_by VARCHAR(64) NULL, reviewed_at DATETIME(6) NULL, review_reason VARCHAR(1000) NULL,
 PRIMARY KEY(tenant_id,id), KEY ix_order_adjustment(tenant_id,order_id,proposed_at),
 CONSTRAINT fk_attribution_adjustment_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id),
 CONSTRAINT ck_attribution_adjustment_status CHECK(status IN ('PENDING','APPLIED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
