-- 统一认证会话、Refresh Token轮换、IAM审计和Transactional Outbox。

CREATE TABLE iam_auth_session (
    id                      BINARY(16)      NOT NULL,
    principal_scope         VARCHAR(32)     NOT NULL,
    tenant_id               BINARY(16)      NULL,
    principal_id            BINARY(16)      NOT NULL,
    client_type             VARCHAR(32)     NOT NULL,
    device_name             VARCHAR(128)    NULL,
    client_fingerprint_hash BINARY(32)      NULL,
    user_agent_hash         BINARY(32)      NULL,
    ip_address              VARBINARY(16)   NULL,
    issued_at               DATETIME(6)     NOT NULL,
    last_seen_at            DATETIME(6)     NOT NULL,
    expires_at              DATETIME(6)     NOT NULL,
    revoked_at              DATETIME(6)     NULL,
    revoke_reason           VARCHAR(255)    NULL,
    status                  VARCHAR(32)     NOT NULL,
    version                 BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_iam_auth_session PRIMARY KEY (id),
    CONSTRAINT ck_iam_auth_session_scope CHECK (principal_scope IN ('PLATFORM', 'TENANT')),
    CONSTRAINT ck_iam_auth_session_tenant CHECK (
        (principal_scope = 'TENANT' AND tenant_id IS NOT NULL)
        OR (principal_scope = 'PLATFORM' AND tenant_id IS NULL)
    ),
    CONSTRAINT ck_iam_auth_session_client CHECK (client_type IN ('WEB', 'FEISHU_H5')),
    CONSTRAINT ck_iam_auth_session_status CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_iam_auth_session_period CHECK (
        expires_at > issued_at
        AND last_seen_at >= issued_at
        AND last_seen_at <= expires_at
        AND (revoked_at IS NULL OR revoked_at >= issued_at)
    ),
    CONSTRAINT ck_iam_auth_session_revoke CHECK (
        (status = 'REVOKED' AND revoked_at IS NOT NULL)
        OR (status IN ('ACTIVE', 'EXPIRED') AND revoked_at IS NULL)
    ),
    CONSTRAINT fk_iam_auth_session_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_auth_session_principal (principal_scope, tenant_id, principal_id, status, expires_at),
    INDEX idx_iam_auth_session_expiry (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台和租户主体共用的认证会话';

CREATE TABLE iam_refresh_token (
    id             BINARY(16)   NOT NULL,
    session_id     BINARY(16)   NOT NULL,
    token_hash     BINARY(32)   NOT NULL,
    issued_at      DATETIME(6)  NOT NULL,
    expires_at     DATETIME(6)  NOT NULL,
    consumed_at    DATETIME(6)  NULL,
    replaced_by_id BINARY(16)   NULL,
    revoked_at     DATETIME(6)  NULL,
    revoke_reason  VARCHAR(255) NULL,
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_iam_refresh_token PRIMARY KEY (id),
    CONSTRAINT uk_iam_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_iam_refresh_token_period CHECK (
        expires_at > issued_at
        AND (consumed_at IS NULL OR (consumed_at >= issued_at AND consumed_at <= expires_at))
        AND (revoked_at IS NULL OR revoked_at >= issued_at)
    ),
    CONSTRAINT ck_iam_refresh_token_replace CHECK (replaced_by_id IS NULL OR replaced_by_id <> id),
    CONSTRAINT ck_iam_refresh_token_rotation CHECK (
        (replaced_by_id IS NULL AND consumed_at IS NULL)
        OR (replaced_by_id IS NOT NULL AND consumed_at IS NOT NULL)
    ),
    CONSTRAINT fk_iam_refresh_token_session FOREIGN KEY (session_id)
        REFERENCES iam_auth_session (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_refresh_token_replaced_by FOREIGN KEY (replaced_by_id)
        REFERENCES iam_refresh_token (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_refresh_token_session (session_id, expires_at),
    INDEX idx_iam_refresh_token_expiry (expires_at, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='只保存哈希的Refresh Token轮换链';

CREATE TABLE iam_audit_log (
    id           BINARY(16)        NOT NULL,
    tenant_id    BINARY(16)        NULL,
    actor_scope  VARCHAR(32)       NOT NULL,
    actor_id     BINARY(16)        NULL,
    action       VARCHAR(128)      NOT NULL,
    target_type  VARCHAR(64)       NOT NULL,
    target_id    BINARY(16)        NULL,
    request_id   BINARY(16)        NOT NULL,
    event_seq    SMALLINT UNSIGNED NOT NULL,
    result       VARCHAR(32)       NOT NULL,
    before_json  JSON              NULL,
    after_json   JSON              NULL,
    ip_address   VARBINARY(16)     NULL,
    occurred_at  DATETIME(6)       NOT NULL,
    CONSTRAINT pk_iam_audit_log PRIMARY KEY (id),
    CONSTRAINT uk_iam_audit_request_event UNIQUE (request_id, event_seq),
    CONSTRAINT ck_iam_audit_actor_scope CHECK (actor_scope IN ('PLATFORM', 'TENANT', 'ANONYMOUS', 'SYSTEM')),
    CONSTRAINT ck_iam_audit_result CHECK (result IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT ck_iam_audit_event_seq CHECK (event_seq > 0),
    INDEX idx_iam_audit_tenant_time (tenant_id, occurred_at),
    INDEX idx_iam_audit_actor (actor_scope, actor_id, occurred_at),
    INDEX idx_iam_audit_target (target_type, target_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='追加写且脱敏的IAM管理审计';

CREATE TABLE iam_outbox_event (
    id                BINARY(16)      NOT NULL,
    tenant_id         BINARY(16)      NULL,
    event_type        VARCHAR(128)    NOT NULL,
    aggregate_type    VARCHAR(64)     NOT NULL,
    aggregate_id      BINARY(16)      NOT NULL,
    aggregate_version BIGINT UNSIGNED NOT NULL,
    payload_json      JSON            NOT NULL,
    headers_json      JSON            NULL,
    status            VARCHAR(32)     NOT NULL,
    attempts          INT UNSIGNED    NOT NULL DEFAULT 0,
    next_retry_at     DATETIME(6)     NULL,
    locked_by         VARCHAR(128)    NULL,
    locked_until      DATETIME(6)     NULL,
    occurred_at       DATETIME(6)     NOT NULL,
    published_at      DATETIME(6)     NULL,
    last_error        VARCHAR(1000)   NULL,
    created_at        DATETIME(6)     NOT NULL,
    CONSTRAINT pk_iam_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_iam_outbox_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_iam_outbox_lease CHECK (
        (status = 'PUBLISHING' AND locked_by IS NOT NULL AND locked_until IS NOT NULL)
        OR (status <> 'PUBLISHING' AND locked_by IS NULL AND locked_until IS NULL)
    ),
    CONSTRAINT ck_iam_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    INDEX idx_iam_outbox_dispatch (status, next_retry_at, locked_until, created_at),
    INDEX idx_iam_outbox_aggregate (aggregate_type, aggregate_id, aggregate_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='与IAM业务事实同事务写入的可靠事件表';
