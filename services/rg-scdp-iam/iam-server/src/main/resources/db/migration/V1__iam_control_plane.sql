-- IAM平台控制面：租户、套餐、订阅、应用与资源目录。
-- MySQL 8.0 / InnoDB / UTC。UUIDv7按自然字节序存入BINARY(16)，不得使用UUID_TO_BIN(uuid, 1)。

CREATE TABLE iam_tenant (
    id                       BINARY(16)      NOT NULL,
    tenant_code              VARCHAR(32)     NOT NULL,
    company_name             VARCHAR(128)    NOT NULL,
    contact_name             VARCHAR(64)     NULL,
    contact_phone_ciphertext VARBINARY(512)  NULL,
    domain                   VARCHAR(255)    NULL,
    license_number           VARCHAR(64)     NULL,
    address                  VARCHAR(255)    NULL,
    intro                    VARCHAR(500)    NULL,
    status                   VARCHAR(32)     NOT NULL,
    policy_version           BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version                  BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at               DATETIME(6)     NOT NULL,
    created_by               BINARY(16)      NULL,
    updated_at               DATETIME(6)     NOT NULL,
    updated_by               BINARY(16)      NULL,
    deleted_at               DATETIME(6)     NULL,
    deleted_by               BINARY(16)      NULL,
    delete_reason            VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_tenant PRIMARY KEY (id),
    CONSTRAINT uk_iam_tenant_code UNIQUE (tenant_code),
    CONSTRAINT uk_iam_tenant_domain UNIQUE (domain),
    CONSTRAINT ck_iam_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'EXPIRED', 'CLOSED')),
    INDEX idx_iam_tenant_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='IAM租户主档';

