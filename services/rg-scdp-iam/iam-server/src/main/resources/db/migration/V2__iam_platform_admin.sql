-- IAM平台管理员账号与密码凭据。平台账号不属于任何租户。

CREATE TABLE iam_platform_user (
    id               BINARY(16)      NOT NULL,
    username         VARCHAR(64)     NOT NULL,
    display_name     VARCHAR(128)    NOT NULL,
    platform_role    VARCHAR(32)     NOT NULL,
    status           VARCHAR(32)     NOT NULL,
    security_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at       DATETIME(6)     NOT NULL,
    created_by       BINARY(16)      NULL,
    updated_at       DATETIME(6)     NOT NULL,
    updated_by       BINARY(16)      NULL,
    deleted_at       DATETIME(6)     NULL,
    deleted_by       BINARY(16)      NULL,
    delete_reason    VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_platform_user PRIMARY KEY (id),
    CONSTRAINT uk_iam_platform_username UNIQUE (username),
    CONSTRAINT ck_iam_platform_role CHECK (platform_role IN ('SUPER_ADMIN', 'OPERATOR', 'AUDITOR')),
    CONSTRAINT ck_iam_platform_user_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    INDEX idx_iam_platform_user_status (status, platform_role, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='独立于租户的平台管理员';

CREATE TABLE iam_platform_user_credential (
    id                  BINARY(16)      NOT NULL,
    platform_user_id    BINARY(16)      NOT NULL,
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
    CONSTRAINT pk_iam_platform_credential PRIMARY KEY (id),
    CONSTRAINT uk_iam_platform_credential UNIQUE (platform_user_id, credential_type),
    CONSTRAINT ck_iam_platform_credential_type CHECK (credential_type IN ('PASSWORD')),
    CONSTRAINT ck_iam_platform_credential_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_platform_credential_user FOREIGN KEY (platform_user_id)
        REFERENCES iam_platform_user (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_platform_credential_lock (status, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台管理员认证凭据';
