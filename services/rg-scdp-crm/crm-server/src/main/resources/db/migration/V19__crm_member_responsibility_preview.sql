-- 预览只保存短期确认凭证和版本，不创建另一份用户客户授权名单。
CREATE TABLE crm_member_responsibility_preview (
    token VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    target_user_id VARCHAR(36) NOT NULL,
    target_employee_code VARCHAR(50) NOT NULL,
    target_fingerprint VARCHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,token),
    KEY idx_crm_responsibility_preview_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE crm_member_responsibility_preview_item (
    tenant_id VARCHAR(64) NOT NULL,
    token VARCHAR(36) NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_revision BIGINT NOT NULL,
    PRIMARY KEY(tenant_id,token,customer_id),
    CONSTRAINT fk_crm_responsibility_preview FOREIGN KEY(tenant_id,token)
        REFERENCES crm_member_responsibility_preview(tenant_id,token) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
