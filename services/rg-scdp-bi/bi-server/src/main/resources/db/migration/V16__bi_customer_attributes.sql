-- CRM 维护客户来源和经营类别，BI 只保存可重建的当前档案投影。
CREATE TABLE bi_customer_attribute_current (
    tenant_id VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_source_name VARCHAR(120) NULL,
    business_category_name VARCHAR(120) NULL,
    synced_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户来源和经营类别当前投影';

CREATE TABLE bi_customer_attribute_snapshot (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    synced_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户属性完整刷新标记';
