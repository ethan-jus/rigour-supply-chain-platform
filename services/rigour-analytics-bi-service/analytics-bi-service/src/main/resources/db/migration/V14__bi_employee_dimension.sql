-- HR 是人员主数据单一写者；本表仅为 BI 刷新任务维护的可重建投影。
CREATE TABLE bi_employee_dim (
    tenant_id VARCHAR(64) NOT NULL,
    employee_code VARCHAR(50) NOT NULL,
    employee_name VARCHAR(128) NOT NULL,
    employment_status VARCHAR(32) NOT NULL,
    city_name VARCHAR(160) NULL,
    region_code VARCHAR(128) NULL,
    position_name VARCHAR(120) NULL,
    department_name VARCHAR(128) NULL,
    entry_date DATETIME(6) NULL,
    leave_date DATETIME(6) NULL,
    synced_time DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, employee_code),
    KEY idx_bi_employee_region (tenant_id, region_code, employment_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HR当前员工统计投影';

CREATE TABLE bi_employee_snapshot (
    tenant_id VARCHAR(64) NOT NULL PRIMARY KEY,
    synced_time DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工投影完整刷新标记，区分未接入和空档案';
