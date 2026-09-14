-- 只保存显式复核作业生成的不可变证据，不变更任何领域业务数据。
CREATE TABLE bi_reconciliation_review (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    source_batch_id VARCHAR(36) NOT NULL,
    captured_time DATETIME(6) NOT NULL,
    completed_time DATETIME(6) NOT NULL,
    review_json JSON NOT NULL COMMENT '版本、范围、原始口径、逐订单/SKU差异；无附件和凭据',
    PRIMARY KEY (id),
    KEY idx_bi_review_actor (tenant_id, actor_id, captured_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI只读来源复核证据';
