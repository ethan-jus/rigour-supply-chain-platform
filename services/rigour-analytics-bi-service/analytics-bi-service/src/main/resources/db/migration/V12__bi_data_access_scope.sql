-- IAM/HR/CRM 的已验证只读授权投影。不从姓名或既有业务可见性推导授权，不自动插入用户授权。
CREATE TABLE bi_data_access_identity (
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    employee_code VARCHAR(50) NOT NULL,
    owner_staff_code VARCHAR(50) NOT NULL,
    iam_binding_ref VARCHAR(128) NOT NULL,
    hr_employee_ref VARCHAR(128) NOT NULL,
    crm_employee_ref VARCHAR(128) NOT NULL,
    user_security_version BIGINT NOT NULL,
    tenant_policy_version BIGINT NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    KEY idx_bi_scope_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_data_access_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    actor_id VARCHAR(36) NOT NULL,
    operation_code VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_bi_access_audit_subject (tenant_id, user_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bi_data_access_scope (
    tenant_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    region_code VARCHAR(64) NOT NULL,
    iam_policy_ref VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, user_id, role_code, scope_type, region_code),
    CONSTRAINT fk_bi_scope_identity FOREIGN KEY (tenant_id, user_id)
        REFERENCES bi_data_access_identity (tenant_id, user_id) ON DELETE CASCADE,
    CONSTRAINT chk_bi_scope_type CHECK (scope_type IN ('SELF', 'MY_CITY', 'MY_REGION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
