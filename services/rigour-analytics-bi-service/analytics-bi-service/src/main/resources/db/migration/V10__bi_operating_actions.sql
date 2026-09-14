-- BI仅持有运营跟进及目标修订，不写订单资金、库存和人事薪资。
CREATE TABLE bi_operating_action (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    kind VARCHAR(24) NOT NULL,
    business_ref VARCHAR(160) NOT NULL,
    business_label VARCHAR(240) NOT NULL,
    city_code VARCHAR(64) NULL,
    employee_code VARCHAR(64) NULL,
    assignee VARCHAR(160) NOT NULL,
    due_at DATETIME(6) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    note VARCHAR(2000) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_by VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_operating_action_tenant_id (tenant_id, id),
    KEY idx_bi_action_queue (tenant_id, status, due_at),
    KEY idx_bi_action_business (tenant_id, kind, business_ref),
    KEY idx_bi_action_scope (tenant_id, city_code, employee_code, due_at),
    CONSTRAINT chk_bi_action_kind CHECK (kind IN ('COLLECTION', 'CUSTOMER', 'STOCK')),
    CONSTRAINT chk_bi_action_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'DISMISSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI运营跟进，不代表业务单据完成';

CREATE TABLE bi_operating_action_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    action_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    previous_status VARCHAR(24) NULL,
    status VARCHAR(24) NOT NULL,
    previous_assignee VARCHAR(160) NULL,
    assignee VARCHAR(160) NOT NULL,
    previous_due_at DATETIME(6) NULL,
    due_at DATETIME(6) NOT NULL,
    note VARCHAR(2000) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_action_event_revision (tenant_id, action_id, revision),
    CONSTRAINT fk_bi_action_event_action FOREIGN KEY (tenant_id, action_id)
        REFERENCES bi_operating_action (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可覆盖的运营处理记录';

CREATE TABLE bi_business_target_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    revision INT NOT NULL,
    target_value DECIMAL(24,6) NOT NULL,
    dimension_name VARCHAR(160) NULL,
    remark VARCHAR(1000) NULL,
    deleted INT NOT NULL DEFAULT 0,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_target_event_revision (tenant_id, target_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI经营目标修订记录';
