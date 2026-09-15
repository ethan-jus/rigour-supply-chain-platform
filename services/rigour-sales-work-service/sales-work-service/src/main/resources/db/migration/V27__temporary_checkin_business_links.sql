-- 仅存业务关联，不建立 IAM 用户、角色或登录身份。名称只用于首次核对，刷新按固定编码关联。
CREATE TABLE temp_sales_checkin_employee_link (
    tenant_id BINARY(16) NOT NULL,
    salesperson_id BINARY(16) NOT NULL,
    employee_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    match_rule VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL,
    batch_id CHAR(36) NOT NULL,
    linked_by VARCHAR(128) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, salesperson_id),
    UNIQUE KEY uk_temp_employee_link_target (tenant_id, employee_code),
    CONSTRAINT fk_temp_employee_link_salesperson FOREIGN KEY (tenant_id, salesperson_id)
        REFERENCES temp_sales_checkin_salesperson (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sales 人员到 HR 员工编码的已核对业务关联';

CREATE TABLE temp_sales_checkin_customer_link (
    tenant_id BINARY(16) NOT NULL,
    store_id BINARY(16) NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    match_rule VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL,
    batch_id CHAR(36) NOT NULL,
    linked_by VARCHAR(128) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, store_id),
    UNIQUE KEY uk_temp_customer_link_target (tenant_id, customer_id),
    UNIQUE KEY uk_temp_customer_link_code (tenant_id, customer_code),
    CONSTRAINT fk_temp_customer_link_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES temp_sales_checkin_store (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sales 门店到 CRM 客户主键及编码的已核对业务关联';