CREATE TABLE iam_tenant_package (
    id            BINARY(16)      NOT NULL,
    package_code  VARCHAR(32)     NOT NULL,
    package_name  VARCHAR(128)    NOT NULL,
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
    CONSTRAINT pk_iam_tenant_package PRIMARY KEY (id),
    CONSTRAINT uk_iam_tenant_package_code UNIQUE (package_code),
    CONSTRAINT uk_iam_tenant_package_name UNIQUE (package_name),
    CONSTRAINT ck_iam_tenant_package_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    INDEX idx_iam_tenant_package_status (status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户套餐主档';

CREATE TABLE iam_tenant_package_version (
    id                 BINARY(16)      NOT NULL,
    package_id         BINARY(16)      NOT NULL,
    version_no         INT UNSIGNED    NOT NULL,
    publish_status     VARCHAR(32)     NOT NULL,
    default_user_limit INT UNSIGNED    NOT NULL,
    limits_json        JSON            NULL,
    change_note        VARCHAR(500)    NULL,
    published_at       DATETIME(6)     NULL,
    published_by       BINARY(16)      NULL,
    version            BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at         DATETIME(6)     NOT NULL,
    created_by         BINARY(16)      NULL,
    updated_at         DATETIME(6)     NOT NULL,
    updated_by         BINARY(16)      NULL,
    deleted_at         DATETIME(6)     NULL,
    deleted_by         BINARY(16)      NULL,
    delete_reason      VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_tenant_package_version PRIMARY KEY (id),
    CONSTRAINT uk_iam_package_version UNIQUE (package_id, version_no),
    CONSTRAINT ck_iam_package_version_status CHECK (publish_status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_iam_package_version_limit CHECK (default_user_limit > 0),
    CONSTRAINT ck_iam_package_version_publish CHECK (
        (publish_status = 'DRAFT' AND published_at IS NULL AND published_by IS NULL)
        OR (publish_status IN ('PUBLISHED', 'RETIRED') AND published_at IS NOT NULL)
    ),
    CONSTRAINT fk_iam_package_version_package FOREIGN KEY (package_id)
        REFERENCES iam_tenant_package (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_package_version_publish (package_id, publish_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变发布的租户套餐版本';

CREATE TABLE iam_application (
    id             BINARY(16)      NOT NULL,
    app_code       VARCHAR(64)     NOT NULL,
    app_name       VARCHAR(128)    NOT NULL,
    app_scope      VARCHAR(32)     NOT NULL,
    app_type       VARCHAR(32)     NOT NULL,
    icon_key       VARCHAR(128)    NULL,
    sort_order     INT             NOT NULL DEFAULT 0,
    launch_mode    VARCHAR(32)     NOT NULL,
    target_uri     VARCHAR(1024)   NULL,
    credential_ref VARCHAR(255)    NULL,
    status         VARCHAR(32)     NOT NULL,
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at     DATETIME(6)     NOT NULL,
    created_by     BINARY(16)      NULL,
    updated_at     DATETIME(6)     NOT NULL,
    updated_by     BINARY(16)      NULL,
    deleted_at     DATETIME(6)     NULL,
    deleted_by     BINARY(16)      NULL,
    delete_reason  VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_application PRIMARY KEY (id),
    CONSTRAINT uk_iam_application_code UNIQUE (app_code),
    CONSTRAINT ck_iam_application_scope CHECK (app_scope IN ('PLATFORM', 'TENANT')),
    CONSTRAINT ck_iam_application_type CHECK (app_type IN ('INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_iam_application_launch CHECK (
        (app_type = 'INTERNAL' AND launch_mode = 'INTERNAL_ROUTE')
        OR (app_type = 'EXTERNAL' AND launch_mode IN ('EXTERNAL_URL', 'FEISHU_DEEPLINK', 'SSO_PROVIDER'))
    ),
    CONSTRAINT ck_iam_application_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_iam_application_active_target CHECK (status <> 'ACTIVE' OR target_uri IS NOT NULL),
    INDEX idx_iam_application_scope_status (app_scope, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一应用门户应用目录';

CREATE TABLE iam_resource (
    id              BINARY(16)      NOT NULL,
    application_id  BINARY(16)      NOT NULL,
    parent_id       BINARY(16)      NULL,
    resource_code   VARCHAR(128)    NOT NULL,
    resource_type   VARCHAR(32)     NOT NULL,
    permission_code VARCHAR(128)    NULL,
    display_name    VARCHAR(128)    NOT NULL,
    sort_order      INT             NOT NULL DEFAULT 0,
    status          VARCHAR(32)     NOT NULL,
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(6)     NOT NULL,
    created_by      BINARY(16)      NULL,
    updated_at      DATETIME(6)     NOT NULL,
    updated_by      BINARY(16)      NULL,
    deleted_at      DATETIME(6)     NULL,
    deleted_by      BINARY(16)      NULL,
    delete_reason   VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_resource PRIMARY KEY (id),
    CONSTRAINT uk_iam_resource_code UNIQUE (resource_code),
    CONSTRAINT uk_iam_resource_permission UNIQUE (permission_code),
    CONSTRAINT uk_iam_resource_app_id UNIQUE (application_id, id),
    CONSTRAINT ck_iam_resource_type CHECK (resource_type IN ('APPLICATION', 'MENU', 'PAGE', 'BUTTON', 'API')),
    CONSTRAINT ck_iam_resource_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_iam_resource_root CHECK (resource_type <> 'APPLICATION' OR parent_id IS NULL),
    CONSTRAINT fk_iam_resource_application FOREIGN KEY (application_id)
        REFERENCES iam_application (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_resource_parent FOREIGN KEY (application_id, parent_id)
        REFERENCES iam_resource (application_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_resource_parent (application_id, parent_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用、菜单、页面、按钮和API资源目录';

CREATE TABLE iam_package_resource (
    package_version_id BINARY(16)  NOT NULL,
    resource_id         BINARY(16)  NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    created_by          BINARY(16)  NULL,
    CONSTRAINT pk_iam_package_resource PRIMARY KEY (package_version_id, resource_id),
    CONSTRAINT fk_iam_package_resource_version FOREIGN KEY (package_version_id)
        REFERENCES iam_tenant_package_version (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_package_resource_resource FOREIGN KEY (resource_id)
        REFERENCES iam_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_package_resource_resource (resource_id, package_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='套餐版本允许使用的最大资源范围';

CREATE TABLE iam_tenant_subscription (
    id                 BINARY(16)      NOT NULL,
    tenant_id          BINARY(16)      NOT NULL,
    package_version_id BINARY(16)      NOT NULL,
    effective_from     DATETIME(6)     NOT NULL,
    effective_to       DATETIME(6)     NOT NULL,
    user_limit         INT UNSIGNED    NOT NULL,
    status             VARCHAR(32)     NOT NULL,
    termination_reason VARCHAR(255)    NULL,
    version            BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at         DATETIME(6)     NOT NULL,
    created_by         BINARY(16)      NULL,
    updated_at         DATETIME(6)     NOT NULL,
    updated_by         BINARY(16)      NULL,
    deleted_at         DATETIME(6)     NULL,
    deleted_by         BINARY(16)      NULL,
    delete_reason      VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_tenant_subscription PRIMARY KEY (id),
    CONSTRAINT ck_iam_subscription_status CHECK (status IN ('SCHEDULED', 'ACTIVE', 'EXPIRED', 'TERMINATED')),
    CONSTRAINT ck_iam_subscription_period CHECK (effective_to > effective_from),
    CONSTRAINT ck_iam_subscription_limit CHECK (user_limit > 0),
    CONSTRAINT fk_iam_subscription_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_subscription_version FOREIGN KEY (package_version_id)
        REFERENCES iam_tenant_package_version (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_subscription_tenant_time (tenant_id, effective_from, effective_to),
    INDEX idx_iam_subscription_status_expiry (status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户套餐订阅及历史';
