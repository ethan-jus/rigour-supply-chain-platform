-- IAM租户身份：组织、用户、密码凭据、组织关系与外部身份。

CREATE TABLE iam_organization (
    id            BINARY(16)      NOT NULL,
    tenant_id     BINARY(16)      NOT NULL,
    parent_id     BINARY(16)      NULL,
    org_code      VARCHAR(64)     NOT NULL,
    org_name      VARCHAR(128)    NOT NULL,
    org_type      VARCHAR(32)     NOT NULL,
    path          VARCHAR(1024)   NOT NULL,
    sort_order    INT             NOT NULL DEFAULT 0,
    status        VARCHAR(32)     NOT NULL,
    version       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at    DATETIME(6)     NOT NULL,
    created_by    BINARY(16)      NULL,
    updated_at    DATETIME(6)     NOT NULL,
    updated_by    BINARY(16)      NULL,
    deleted_at    DATETIME(6)     NULL,
    deleted_by    BINARY(16)      NULL,
    delete_reason VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_organization PRIMARY KEY (id),
    CONSTRAINT uk_iam_organization_code UNIQUE (tenant_id, org_code),
    CONSTRAINT uk_iam_organization_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_organization_type CHECK (org_type IN ('COMPANY', 'REGION', 'CITY', 'DEPARTMENT', 'TEAM')),
    CONSTRAINT ck_iam_organization_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_iam_organization_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT fk_iam_organization_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_organization_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES iam_organization (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_organization_parent (tenant_id, parent_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户访问治理组织树';

CREATE TABLE iam_user (
    id                BINARY(16)      NOT NULL,
    tenant_id         BINARY(16)      NOT NULL,
    username          VARCHAR(64)     NOT NULL,
    display_name      VARCHAR(128)    NOT NULL,
    mobile_ciphertext VARBINARY(512)  NULL,
    email_ciphertext  VARBINARY(512)  NULL,
    status            VARCHAR(32)     NOT NULL,
    security_version  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version           BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at        DATETIME(6)     NOT NULL,
    created_by        BINARY(16)      NULL,
    updated_at        DATETIME(6)     NOT NULL,
    updated_by        BINARY(16)      NULL,
    deleted_at        DATETIME(6)     NULL,
    deleted_by        BINARY(16)      NULL,
    delete_reason     VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_user PRIMARY KEY (id),
    CONSTRAINT uk_iam_user_username UNIQUE (tenant_id, username),
    CONSTRAINT uk_iam_user_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT fk_iam_user_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_user_status (tenant_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户登录身份';

CREATE TABLE iam_user_credential (
    id                  BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    user_id             BINARY(16)      NOT NULL,
    credential_type     VARCHAR(32)     NOT NULL,
    password_hash       VARCHAR(255)    NOT NULL,
    algorithm           VARCHAR(32)     NOT NULL,
    algorithm_version   INT UNSIGNED    NOT NULL,
    failed_attempts     INT UNSIGNED    NOT NULL DEFAULT 0,
    last_failed_at      DATETIME(6)     NULL,
    locked_until        DATETIME(6)     NULL,
    password_changed_at DATETIME(6)     NOT NULL,
    status              VARCHAR(32)     NOT NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL,
    updated_at          DATETIME(6)     NOT NULL,
    CONSTRAINT pk_iam_user_credential PRIMARY KEY (id),
    CONSTRAINT uk_iam_user_credential UNIQUE (tenant_id, user_id, credential_type),
    CONSTRAINT ck_iam_user_credential_type CHECK (credential_type IN ('PASSWORD')),
    CONSTRAINT ck_iam_user_credential_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_user_credential_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES iam_user (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_user_credential_lock (tenant_id, status, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户用户认证凭据';

CREATE TABLE iam_user_organization (
    tenant_id      BINARY(16)  NOT NULL,
    user_id        BINARY(16)  NOT NULL,
    organization_id BINARY(16) NOT NULL,
    position_code  VARCHAR(64) NULL,
    is_primary     BOOLEAN     NOT NULL DEFAULT FALSE,
    status         VARCHAR(32) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to   DATETIME(6) NULL,
    created_at     DATETIME(6) NOT NULL,
    created_by     BINARY(16)  NULL,
    updated_at     DATETIME(6) NOT NULL,
    updated_by     BINARY(16)  NULL,
    CONSTRAINT pk_iam_user_organization PRIMARY KEY (tenant_id, user_id, organization_id),
    CONSTRAINT ck_iam_user_organization_primary CHECK (is_primary IN (0, 1)),
    CONSTRAINT ck_iam_user_organization_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_iam_user_organization_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT fk_iam_user_organization_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES iam_user (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_user_organization_org FOREIGN KEY (tenant_id, organization_id)
        REFERENCES iam_organization (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_user_organization_org (tenant_id, organization_id, status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户当前访问治理组织关系';

CREATE TABLE iam_external_identity (
    id                  BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    user_id             BINARY(16)      NOT NULL,
    provider            VARCHAR(32)     NOT NULL,
    external_tenant_key VARCHAR(128)    NOT NULL,
    external_user_id    VARCHAR(128)    NOT NULL,
    status              VARCHAR(32)     NOT NULL,
    bound_at            DATETIME(6)     NOT NULL,
    last_verified_at    DATETIME(6)     NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at          DATETIME(6)     NOT NULL,
    created_by          BINARY(16)      NULL,
    updated_at          DATETIME(6)     NOT NULL,
    updated_by          BINARY(16)      NULL,
    deleted_at          DATETIME(6)     NULL,
    deleted_by          BINARY(16)      NULL,
    delete_reason       VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_external_identity PRIMARY KEY (id),
    CONSTRAINT uk_iam_external_identity UNIQUE (provider, external_tenant_key, external_user_id),
    CONSTRAINT uk_iam_user_provider UNIQUE (tenant_id, user_id, provider),
    CONSTRAINT ck_iam_external_identity_status CHECK (status IN ('ACTIVE', 'UNBOUND', 'DISABLED')),
    CONSTRAINT fk_iam_external_identity_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES iam_user (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_external_identity_status (tenant_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书等外部身份到IAM用户的映射';
