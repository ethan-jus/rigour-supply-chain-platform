CREATE TABLE bi_sales_contact_fact (
    tenant_id VARCHAR(64) NOT NULL,
    submission_id VARCHAR(36) NOT NULL,
    store_id VARCHAR(36) NOT NULL,
    city_name VARCHAR(160) NULL,
    region_code VARCHAR(128) NULL,
    submitted_at DATETIME(6) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    PRIMARY KEY (tenant_id, submission_id),
    KEY idx_bi_contact_date (tenant_id, submitted_at, region_code, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sales已提交未删除的拜访记录，含审核状态';

CREATE TABLE bi_sales_contact_snapshot (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    synced_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sales建联投影完整刷新标记';

CREATE TABLE bi_sales_contact_city_dim (
    tenant_id VARCHAR(64) NOT NULL,
    region_code VARCHAR(128) NOT NULL,
    city_name VARCHAR(160) NOT NULL,
    PRIMARY KEY (tenant_id, region_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='城市建联统计目录，保留期间无凭证的城市';
