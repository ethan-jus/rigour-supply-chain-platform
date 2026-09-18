-- IAM租户RBAC与DataScope。角色授权不得超过租户有效套餐，由应用事务校验。

CREATE TABLE iam_role (
    id            BINARY(16)      NOT NULL,
    tenant_id     BINARY(16)      NOT NULL,
    role_code     VARCHAR(64)     NOT NULL,
    role_name     VARCHAR(128)    NOT NULL,
    role_type     VARCHAR(32)     NOT NULL,
    description   VARCHAR(500)    NULL,
    status        VARCHAR(32)     NOT NULL,
    version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME(6)     NOT NULL,
    created_by    BINARY(16)      NULL,
    updated_at    DATETIME(6)     NOT NULL,
    updated_by    BINARY(16)      NULL,
    deleted_at    DATETIME(6)     NULL,
    deleted_by    BINARY(16)      NULL,
    delete_reason VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_role PRIMARY KEY (id),
    CONSTRAINT uk_iam_role_code UNIQUE (tenant_id, role_code),
    CONSTRAINT uk_iam_role_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_role_type CHECK (role_type IN ('SYSTEM', 'CUSTOM')),
    CONSTRAINT ck_iam_role_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_role_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_role_status (tenant_id, status, role_type, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户角色';

CREATE TABLE iam_user_role (
    tenant_id      BINARY(16)  NOT NULL,
    user_id        BINARY(16)  NOT NULL,
    role_id        BINARY(16)  NOT NULL,
    status         VARCHAR(32) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to   DATETIME(6) NULL,
    created_at     DATETIME(6) NOT NULL,
    created_by     BINARY(16)  NULL,
    updated_at     DATETIME(6) NOT NULL,
    updated_by     BINARY(16)  NULL,
    CONSTRAINT pk_iam_user_role PRIMARY KEY (tenant_id, user_id, role_id),
    CONSTRAINT ck_iam_user_role_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_iam_user_role_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT fk_iam_user_role_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES iam_user (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_user_role_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES iam_role (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_user_role_role (tenant_id, role_id, status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户当前角色关系';

CREATE TABLE iam_role_resource (
    tenant_id  BINARY(16)  NOT NULL,
    role_id    BINARY(16)  NOT NULL,
    resource_id BINARY(16) NOT NULL,
    status     VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16)  NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16)  NULL,
    CONSTRAINT pk_iam_role_resource PRIMARY KEY (tenant_id, role_id, resource_id),
    CONSTRAINT ck_iam_role_resource_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_iam_role_resource_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES iam_role (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_role_resource_resource FOREIGN KEY (resource_id)
        REFERENCES iam_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_role_resource_resource (resource_id, tenant_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户角色应用与功能资源授权';

CREATE TABLE iam_data_scope_policy (
    id             BINARY(16)      NOT NULL,
    tenant_id      BINARY(16)      NOT NULL,
    role_id        BINARY(16)      NOT NULL,
    application_id BINARY(16)      NOT NULL,
    scope_type     VARCHAR(32)     NOT NULL,
    scope_key      VARCHAR(160)    NOT NULL,
    scope_ref      BINARY(16)      NULL,
    status         VARCHAR(32)     NOT NULL,
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at     DATETIME(6)     NOT NULL,
    created_by     BINARY(16)      NULL,
    updated_at     DATETIME(6)     NOT NULL,
    updated_by     BINARY(16)      NULL,
    deleted_at     DATETIME(6)     NULL,
    deleted_by     BINARY(16)      NULL,
    delete_reason  VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_data_scope_policy PRIMARY KEY (id),
    CONSTRAINT uk_iam_data_scope_policy UNIQUE (tenant_id, role_id, application_id, scope_key),
    CONSTRAINT ck_iam_data_scope_type CHECK (scope_type IN ('SELF', 'MY_STORES', 'MY_CITY', 'MY_REGION', 'ALL')),
    CONSTRAINT ck_iam_data_scope_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_iam_data_scope_role FOREIGN KEY (tenant_id, role_id)
        REFERENCES iam_role (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_data_scope_application FOREIGN KEY (application_id)
        REFERENCES iam_application (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_data_scope_application (tenant_id, application_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='基于角色和应用的数据范围允许策略';
