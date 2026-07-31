-- IAM OIDC授权服务器持久化。
-- 不保存Authorization Code、Access Token、ID Token或Refresh Token原文；私钥只保存受控引用。

CREATE TABLE iam_oauth_client (
    id                              BINARY(16)      NOT NULL,
    application_id                  BINARY(16)      NULL,
    client_id                       VARCHAR(100)    NOT NULL,
    client_id_issued_at             DATETIME(6)     NOT NULL,
    client_secret_hash              VARCHAR(255)    NULL,
    client_secret_expires_at        DATETIME(6)     NULL,
    client_name                     VARCHAR(200)    NOT NULL,
    client_type                     VARCHAR(16)     NOT NULL,
    require_pkce                    TINYINT UNSIGNED NOT NULL,
    require_consent                 TINYINT UNSIGNED NOT NULL,
    authorization_code_ttl_seconds  INT UNSIGNED    NOT NULL,
    access_token_ttl_seconds        INT UNSIGNED    NOT NULL,
    refresh_token_ttl_seconds       INT UNSIGNED    NOT NULL,
    reuse_refresh_tokens            TINYINT UNSIGNED NOT NULL DEFAULT 0,
    id_token_signature_algorithm    VARCHAR(16)     NOT NULL,
    status                          VARCHAR(16)     NOT NULL,
    version                         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at                      DATETIME(6)     NOT NULL,
    created_by                      BINARY(16)      NULL,
    updated_at                      DATETIME(6)     NOT NULL,
    updated_by                      BINARY(16)      NULL,
    CONSTRAINT pk_iam_oauth_client PRIMARY KEY (id),
    CONSTRAINT uk_iam_oauth_client_id UNIQUE (client_id),
    CONSTRAINT ck_iam_oauth_client_type CHECK (client_type IN ('PUBLIC', 'CONFIDENTIAL')),
    CONSTRAINT ck_iam_oauth_client_public CHECK (
        client_type <> 'PUBLIC'
        OR (client_secret_hash IS NULL AND client_secret_expires_at IS NULL AND require_pkce = 1)
    ),
    CONSTRAINT ck_iam_oauth_client_secret_expiry CHECK (
        client_secret_expires_at IS NULL OR client_secret_hash IS NOT NULL
    ),
    CONSTRAINT ck_iam_oauth_client_booleans CHECK (
        require_pkce IN (0, 1)
        AND require_consent IN (0, 1)
        AND reuse_refresh_tokens IN (0, 1)
    ),
    CONSTRAINT ck_iam_oauth_client_ttl CHECK (
        authorization_code_ttl_seconds > 0
        AND access_token_ttl_seconds > 0
        AND refresh_token_ttl_seconds > 0
    ),
    CONSTRAINT ck_iam_oauth_client_refresh_rotation CHECK (reuse_refresh_tokens = 0),
    CONSTRAINT ck_iam_oauth_client_id_token_alg CHECK (id_token_signature_algorithm = 'RS256'),
    CONSTRAINT ck_iam_oauth_client_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_oauth_client_application FOREIGN KEY (application_id)
        REFERENCES iam_application (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_oauth_client_application (application_id, status),
    INDEX idx_iam_oauth_client_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OIDC和OAuth技术客户端主档';

CREATE TABLE iam_oauth_client_auth_method (
    client_id    BINARY(16)  NOT NULL,
    auth_method  VARCHAR(64) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_iam_oauth_client_auth_method PRIMARY KEY (client_id, auth_method),
    CONSTRAINT ck_iam_oauth_client_auth_method CHECK (
        auth_method IN ('none', 'client_secret_basic', 'private_key_jwt')
    ),
    CONSTRAINT fk_iam_oauth_client_auth_method_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OAuth客户端认证方式';

CREATE TABLE iam_oauth_client_grant (
    client_id   BINARY(16)  NOT NULL,
    grant_type  VARCHAR(64) NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    CONSTRAINT pk_iam_oauth_client_grant PRIMARY KEY (client_id, grant_type),
    CONSTRAINT ck_iam_oauth_client_grant CHECK (
        grant_type IN ('authorization_code', 'refresh_token', 'client_credentials')
    ),
    CONSTRAINT fk_iam_oauth_client_grant_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OAuth客户端允许的Grant Type';

CREATE TABLE iam_oauth_client_redirect_uri (
    id          BINARY(16)    NOT NULL,
    client_id   BINARY(16)    NOT NULL,
    uri_type    VARCHAR(32)   NOT NULL,
    uri         VARCHAR(1024) NOT NULL,
    uri_hash    BINARY(32)    NOT NULL,
    status      VARCHAR(16)   NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    created_by  BINARY(16)    NULL,
    CONSTRAINT pk_iam_oauth_client_redirect_uri PRIMARY KEY (id),
    CONSTRAINT uk_iam_oauth_client_redirect_uri UNIQUE (client_id, uri_type, uri_hash),
    CONSTRAINT ck_iam_oauth_client_redirect_uri_type CHECK (
        uri_type IN ('LOGIN_REDIRECT', 'POST_LOGOUT_REDIRECT')
    ),
    CONSTRAINT ck_iam_oauth_client_redirect_uri_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_oauth_client_redirect_uri_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_oauth_client_redirect_uri_status (client_id, uri_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OAuth登录和退出回调精确白名单';

CREATE TABLE iam_oauth_client_scope (
    client_id   BINARY(16)   NOT NULL,
    scope_code  VARCHAR(128) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_iam_oauth_client_scope PRIMARY KEY (client_id, scope_code),
    CONSTRAINT fk_iam_oauth_client_scope_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OAuth客户端协议Scope';

CREATE TABLE iam_oauth_authorization (
    id                           BINARY(16)      NOT NULL,
    client_id                    BINARY(16)      NOT NULL,
    session_id                   BINARY(16)      NOT NULL,
    principal_name               VARCHAR(200)    NOT NULL,
    grant_type                   VARCHAR(64)     NOT NULL,
    authorized_scopes_json       JSON            NOT NULL,
    state_hash                   BINARY(32)      NULL,
    attributes_ciphertext        LONGBLOB        NULL,
    attributes_key_version       VARCHAR(64)     NULL,
    authorization_code_hash      BINARY(32)      NULL,
    code_issued_at               DATETIME(6)     NULL,
    code_expires_at              DATETIME(6)     NULL,
    code_consumed_at             DATETIME(6)     NULL,
    status                       VARCHAR(32)     NOT NULL,
    revoked_at                   DATETIME(6)     NULL,
    revoke_reason                VARCHAR(255)    NULL,
    version                      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at                   DATETIME(6)     NOT NULL,
    updated_at                   DATETIME(6)     NOT NULL,
    CONSTRAINT pk_iam_oauth_authorization PRIMARY KEY (id),
    CONSTRAINT uk_iam_oauth_authorization_code_hash UNIQUE (authorization_code_hash),
    CONSTRAINT uk_iam_oauth_authorization_state_hash UNIQUE (client_id, state_hash),
    CONSTRAINT ck_iam_oauth_authorization_grant CHECK (grant_type = 'authorization_code'),
    CONSTRAINT ck_iam_oauth_authorization_attributes CHECK (
        (attributes_ciphertext IS NULL AND attributes_key_version IS NULL)
        OR (attributes_ciphertext IS NOT NULL AND attributes_key_version IS NOT NULL)
    ),
    CONSTRAINT ck_iam_oauth_authorization_code CHECK (
        (authorization_code_hash IS NULL
            AND code_issued_at IS NULL
            AND code_expires_at IS NULL
            AND code_consumed_at IS NULL)
        OR (authorization_code_hash IS NOT NULL
            AND code_issued_at IS NOT NULL
            AND code_expires_at > code_issued_at
            AND (code_consumed_at IS NULL
                OR (code_consumed_at >= code_issued_at AND code_consumed_at <= code_expires_at)))
    ),
    CONSTRAINT ck_iam_oauth_authorization_status CHECK (
        status IN ('PENDING', 'CODE_ISSUED', 'ACTIVE', 'REVOKED', 'EXPIRED')
    ),
    CONSTRAINT ck_iam_oauth_authorization_revoke CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status <> 'REVOKED' AND revoked_at IS NULL)
    ),
    CONSTRAINT fk_iam_oauth_authorization_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_oauth_authorization_session FOREIGN KEY (session_id)
        REFERENCES iam_auth_session (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_oauth_authorization_session (session_id, status, updated_at),
    INDEX idx_iam_oauth_authorization_client_principal (client_id, principal_name, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不保存原始Token的OAuth授权上下文';

CREATE TABLE iam_oauth_consent (
    client_id        BINARY(16)      NOT NULL,
    principal_name   VARCHAR(200)    NOT NULL,
    authorities_json JSON            NOT NULL,
    consent_source   VARCHAR(32)     NOT NULL,
    granted_at       DATETIME(6)     NOT NULL,
    granted_by       BINARY(16)      NULL,
    revoked_at       DATETIME(6)     NULL,
    version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_iam_oauth_consent PRIMARY KEY (client_id, principal_name),
    CONSTRAINT ck_iam_oauth_consent_source CHECK (consent_source IN ('USER', 'ADMIN_PREAUTHORIZED')),
    CONSTRAINT fk_iam_oauth_consent_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_oauth_consent_active (principal_name, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='OAuth用户同意或管理员预授权';

CREATE TABLE iam_signing_key (
    id                 BINARY(16)    NOT NULL,
    kid                VARCHAR(100)  NOT NULL,
    algorithm          VARCHAR(16)   NOT NULL,
    key_use            VARCHAR(16)   NOT NULL,
    public_jwk_json    JSON          NOT NULL,
    private_key_ref    VARCHAR(255)  NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    not_before         DATETIME(6)   NOT NULL,
    not_after          DATETIME(6)   NOT NULL,
    activated_at       DATETIME(6)   NULL,
    retired_at         DATETIME(6)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    created_by         BINARY(16)    NULL,
    CONSTRAINT pk_iam_signing_key PRIMARY KEY (id),
    CONSTRAINT uk_iam_signing_key_kid UNIQUE (kid),
    CONSTRAINT ck_iam_signing_key_algorithm CHECK (algorithm = 'RS256'),
    CONSTRAINT ck_iam_signing_key_use CHECK (key_use = 'sig'),
    CONSTRAINT ck_iam_signing_key_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'VERIFY_ONLY', 'RETIRED', 'REVOKED')
    ),
    CONSTRAINT ck_iam_signing_key_period CHECK (not_after > not_before),
    CONSTRAINT ck_iam_signing_key_activation CHECK (
        (status = 'PENDING' AND activated_at IS NULL)
        OR (status <> 'PENDING' AND activated_at IS NOT NULL)
    ),
    CONSTRAINT ck_iam_signing_key_retirement CHECK (
        (status IN ('RETIRED', 'REVOKED') AND retired_at IS NOT NULL)
        OR (status NOT IN ('RETIRED', 'REVOKED') AND retired_at IS NULL)
    ),
    INDEX idx_iam_signing_key_status_period (status, not_before, not_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只保存公钥和私钥引用的签名密钥元数据';

ALTER TABLE iam_refresh_token
    ADD COLUMN authorization_id BINARY(16) NULL AFTER session_id,
    ADD CONSTRAINT fk_iam_refresh_token_authorization FOREIGN KEY (authorization_id)
        REFERENCES iam_oauth_authorization (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    ADD INDEX idx_iam_refresh_token_authorization (authorization_id, expires_at);

ALTER TABLE iam_application
    DROP CHECK ck_iam_application_launch,
    ADD CONSTRAINT ck_iam_application_launch CHECK (
        (app_type = 'INTERNAL' AND launch_mode IN ('INTERNAL_ROUTE', 'OIDC_CLIENT'))
        OR (app_type = 'EXTERNAL' AND launch_mode IN ('EXTERNAL_URL', 'FEISHU_DEEPLINK', 'SSO_PROVIDER'))
    );
