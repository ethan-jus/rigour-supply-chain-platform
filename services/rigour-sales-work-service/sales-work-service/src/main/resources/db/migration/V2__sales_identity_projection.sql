-- Sales Work V2：IAM用户/HR员工到销售画像的只读身份投影。
-- 本表由身份或HR事件消费者维护，不能由H5或Portal直接写入。

CREATE TABLE sales_identity_projection (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    platform_user_id BINARY(16) NOT NULL,
    employee_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_identity_projection PRIMARY KEY (id),
    CONSTRAINT uk_sales_identity_projection_user UNIQUE (tenant_id, platform_user_id),
    CONSTRAINT uk_sales_identity_projection_employee UNIQUE (tenant_id, employee_id),
    INDEX idx_sales_identity_projection_active (
        tenant_id, platform_user_id, status, effective_from, effective_to
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IAM平台用户到HR员工的Sales Work只读身份投影';
