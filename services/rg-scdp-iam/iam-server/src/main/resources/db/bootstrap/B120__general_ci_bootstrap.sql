-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__iam_control_plane.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='IAM租户主档';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户套餐主档';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='不可变发布的租户套餐版本';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='统一应用门户应用目录';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='应用、菜单、页面、按钮和API资源目录';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='套餐版本允许使用的最大资源范围';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户套餐订阅及历史';

-- Source: V2__iam_platform_admin.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='独立于租户的平台管理员';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台管理员认证凭据';

-- Source: V3__iam_tenant_identity.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户访问治理组织树';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户登录身份';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户用户认证凭据';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户当前访问治理组织关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='飞书等外部身份到IAM用户的映射';

-- Source: V4__iam_authorization.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户角色';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户当前角色关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户角色应用与功能资源授权';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='基于角色和应用的数据范围允许策略';

-- Source: V5__iam_session_audit_outbox.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台和租户主体共用的认证会话';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='只保存哈希的Refresh Token轮换链';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='追加写且脱敏的IAM管理审计';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='与IAM业务事实同事务写入的可靠事件表';

-- Source: V6__iam_seed_catalog.sql
-- IAM一期应用、资源与标准套餐种子。
-- 固定UUIDv7是跨环境稳定标识；已执行后只能通过新迁移修改，不得改写本文件。

SET @seed_at = TIMESTAMP('2026-07-30 00:00:00.000000');

SET @app_platform_admin = UUID_TO_BIN('019facf1-0000-7000-8000-000000000001');
SET @app_system_admin   = UUID_TO_BIN('019facf1-0000-7000-8000-000000000002');
SET @app_supply_chain   = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_dinghuobao     = UUID_TO_BIN('019facf1-0000-7000-8000-000000000004');
SET @app_feishu_sales   = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');

INSERT INTO iam_application (
    id, app_code, app_name, app_scope, app_type, icon_key, sort_order,
    launch_mode, target_uri, credential_ref, status, created_at, updated_at
) VALUES
    (@app_platform_admin, 'PLATFORM_ADMIN', '平台管理中心', 'PLATFORM', 'INTERNAL', 'app-platform-admin', 10,
     'INTERNAL_ROUTE', '/admin', NULL, 'ACTIVE', @seed_at, @seed_at),
    (@app_system_admin, 'SYSTEM_ADMIN', '系统管理', 'TENANT', 'INTERNAL', 'app-system-admin', 20,
     'INTERNAL_ROUTE', '/admin', NULL, 'ACTIVE', @seed_at, @seed_at),
    (@app_supply_chain, 'SUPPLY_CHAIN', '供应链系统', 'TENANT', 'INTERNAL', 'app-supply-chain', 30,
     'INTERNAL_ROUTE', '/supply-chain', NULL, 'ACTIVE', @seed_at, @seed_at),
    (@app_dinghuobao, 'DINGHUOBAO', '订货宝', 'TENANT', 'EXTERNAL', 'app-dinghuobao', 40,
     'SSO_PROVIDER', NULL, NULL, 'DISABLED', @seed_at, @seed_at),
    (@app_feishu_sales, 'FEISHU_SALES', '飞书销售工作台', 'TENANT', 'EXTERNAL', 'app-feishu-sales', 50,
     'FEISHU_DEEPLINK', NULL, NULL, 'DISABLED', @seed_at, @seed_at);

-- PLATFORM_ADMIN：23个资源。
SET @r001 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000001');
SET @r002 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000002');
SET @r003 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000003');
SET @r004 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000004');
SET @r005 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000005');
SET @r006 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000006');
SET @r007 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000007');
SET @r008 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000008');
SET @r009 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000009');
SET @r010 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000010');
SET @r011 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000011');
SET @r012 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000012');
SET @r013 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000013');
SET @r014 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000014');
SET @r015 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000015');
SET @r016 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000016');
SET @r017 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000017');
SET @r018 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000018');
SET @r019 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000019');
SET @r020 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000020');
SET @r021 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000021');
SET @r022 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000022');
SET @r023 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000023');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r001, @app_platform_admin, NULL,  'PLATFORM_ADMIN.ROOT', 'APPLICATION', NULL, '平台管理中心', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r002, @app_platform_admin, @r001, 'PLATFORM_ADMIN.PAGE.DASHBOARD', 'PAGE', NULL, '平台管理首页', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r003, @app_platform_admin, @r001, 'PLATFORM_ADMIN.MENU.TENANT', 'MENU', NULL, '租户管理', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r004, @app_platform_admin, @r003, 'PLATFORM_ADMIN.PAGE.TENANT_LIST', 'PAGE', NULL, '租户列表', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r005, @app_platform_admin, @r004, 'PLATFORM_ADMIN.API.TENANT_READ', 'API', 'platform:tenant:read', '查询租户', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r006, @app_platform_admin, @r004, 'PLATFORM_ADMIN.API.TENANT_WRITE', 'API', 'platform:tenant:write', '维护租户', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r007, @app_platform_admin, @r004, 'PLATFORM_ADMIN.API.TENANT_SUBSCRIBE', 'API', 'platform:tenant:subscribe', '设置租户订阅', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r008, @app_platform_admin, @r001, 'PLATFORM_ADMIN.MENU.PACKAGE', 'MENU', NULL, '租户套餐管理', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r009, @app_platform_admin, @r008, 'PLATFORM_ADMIN.PAGE.PACKAGE_LIST', 'PAGE', NULL, '套餐和版本列表', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r010, @app_platform_admin, @r009, 'PLATFORM_ADMIN.API.PACKAGE_READ', 'API', 'platform:package:read', '查询套餐', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r011, @app_platform_admin, @r009, 'PLATFORM_ADMIN.API.PACKAGE_WRITE', 'API', 'platform:package:write', '维护套餐草稿', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r012, @app_platform_admin, @r009, 'PLATFORM_ADMIN.API.PACKAGE_PUBLISH', 'API', 'platform:package:publish', '发布套餐版本', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r013, @app_platform_admin, @r001, 'PLATFORM_ADMIN.MENU.APPLICATION', 'MENU', NULL, '应用目录', 40, 'ACTIVE', @seed_at, @seed_at),
    (@r014, @app_platform_admin, @r013, 'PLATFORM_ADMIN.PAGE.APPLICATION_LIST', 'PAGE', NULL, '应用和启动配置', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r015, @app_platform_admin, @r014, 'PLATFORM_ADMIN.API.APPLICATION_READ', 'API', 'platform:application:read', '查询应用目录', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r016, @app_platform_admin, @r014, 'PLATFORM_ADMIN.API.APPLICATION_WRITE', 'API', 'platform:application:write', '维护应用目录', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r017, @app_platform_admin, @r001, 'PLATFORM_ADMIN.MENU.RESOURCE', 'MENU', NULL, '资源目录', 50, 'ACTIVE', @seed_at, @seed_at),
    (@r018, @app_platform_admin, @r017, 'PLATFORM_ADMIN.PAGE.RESOURCE_LIST', 'PAGE', NULL, '菜单页面和API资源', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r019, @app_platform_admin, @r018, 'PLATFORM_ADMIN.API.RESOURCE_READ', 'API', 'platform:resource:read', '查询资源树', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r020, @app_platform_admin, @r018, 'PLATFORM_ADMIN.API.RESOURCE_WRITE', 'API', 'platform:resource:write', '维护资源目录', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r021, @app_platform_admin, @r001, 'PLATFORM_ADMIN.MENU.AUDIT', 'MENU', NULL, '平台IAM审计', 60, 'ACTIVE', @seed_at, @seed_at),
    (@r022, @app_platform_admin, @r021, 'PLATFORM_ADMIN.PAGE.AUDIT_LIST', 'PAGE', NULL, '平台审计日志', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r023, @app_platform_admin, @r022, 'PLATFORM_ADMIN.API.AUDIT_READ', 'API', 'platform:audit:read', '查询平台审计', 10, 'ACTIVE', @seed_at, @seed_at);

-- SYSTEM_ADMIN：25个资源。
SET @r024 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000024');
SET @r025 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000025');
SET @r026 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000026');
SET @r027 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000027');
SET @r028 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000028');
SET @r029 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000029');
SET @r030 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000030');
SET @r031 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000031');
SET @r032 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000032');
SET @r033 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000033');
SET @r034 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000034');
SET @r035 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000035');
SET @r036 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000036');
SET @r037 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000037');
SET @r038 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000038');
SET @r039 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000039');
SET @r040 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000040');
SET @r041 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000041');
SET @r042 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000042');
SET @r043 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000043');
SET @r044 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000044');
SET @r045 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000045');
SET @r046 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000046');
SET @r047 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000047');
SET @r048 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000048');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r024, @app_system_admin, NULL,  'SYSTEM_ADMIN.ROOT', 'APPLICATION', NULL, '系统管理', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r025, @app_system_admin, @r024, 'SYSTEM_ADMIN.PAGE.DASHBOARD', 'PAGE', NULL, '系统管理首页', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r026, @app_system_admin, @r024, 'SYSTEM_ADMIN.MENU.ORGANIZATION', 'MENU', NULL, '组织管理', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r027, @app_system_admin, @r026, 'SYSTEM_ADMIN.PAGE.ORGANIZATION_LIST', 'PAGE', NULL, '组织树和详情', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r028, @app_system_admin, @r027, 'SYSTEM_ADMIN.API.ORGANIZATION_READ', 'API', 'iam:organization:read', '查询组织', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r029, @app_system_admin, @r027, 'SYSTEM_ADMIN.API.ORGANIZATION_WRITE', 'API', 'iam:organization:write', '维护组织', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r030, @app_system_admin, @r024, 'SYSTEM_ADMIN.MENU.USER', 'MENU', NULL, '用户管理', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r031, @app_system_admin, @r030, 'SYSTEM_ADMIN.PAGE.USER_LIST', 'PAGE', NULL, '用户组织和外部身份', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r032, @app_system_admin, @r031, 'SYSTEM_ADMIN.API.USER_READ', 'API', 'iam:user:read', '查询用户', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r033, @app_system_admin, @r031, 'SYSTEM_ADMIN.API.USER_WRITE', 'API', 'iam:user:write', '维护用户', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r034, @app_system_admin, @r031, 'SYSTEM_ADMIN.API.USER_ASSIGN_ROLE', 'API', 'iam:user:assign-role', '分配用户角色', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r035, @app_system_admin, @r031, 'SYSTEM_ADMIN.API.USER_BIND_EXTERNAL_IDENTITY', 'API', 'iam:user:bind-external-identity', '绑定外部身份', 40, 'ACTIVE', @seed_at, @seed_at),
    (@r036, @app_system_admin, @r031, 'SYSTEM_ADMIN.API.USER_RESET_PASSWORD', 'API', 'iam:user:reset-password', '重置用户密码', 50, 'ACTIVE', @seed_at, @seed_at),
    (@r037, @app_system_admin, @r024, 'SYSTEM_ADMIN.MENU.ROLE', 'MENU', NULL, '角色与权限', 40, 'ACTIVE', @seed_at, @seed_at),
    (@r038, @app_system_admin, @r037, 'SYSTEM_ADMIN.PAGE.ROLE_LIST', 'PAGE', NULL, '角色管理', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r039, @app_system_admin, @r038, 'SYSTEM_ADMIN.API.ROLE_READ', 'API', 'iam:role:read', '查询角色', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r040, @app_system_admin, @r038, 'SYSTEM_ADMIN.API.ROLE_WRITE', 'API', 'iam:role:write', '维护角色', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r041, @app_system_admin, @r038, 'SYSTEM_ADMIN.API.ROLE_GRANT', 'API', 'iam:role:grant', '分配角色资源', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r042, @app_system_admin, @r024, 'SYSTEM_ADMIN.MENU.DATA_SCOPE', 'MENU', NULL, '数据范围', 50, 'ACTIVE', @seed_at, @seed_at),
    (@r043, @app_system_admin, @r042, 'SYSTEM_ADMIN.PAGE.DATA_SCOPE_LIST', 'PAGE', NULL, '角色DataScope', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r044, @app_system_admin, @r043, 'SYSTEM_ADMIN.API.DATA_SCOPE_READ', 'API', 'iam:data-scope:read', '查询DataScope', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r045, @app_system_admin, @r043, 'SYSTEM_ADMIN.API.DATA_SCOPE_WRITE', 'API', 'iam:data-scope:write', '维护DataScope', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r046, @app_system_admin, @r024, 'SYSTEM_ADMIN.MENU.AUDIT', 'MENU', NULL, '本租户IAM审计', 60, 'ACTIVE', @seed_at, @seed_at),
    (@r047, @app_system_admin, @r046, 'SYSTEM_ADMIN.PAGE.AUDIT_LIST', 'PAGE', NULL, '本租户审计日志', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r048, @app_system_admin, @r047, 'SYSTEM_ADMIN.API.AUDIT_READ', 'API', 'iam:audit:read', '查询租户审计', 10, 'ACTIVE', @seed_at, @seed_at);

-- SUPPLY_CHAIN现有页面骨架：20个资源；业务API权限在对应领域契约冻结后追加。
SET @r049 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @r050 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000050');
SET @r051 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000051');
SET @r052 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000052');
SET @r053 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @r054 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000054');
SET @r055 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @r056 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @r057 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000057');
SET @r058 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000058');
SET @r059 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @r060 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000060');
SET @r061 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @r062 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @r063 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000063');
SET @r064 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000064');
SET @r065 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @r066 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @r067 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000067');
SET @r068 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000068');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r049, @app_supply_chain, NULL,  'SUPPLY_CHAIN.ROOT', 'APPLICATION', NULL, '供应链系统', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r050, @app_supply_chain, @r049, 'SUPPLY_CHAIN.PAGE.DASHBOARD', 'PAGE', NULL, '供应链首页', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r051, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.CITY', 'MENU', NULL, '城市运营', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r052, @app_supply_chain, @r051, 'SUPPLY_CHAIN.PAGE.CITY_INDEX', 'PAGE', NULL, '城市运营入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r053, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.CRM', 'MENU', NULL, 'CRM', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r054, @app_supply_chain, @r053, 'SUPPLY_CHAIN.PAGE.CRM_INDEX', 'PAGE', NULL, '商家与门店入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r055, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.ORDER', 'MENU', NULL, '订单', 40, 'ACTIVE', @seed_at, @seed_at),
    (@r056, @app_supply_chain, @r055, 'SUPPLY_CHAIN.PAGE.ORDER_INDEX', 'PAGE', NULL, '订单入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r057, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.SALES', 'MENU', NULL, '销售监管', 50, 'ACTIVE', @seed_at, @seed_at),
    (@r058, @app_supply_chain, @r057, 'SUPPLY_CHAIN.PAGE.SALES_INDEX', 'PAGE', NULL, '销售监管入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r059, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.ERP', 'MENU', NULL, 'ERP', 60, 'ACTIVE', @seed_at, @seed_at),
    (@r060, @app_supply_chain, @r059, 'SUPPLY_CHAIN.PAGE.ERP_INDEX', 'PAGE', NULL, 'ERP入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r061, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.HR', 'MENU', NULL, '人事', 70, 'ACTIVE', @seed_at, @seed_at),
    (@r062, @app_supply_chain, @r061, 'SUPPLY_CHAIN.PAGE.HR_INDEX', 'PAGE', NULL, '人事入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r063, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.CHANNEL', 'MENU', NULL, '渠道代理', 80, 'ACTIVE', @seed_at, @seed_at),
    (@r064, @app_supply_chain, @r063, 'SUPPLY_CHAIN.PAGE.CHANNEL_INDEX', 'PAGE', NULL, '渠道代理入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r065, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.BI', 'MENU', NULL, 'BI', 90, 'ACTIVE', @seed_at, @seed_at),
    (@r066, @app_supply_chain, @r065, 'SUPPLY_CHAIN.PAGE.BI_INDEX', 'PAGE', NULL, 'BI入口', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r067, @app_supply_chain, @r049, 'SUPPLY_CHAIN.MENU.SETTINGS', 'MENU', NULL, '业务设置', 100, 'ACTIVE', @seed_at, @seed_at),
    (@r068, @app_supply_chain, @r067, 'SUPPLY_CHAIN.PAGE.SETTINGS_INDEX', 'PAGE', NULL, '领域配置聚合入口', 10, 'ACTIVE', @seed_at, @seed_at);

-- 外部应用只初始化根资源，应用目标未配置前保持DISABLED。
SET @r069 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000069');
SET @r070 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r069, @app_dinghuobao, NULL, 'DINGHUOBAO.ROOT', 'APPLICATION', NULL, '订货宝', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r070, @app_feishu_sales, NULL, 'FEISHU_SALES.ROOT', 'APPLICATION', NULL, '飞书销售工作台', 10, 'ACTIVE', @seed_at, @seed_at);

SET @package_standard = UUID_TO_BIN('019facf3-0000-7000-8000-000000000001');
SET @package_standard_v1 = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

INSERT INTO iam_tenant_package (
    id, package_code, package_name, description, status, created_at, updated_at
) VALUES (
    @package_standard, 'STANDARD', '一期标准版', '一期统一门户、系统管理、供应链及已配置外部应用',
    'ACTIVE', @seed_at, @seed_at
);

INSERT INTO iam_tenant_package_version (
    id, package_id, version_no, publish_status, default_user_limit,
    limits_json, change_note, published_at, published_by, created_at, updated_at
) VALUES (
    @package_standard_v1, @package_standard, 1, 'PUBLISHED', 100,
    NULL, '一期首批应用与权限基线', @seed_at, NULL, @seed_at, @seed_at
);

-- 标准套餐包含SYSTEM_ADMIN 25项、SUPPLY_CHAIN 20项和两个外部应用根资源，共47项。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @package_standard_v1, resource.id, @seed_at, NULL
FROM iam_resource AS resource
WHERE resource.id IN (
    @r024, @r025, @r026, @r027, @r028, @r029, @r030, @r031, @r032, @r033,
    @r034, @r035, @r036, @r037, @r038, @r039, @r040, @r041, @r042, @r043,
    @r044, @r045, @r046, @r047, @r048,
    @r049, @r050, @r051, @r052, @r053, @r054, @r055, @r056, @r057, @r058,
    @r059, @r060, @r061, @r062, @r063, @r064, @r065, @r066, @r067, @r068,
    @r069, @r070
);

-- Source: V7__iam_oidc_authorization_server.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OIDC和OAuth技术客户端主档';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OAuth客户端认证方式';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OAuth客户端允许的Grant Type';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OAuth登录和退出回调精确白名单';

CREATE TABLE iam_oauth_client_scope (
    client_id   BINARY(16)   NOT NULL,
    scope_code  VARCHAR(128) NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_iam_oauth_client_scope PRIMARY KEY (client_id, scope_code),
    CONSTRAINT fk_iam_oauth_client_scope_client FOREIGN KEY (client_id)
        REFERENCES iam_oauth_client (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OAuth客户端协议Scope';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='不保存原始Token的OAuth授权上下文';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='OAuth用户同意或管理员预授权';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='只保存公钥和私钥引用的签名密钥元数据';

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

-- Source: V8__iam_management_console.sql
-- IAM 管理控制台：资源授权事实与前端导航展示元数据分离。
-- route_key 只能映射到前端已编译的受控组件，数据库不得保存组件文件路径或脚本。

ALTER TABLE iam_resource
    DROP INDEX uk_iam_resource_permission,
    ADD INDEX idx_iam_resource_permission (permission_code, status);

CREATE TABLE iam_resource_ui (
    resource_id BINARY(16)      NOT NULL,
    route_key   VARCHAR(128)    NOT NULL,
    route_path  VARCHAR(255)    NULL,
    icon_key    VARCHAR(128)    NULL,
    visible     TINYINT UNSIGNED NOT NULL DEFAULT 1,
    keep_alive  TINYINT UNSIGNED NOT NULL DEFAULT 0,
    version     BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at  DATETIME(6)     NOT NULL,
    updated_at  DATETIME(6)     NOT NULL,
    CONSTRAINT pk_iam_resource_ui PRIMARY KEY (resource_id),
    CONSTRAINT uk_iam_resource_ui_route_key UNIQUE (route_key),
    CONSTRAINT ck_iam_resource_ui_booleans CHECK (visible IN (0, 1) AND keep_alive IN (0, 1)),
    CONSTRAINT ck_iam_resource_ui_route_path CHECK (
        route_path IS NULL OR (route_path LIKE '/%' AND route_path NOT LIKE '//%')
    ),
    CONSTRAINT fk_iam_resource_ui_resource FOREIGN KEY (resource_id)
        REFERENCES iam_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_resource_ui_path (route_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='菜单页面受控导航元数据';

CREATE TABLE iam_tenant_setting (
    tenant_id    BINARY(16)      NOT NULL,
    setting_key  VARCHAR(128)    NOT NULL,
    value_json   JSON            NOT NULL,
    version      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at   DATETIME(6)     NOT NULL,
    created_by   BINARY(16)      NULL,
    updated_at   DATETIME(6)     NOT NULL,
    updated_by   BINARY(16)      NULL,
    CONSTRAINT pk_iam_tenant_setting PRIMARY KEY (tenant_id, setting_key),
    CONSTRAINT fk_iam_tenant_setting_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户IAM与门户非敏感设置';

-- 平台管理与租户系统管理必须使用不同路由边界，不能共用含糊的 /admin。
UPDATE iam_application
   SET target_uri = '/platform-admin', updated_at = UTC_TIMESTAMP(6), version = version + 1
 WHERE app_code = 'PLATFORM_ADMIN';
UPDATE iam_application
   SET target_uri = '/system-admin', updated_at = UTC_TIMESTAMP(6), version = version + 1
 WHERE app_code = 'SYSTEM_ADMIN';

SET @seed_at = TIMESTAMP('2026-07-31 00:00:00.000000');
SET @app_system_admin = UUID_TO_BIN('019facf1-0000-7000-8000-000000000002');
SET @r024 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000024');
SET @r071 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000071');
SET @r072 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000072');
SET @r073 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000073');
SET @r074 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000074');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r071, @app_system_admin, @r024, 'SYSTEM_ADMIN.MENU.SETTING', 'MENU', NULL,
     '系统设置', 55, 'ACTIVE', @seed_at, @seed_at),
    (@r072, @app_system_admin, @r071, 'SYSTEM_ADMIN.PAGE.SETTING_LIST', 'PAGE', NULL,
     '系统设置', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r073, @app_system_admin, @r072, 'SYSTEM_ADMIN.API.SETTING_READ', 'API', 'iam:setting:read',
     '查询系统设置', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r074, @app_system_admin, @r072, 'SYSTEM_ADMIN.API.SETTING_WRITE', 'API', 'iam:setting:write',
     '维护系统设置', 20, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @r071 AS resource_id UNION ALL SELECT @r072 UNION ALL SELECT @r073 UNION ALL SELECT @r074
) added_resources;

-- 平台管理导航。
INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000002'), 'platform.dashboard', '/platform-admin', 'Odometer', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000003'), 'platform.tenant.menu', NULL, 'OfficeBuilding', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000004'), 'platform.tenant.list', '/platform-admin/tenants', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000008'), 'platform.package.menu', NULL, 'Box', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000009'), 'platform.package.list', '/platform-admin/packages', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000013'), 'platform.application.menu', NULL, 'Grid', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000014'), 'platform.application.list', '/platform-admin/applications', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000017'), 'platform.resource.menu', NULL, 'Menu', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000018'), 'platform.resource.list', '/platform-admin/resources', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000021'), 'platform.audit.menu', NULL, 'Document', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000022'), 'platform.audit.list', '/platform-admin/audit', NULL, 1, 0, @seed_at, @seed_at);

-- 租户系统管理导航。
INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000025'), 'system.dashboard', '/system-admin', 'Odometer', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000026'), 'system.organization.menu', NULL, 'Share', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000027'), 'system.organization.list', '/system-admin/organizations', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000030'), 'system.user.menu', NULL, 'User', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000031'), 'system.user.list', '/system-admin/users', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000037'), 'system.role.menu', NULL, 'Key', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000038'), 'system.role.list', '/system-admin/roles', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000042'), 'system.data-scope.menu', NULL, 'Filter', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000043'), 'system.data-scope.list', '/system-admin/data-scopes', NULL, 1, 0, @seed_at, @seed_at),
    (@r071, 'system.setting.menu', NULL, 'Setting', 1, 0, @seed_at, @seed_at),
    (@r072, 'system.setting.list', '/system-admin/settings', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000046'), 'system.audit.menu', NULL, 'Document', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000047'), 'system.audit.list', '/system-admin/audit', NULL, 1, 0, @seed_at, @seed_at);

-- 供应链应用现有页面导航。后续业务页面通过新迁移登记，不允许运行时注入组件。
INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000050'), 'supply.dashboard', '/supply-chain', 'Odometer', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000051'), 'supply.city.menu', NULL, 'MapLocation', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000052'), 'supply.city.index', '/supply-chain/city', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000053'), 'supply.crm.menu', NULL, 'Shop', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000054'), 'supply.crm.index', '/supply-chain/crm', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000055'), 'supply.order.menu', NULL, 'List', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000056'), 'supply.order.index', '/supply-chain/order', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000057'), 'supply.sales.menu', NULL, 'User', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000058'), 'supply.sales.index', '/supply-chain/sales', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000059'), 'supply.erp.menu', NULL, 'SetUp', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000060'), 'supply.erp.index', '/supply-chain/erp', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000061'), 'supply.hr.menu', NULL, 'Avatar', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000062'), 'supply.hr.index', '/supply-chain/hr', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000063'), 'supply.channel.menu', NULL, 'Connection', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000064'), 'supply.channel.index', '/supply-chain/channel', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000065'), 'supply.bi.menu', NULL, 'DataAnalysis', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000066'), 'supply.bi.index', '/supply-chain/bi', NULL, 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000067'), 'supply.setting.menu', NULL, 'Setting', 1, 0, @seed_at, @seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000068'), 'supply.setting.index', '/supply-chain/settings', NULL, 1, 0, @seed_at, @seed_at);

-- Source: V9__iam_dictionary_and_dinghuobao_integration.sql
-- IAM V9：数据字典（平台/租户双边界）与订货宝数据同步应用/导航。
-- 不修改 V1~V8；平台已有迁移执行后只通过新迁移演进。

SET @seed_at = TIMESTAMP('2026-07-31 12:00:00.000000');
SET @platform_owner = UUID_TO_BIN('00000000-0000-0000-0000-000000000000');

CREATE TABLE iam_dictionary_type (
    id           BINARY(16)      NOT NULL,
    owner_scope  VARCHAR(16)     NOT NULL,
    owner_key    BINARY(16)      NOT NULL,
    tenant_id    BINARY(16)      NULL,
    type_code    VARCHAR(128)    NOT NULL,
    type_name    VARCHAR(128)    NOT NULL,
    description  VARCHAR(512)    NULL,
    status       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at   DATETIME(6)     NOT NULL,
    created_by   BINARY(16)      NULL,
    updated_at   DATETIME(6)     NOT NULL,
    updated_by   BINARY(16)      NULL,
    deleted_at   DATETIME(6)     NULL,
    deleted_by   BINARY(16)      NULL,
    delete_reason VARCHAR(512)   NULL,
    CONSTRAINT pk_iam_dictionary_type PRIMARY KEY (id),
    CONSTRAINT uk_iam_dictionary_type_owner_code UNIQUE (owner_key, type_code),
    CONSTRAINT ck_iam_dictionary_type_owner CHECK (
        (owner_scope = 'PLATFORM' AND tenant_id IS NULL)
        OR (owner_scope = 'TENANT' AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT ck_iam_dictionary_type_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_dictionary_type_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_dictionary_type_tenant (tenant_id, status),
    INDEX idx_iam_dictionary_type_scope (owner_scope, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台/租户数据字典类型';

CREATE TABLE iam_dictionary_item (
    id           BINARY(16)      NOT NULL,
    type_id      BINARY(16)      NOT NULL,
    tenant_id    BINARY(16)      NULL,
    item_code    VARCHAR(128)    NOT NULL,
    item_label   VARCHAR(128)    NOT NULL,
    item_value   VARCHAR(512)    NULL,
    sort_order   INT             NOT NULL DEFAULT 0,
    status       VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at   DATETIME(6)     NOT NULL,
    created_by   BINARY(16)      NULL,
    updated_at   DATETIME(6)     NOT NULL,
    updated_by   BINARY(16)      NULL,
    deleted_at   DATETIME(6)     NULL,
    deleted_by   BINARY(16)      NULL,
    delete_reason VARCHAR(512)   NULL,
    CONSTRAINT pk_iam_dictionary_item PRIMARY KEY (id),
    CONSTRAINT uk_iam_dictionary_item_type_code UNIQUE (type_id, item_code),
    CONSTRAINT ck_iam_dictionary_item_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_dictionary_item_type FOREIGN KEY (type_id)
        REFERENCES iam_dictionary_type (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_dictionary_item_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_dictionary_item_tenant (tenant_id, type_id, status),
    INDEX idx_iam_dictionary_item_sort (type_id, sort_order, item_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='数据字典条目';

-- 订货宝数据同步作为内部应用：员工在统一门户看到卡片，点击进入供应链下的受控页面。
SET @app_dinghuobao_integration = UUID_TO_BIN('019facf1-0000-7000-8000-000000000006');

INSERT INTO iam_application (
    id, app_code, app_name, app_scope, app_type, icon_key, sort_order,
    launch_mode, target_uri, credential_ref, status, created_at, updated_at
) VALUES (
    @app_dinghuobao_integration, 'DINGHUOBAO_INTEGRATION', '订货宝数据同步', 'TENANT', 'INTERNAL',
    'app-dinghuobao', 40, 'INTERNAL_ROUTE', '/supply-chain/dinghuobao', NULL, 'ACTIVE',
    @seed_at, @seed_at
);

-- 平台管理：数据字典菜单与API资源。
SET @r075 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000075');
SET @r076 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000076');
SET @r077 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000077');
SET @r078 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000078');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r075, UUID_TO_BIN('019facf1-0000-7000-8000-000000000001'),
     UUID_TO_BIN('019facf2-0000-7000-8000-000000000001'),
     'PLATFORM_ADMIN.MENU.DICTIONARY', 'MENU', NULL, '数据字典', 65, 'ACTIVE', @seed_at, @seed_at),
    (@r076, UUID_TO_BIN('019facf1-0000-7000-8000-000000000001'),
     @r075, 'PLATFORM_ADMIN.PAGE.DICTIONARY_LIST', 'PAGE', NULL, '平台数据字典', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@r077, UUID_TO_BIN('019facf1-0000-7000-8000-000000000001'),
     @r076, 'PLATFORM_ADMIN.API.DICTIONARY_READ', 'API', 'platform:dictionary:read',
     '查询平台数据字典', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r078, UUID_TO_BIN('019facf1-0000-7000-8000-000000000001'),
     @r076, 'PLATFORM_ADMIN.API.DICTIONARY_WRITE', 'API', 'platform:dictionary:write',
     '维护平台数据字典', 20, 'ACTIVE', @seed_at, @seed_at);

-- 租户系统管理：数据字典菜单与API资源。
SET @r079 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000079');
SET @r080 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000080');
SET @r081 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000081');
SET @r082 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000082');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r079, UUID_TO_BIN('019facf1-0000-7000-8000-000000000002'),
     UUID_TO_BIN('019facf2-0000-7000-8000-000000000024'),
     'SYSTEM_ADMIN.MENU.DICTIONARY', 'MENU', NULL, '数据字典', 55, 'ACTIVE', @seed_at, @seed_at),
    (@r080, UUID_TO_BIN('019facf1-0000-7000-8000-000000000002'),
     @r079, 'SYSTEM_ADMIN.PAGE.DICTIONARY_LIST', 'PAGE', NULL, '本租户数据字典', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@r081, UUID_TO_BIN('019facf1-0000-7000-8000-000000000002'),
     @r080, 'SYSTEM_ADMIN.API.DICTIONARY_READ', 'API', 'iam:dictionary:read',
     '查询本租户数据字典', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r082, UUID_TO_BIN('019facf1-0000-7000-8000-000000000002'),
     @r080, 'SYSTEM_ADMIN.API.DICTIONARY_WRITE', 'API', 'iam:dictionary:write',
     '维护本租户数据字典', 20, 'ACTIVE', @seed_at, @seed_at);

-- 供应链：订货宝同步菜单与页面框架。
SET @r083 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @r084 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @r085 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000085');
SET @r086 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000086');
SET @r087 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000087');
SET @r088 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000088');
SET @r089 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000089');
SET @r090 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000090');
SET @r091 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r083, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     UUID_TO_BIN('019facf2-0000-7000-8000-000000000049'),
     'SUPPLY_CHAIN.MENU.DINGHUOBAO', 'MENU', NULL, '订货宝数据同步', 45, 'ACTIVE', @seed_at, @seed_at),
    (@r084, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_OVERVIEW', 'PAGE', NULL, '同步概览', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@r085, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_ORDER_MIRROR', 'PAGE', NULL, '订单镜像', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@r086, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_SYNC_TASK', 'PAGE', NULL, '同步任务', 30,
     'ACTIVE', @seed_at, @seed_at),
    (@r087, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_SYNC_LOG', 'PAGE', NULL, '同步日志与死信', 40,
     'ACTIVE', @seed_at, @seed_at),
    (@r088, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_CONNECTION', 'PAGE', NULL, '连接配置', 50,
     'ACTIVE', @seed_at, @seed_at),
    (@r089, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_FIELD_MAPPING', 'PAGE', NULL, '字段映射', 60,
     'ACTIVE', @seed_at, @seed_at),
    (@r090, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_DATA_QUALITY', 'PAGE', NULL, '数据质量', 70,
     'ACTIVE', @seed_at, @seed_at),
    (@r091, UUID_TO_BIN('019facf1-0000-7000-8000-000000000003'),
     @r083, 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_BI_PREP', 'PAGE', NULL, 'BI数据准备', 80,
     'ACTIVE', @seed_at, @seed_at);

-- 订货宝数据同步应用根资源。
SET @r092 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000092');
INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @r092, @app_dinghuobao_integration, NULL, 'DINGHUOBAO_INTEGRATION.ROOT',
    'APPLICATION', NULL, '订货宝数据同步', 10, 'ACTIVE', @seed_at, @seed_at
);

-- 订货宝同步API权限。
SET @r093 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000093');
SET @r094 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000094');
INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r093, @app_dinghuobao_integration, @r092, 'DINGHUOBAO_INTEGRATION.API.READ',
     'API', 'integration:dinghuobao:read', '查询订货宝同步数据', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r094, @app_dinghuobao_integration, @r092, 'DINGHUOBAO_INTEGRATION.API.WRITE',
     'API', 'integration:dinghuobao:write', '维护订货宝同步配置', 20, 'ACTIVE', @seed_at, @seed_at);

-- 平台/租户管理导航。
INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (@r075, 'platform.dictionary.menu', NULL, 'Notebook', 1, 0, @seed_at, @seed_at),
    (@r076, 'platform.dictionary.list', '/platform-admin/dictionaries', NULL, 1, 0, @seed_at, @seed_at),
    (@r079, 'system.dictionary.menu', NULL, 'Notebook', 1, 0, @seed_at, @seed_at),
    (@r080, 'system.dictionary.list', '/system-admin/dictionaries', NULL, 1, 0, @seed_at, @seed_at);

-- 供应链订货宝导航。
INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (@r083, 'supply.dinghuobao.menu', NULL, 'Connection', 1, 0, @seed_at, @seed_at),
    (@r084, 'supply.dinghuobao.overview', '/supply-chain/dinghuobao', NULL, 1, 0, @seed_at, @seed_at),
    (@r085, 'supply.dinghuobao.order-mirror', '/supply-chain/dinghuobao/order-mirror', NULL, 1, 0, @seed_at, @seed_at),
    (@r086, 'supply.dinghuobao.sync-tasks', '/supply-chain/dinghuobao/sync-tasks', NULL, 1, 0, @seed_at, @seed_at),
    (@r087, 'supply.dinghuobao.sync-logs', '/supply-chain/dinghuobao/sync-logs', NULL, 1, 0, @seed_at, @seed_at),
    (@r088, 'supply.dinghuobao.connections', '/supply-chain/dinghuobao/connections', NULL, 1, 0, @seed_at, @seed_at),
    (@r089, 'supply.dinghuobao.field-mappings', '/supply-chain/dinghuobao/field-mappings', NULL, 1, 0, @seed_at, @seed_at),
    (@r090, 'supply.dinghuobao.data-quality', '/supply-chain/dinghuobao/data-quality', NULL, 1, 0, @seed_at, @seed_at),
    (@r091, 'supply.dinghuobao.bi-prep', '/supply-chain/dinghuobao/bi-prep', NULL, 1, 0, @seed_at, @seed_at);

-- 标准套餐补充：本租户字典、供应链订货宝、订货宝同步应用。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource.id, @seed_at, NULL
FROM iam_resource resource
WHERE resource.id IN (@r079, @r080, @r081, @r082, @r083, @r084, @r085, @r086,
                      @r087, @r088, @r089, @r090, @r091, @r092, @r093, @r094);

-- Source: V10__iam_dinghuobao_external_application.sql
-- IAM V10：将订货宝商城恢复为外部应用，取消订货宝同步卡片对供应链内部路由的错误复用。
-- 订货宝数据同步仍属于SUPPLY_CHAIN应用下的菜单和页面，不是独立门户应用。

SET @app_dinghuobao = UUID_TO_BIN('019facf1-0000-7000-8000-000000000004');
SET @app_dinghuobao_integration = UUID_TO_BIN('019facf1-0000-7000-8000-000000000006');

-- 订货宝商城卡片：统一门户负责直达第三方管理端；当前先使用官方测试入口。
UPDATE iam_application
   SET app_name='订货宝商城系统',
       launch_mode='EXTERNAL_URL',
       target_uri='https://pc.dhb168.com',
       status='ACTIVE',
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@app_dinghuobao
   AND app_code='DINGHUOBAO'
   AND app_scope='TENANT'
   AND app_type='EXTERNAL'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET display_name='订货宝商城系统',
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE application_id=@app_dinghuobao
   AND resource_code='DINGHUOBAO.ROOT'
   AND deleted_at IS NULL;

-- 订货宝同步能力仍由供应链菜单提供，不能再次作为门户卡片出现。
UPDATE iam_application
   SET status='DISABLED',
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@app_dinghuobao_integration
   AND app_code='DINGHUOBAO_INTEGRATION'
   AND deleted_at IS NULL;

-- Source: V11__iam_application_route_boundaries.sql
-- IAM V11：再次收敛门户应用启动地址，修复旧环境残留的含糊 /admin。
--
-- V8 已经定义了正确边界；本迁移用于共享DEV/历史环境可能未完整执行V8时的幂等纠偏。
-- 不修改已执行的历史迁移文件，平台管理和租户系统管理必须保持不同入口。

UPDATE iam_application
   SET target_uri = '/platform-admin',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'PLATFORM_ADMIN'
   AND app_scope = 'PLATFORM'
   AND launch_mode = 'INTERNAL_ROUTE'
   AND target_uri <> '/platform-admin'
   AND deleted_at IS NULL;

UPDATE iam_application
   SET target_uri = '/system-admin',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'SYSTEM_ADMIN'
   AND app_scope = 'TENANT'
   AND launch_mode = 'INTERNAL_ROUTE'
   AND target_uri <> '/system-admin'
   AND deleted_at IS NULL;

UPDATE iam_application
   SET target_uri = '/supply-chain',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'SUPPLY_CHAIN'
   AND app_scope = 'TENANT'
   AND launch_mode = 'INTERNAL_ROUTE'
   AND target_uri <> '/supply-chain'
   AND deleted_at IS NULL;

-- Source: V12__iam_dinghuobao_order_menu.sql
-- IAM V12：将供应链“订单”入口收敛为订货宝，并补齐订货宝一期只读导航。
-- 订单同步仍属于供应链内部应用；本迁移只增加菜单/页面/API资源，不提供订货宝写事实操作。

SET @seed_at = TIMESTAMP('2026-08-03 00:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');

UPDATE iam_resource
   SET display_name = '订货宝', version = version + 1, updated_at = @seed_at
 WHERE id = @order_menu AND resource_code = 'SUPPLY_CHAIN.MENU.ORDER';

UPDATE iam_resource
   SET display_name = '订单', version = version + 1, updated_at = @seed_at
 WHERE id = @order_page AND resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_INDEX';

UPDATE iam_resource_ui
   SET icon_key = 'ShoppingCart', updated_at = @seed_at
 WHERE resource_id = @order_menu;

SET @r101 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @r102 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @r103 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @r104 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @r105 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @r106 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @r107 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000107');
SET @r108 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000108');
SET @r109 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000109');
SET @r110 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000110');
SET @r111 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @r112 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @r113 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @r114 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @r115 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @r116 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');
SET @r117 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000117');
SET @r118 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000118');
SET @r119 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000119');
SET @r120 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r101, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_LIST', 'PAGE', NULL, '订货单', 20, 'ACTIVE', @seed_at, @seed_at),
    (@r102, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STOCK_UP', 'PAGE', NULL, '出库发货', 30, 'ACTIVE', @seed_at, @seed_at),
    (@r103, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SHIPMENTS', 'PAGE', NULL, '发货单', 40, 'ACTIVE', @seed_at, @seed_at),
    (@r104, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_RETURNS', 'PAGE', NULL, '退货单', 50, 'ACTIVE', @seed_at, @seed_at),
    (@r105, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_DELIVERY_PARTNERS', 'PAGE', NULL, '配送伙伴', 60, 'ACTIVE', @seed_at, @seed_at),
    (@r106, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_GOODS', 'PAGE', NULL, '订单商品统计', 70, 'ACTIVE', @seed_at, @seed_at),
    (@r107, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_PENDING_STOCK', 'PAGE', NULL, '待出库统计', 80, 'ACTIVE', @seed_at, @seed_at),
    (@r108, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_SHIPPED', 'PAGE', NULL, '已出库统计', 90, 'ACTIVE', @seed_at, @seed_at),
    (@r109, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_PENDING_DELIVERY', 'PAGE', NULL, '待发货统计', 100, 'ACTIVE', @seed_at, @seed_at),
    (@r110, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_STATS_RETURNS', 'PAGE', NULL, '退单商品统计', 110, 'ACTIVE', @seed_at, @seed_at),
    (@r111, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_GOODS', 'PAGE', NULL, '商品', 120, 'ACTIVE', @seed_at, @seed_at),
    (@r112, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_MARKETING', 'PAGE', NULL, '营销', 130, 'ACTIVE', @seed_at, @seed_at),
    (@r113, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_INVENTORY', 'PAGE', NULL, '库存', 140, 'ACTIVE', @seed_at, @seed_at),
    (@r114, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_CUSTOMER', 'PAGE', NULL, '客户', 150, 'ACTIVE', @seed_at, @seed_at),
    (@r115, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_FINANCE', 'PAGE', NULL, '资金', 160, 'ACTIVE', @seed_at, @seed_at),
    (@r116, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_REPORT', 'PAGE', NULL, '报表', 170, 'ACTIVE', @seed_at, @seed_at),
    (@r117, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_DATA', 'PAGE', NULL, '数据', 180, 'ACTIVE', @seed_at, @seed_at),
    (@r118, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SYSTEM', 'PAGE', NULL, '系统', 190, 'ACTIVE', @seed_at, @seed_at),
    (@r119, @app_supply_chain, @order_page, 'SUPPLY_CHAIN.API.ORDER_READ', 'API', 'order:read', '查询订货宝订单', 10, 'ACTIVE', @seed_at, @seed_at),
    (@r120, @app_supply_chain, @order_page, 'SUPPLY_CHAIN.API.ORDER_WRITE', 'API', 'order:write', '同步订货宝订单到本地', 20, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_resource_ui (resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at) VALUES
    (@r101, 'supply.order.order-list', '/supply-chain/order/orders', NULL, 1, 0, @seed_at, @seed_at),
    (@r102, 'supply.order.stock-up', '/supply-chain/order/stock-up', NULL, 1, 0, @seed_at, @seed_at),
    (@r103, 'supply.order.shipments', '/supply-chain/order/shipments', NULL, 1, 0, @seed_at, @seed_at),
    (@r104, 'supply.order.returns', '/supply-chain/order/returns', NULL, 1, 0, @seed_at, @seed_at),
    (@r105, 'supply.order.delivery-partners', '/supply-chain/order/delivery-partners', NULL, 1, 0, @seed_at, @seed_at),
    (@r106, 'supply.order.stats-goods', '/supply-chain/order/stats/goods', NULL, 1, 0, @seed_at, @seed_at),
    (@r107, 'supply.order.stats-pending-stock', '/supply-chain/order/stats/pending-stock', NULL, 1, 0, @seed_at, @seed_at),
    (@r108, 'supply.order.stats-shipped', '/supply-chain/order/stats/shipped', NULL, 1, 0, @seed_at, @seed_at),
    (@r109, 'supply.order.stats-pending-delivery', '/supply-chain/order/stats/pending-delivery', NULL, 1, 0, @seed_at, @seed_at),
    (@r110, 'supply.order.stats-returns', '/supply-chain/order/stats/returns', NULL, 1, 0, @seed_at, @seed_at),
    (@r111, 'supply.order.goods', '/supply-chain/order/goods', NULL, 1, 0, @seed_at, @seed_at),
    (@r112, 'supply.order.marketing', '/supply-chain/order/marketing', NULL, 1, 0, @seed_at, @seed_at),
    (@r113, 'supply.order.inventory', '/supply-chain/order/inventory', NULL, 1, 0, @seed_at, @seed_at),
    (@r114, 'supply.order.customer', '/supply-chain/order/customer', NULL, 1, 0, @seed_at, @seed_at),
    (@r115, 'supply.order.finance', '/supply-chain/order/finance', NULL, 1, 0, @seed_at, @seed_at),
    (@r116, 'supply.order.report', '/supply-chain/order/report', NULL, 1, 0, @seed_at, @seed_at),
    (@r117, 'supply.order.data', '/supply-chain/order/data', NULL, 1, 0, @seed_at, @seed_at),
    (@r118, 'supply.order.system', '/supply-chain/order/system', NULL, 1, 0, @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource.id, @seed_at, NULL
FROM iam_resource resource
WHERE resource.id IN (@r101, @r102, @r103, @r104, @r105, @r106, @r107, @r108, @r109, @r110,
                      @r111, @r112, @r113, @r114, @r115, @r116, @r117, @r118, @r119, @r120);

-- Source: V13__iam_order_nested_menu.sql
-- IAM V13：将订货宝下的订单菜单调整为三级树。
-- 订货宝（一级）→ 订单（二级）→ 订货单等页面（三级）。订单统计保留统计页面分组。

SET @seed_at = TIMESTAMP('2026-08-03 14:30:00.000000');
SET @order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @r101 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @r102 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @r103 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @r104 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @r105 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @r106 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @r107 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000107');
SET @r108 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000108');
SET @r109 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000109');
SET @r110 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000110');

-- 订单模块下挂订货单、出库发货、发货单、退货单、配送伙伴和订单统计。
UPDATE iam_resource
   SET parent_id = @order_page, version = version + 1, updated_at = @seed_at
 WHERE id IN (@r101, @r102, @r103, @r104, @r105, @r106);

-- 订单统计作为三级菜单分组，统计页面作为其下级页面。
UPDATE iam_resource
   SET parent_id = @order_page,
       resource_code = 'SUPPLY_CHAIN.MENU.ORDER_STATS',
       resource_type = 'MENU',
       display_name = '订单统计',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @r106;

UPDATE iam_resource
   SET parent_id = @r106, version = version + 1, updated_at = @seed_at
 WHERE id IN (@r107, @r108, @r109, @r110);

UPDATE iam_resource_ui
   SET route_key = 'supply.order.stats', route_path = NULL, updated_at = @seed_at
 WHERE resource_id = @r106;

-- Source: V14__iam_grant_order_menu_to_tenant_admin.sql
-- IAM V14：将V12/V13新增的订货宝订单菜单授权给已存在的租户超级管理员。
-- 新资源先进入套餐不会自动补写既有角色，否则旧租户登录后只能看到迁移前的订单入口。

SET @seed_at = TIMESTAMP('2026-08-03 15:10:00.000000');
SET @r101 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @r102 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @r103 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @r104 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @r105 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @r106 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @r107 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000107');
SET @r108 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000108');
SET @r109 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000109');
SET @r110 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000110');
SET @r111 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @r112 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @r113 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @r114 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @r115 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @r116 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');
SET @r117 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000117');
SET @r118 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000118');
SET @r119 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000119');
SET @r120 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

INSERT INTO iam_role_resource
    (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, package_resource.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND package_resource.resource_id IN (
       @r101, @r102, @r103, @r104, @r105, @r106, @r107, @r108, @r109,
       @r110, @r111, @r112, @r113, @r114, @r115, @r116, @r117, @r118,
       @r119, @r120
   )
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role role_record
        WHERE role_record.tenant_id = tenant_record.id
          AND role_record.role_code = 'TENANT_SUPER_ADMIN'
          AND role_record.role_type = 'SYSTEM'
          AND role_record.status = 'ACTIVE'
          AND role_record.deleted_at IS NULL
   );

-- Source: V15__iam_make_order_group_non_navigable.sql
-- IAM V15：将“订货宝”下的“订单”调整为不可跳转的二级菜单分组。
-- 订单查询只允许从三级“订货单”进入，点击“订单”本身只展开/收起菜单，不触发接口请求。

SET @seed_at = TIMESTAMP('2026-08-03 16:00:00.000000');
SET @order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');

UPDATE iam_resource
   SET resource_code = 'SUPPLY_CHAIN.MENU.ORDER_GROUP',
       resource_type = 'MENU',
       display_name = '订单',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @order_page
   AND resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_INDEX';

UPDATE iam_resource_ui
   SET route_key = 'supply.order.group',
       route_path = NULL,
       updated_at = @seed_at
 WHERE resource_id = @order_page;

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role role_record
        WHERE role_record.tenant_id = tenant_record.id
          AND role_record.role_code = 'TENANT_SUPER_ADMIN'
          AND role_record.role_type = 'SYSTEM'
          AND role_record.status = 'ACTIVE'
          AND role_record.deleted_at IS NULL
   );

-- Source: V16__iam_remove_obsolete_order_write_resource.sql
-- IAM V16：删除已取消的订单中心“订货宝订单同步”权限。
-- V12/V14 已在共享 DEV 执行，不能修改历史迁移；本迁移只清理不再存在的运行时权限和资源。

SET @cleanup_at = TIMESTAMP('2026-08-04 12:00:00.000000');
SET @order_write_resource = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

UPDATE iam_tenant tenant_record
JOIN (
    SELECT DISTINCT tenant_id
      FROM iam_role_resource
     WHERE resource_id = @order_write_resource
) affected_tenant
  ON affected_tenant.tenant_id = tenant_record.id
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @cleanup_at;

DELETE FROM iam_role_resource
 WHERE resource_id = @order_write_resource;

DELETE FROM iam_package_resource
 WHERE resource_id = @order_write_resource;

DELETE FROM iam_resource_ui
 WHERE resource_id = @order_write_resource;

DELETE FROM iam_resource
 WHERE id = @order_write_resource
   AND resource_code = 'SUPPLY_CHAIN.API.ORDER_WRITE';

-- Source: V17__iam_dhb_short_names.sql
-- IAM V17：将订货宝运行时标识统一收缩为 DHB。
--
-- V6、V9、V10 等历史迁移文件和已执行的 Flyway 历史不改写；本迁移只更新运行时数据。
-- 业务显示名称仍使用中文“订货宝”，代码、权限和门户路由使用短标识 DHB。

UPDATE iam_application
   SET app_code = 'DHB',
       icon_key = 'app-dhb',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'DINGHUOBAO'
   AND deleted_at IS NULL;

UPDATE iam_application
   SET app_code = 'DHB_INTEGRATION',
       icon_key = 'app-dhb',
       target_uri = '/supply-chain/dhb',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'DINGHUOBAO_INTEGRATION'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET resource_code = REPLACE(resource_code, 'DINGHUOBAO', 'DHB'),
       permission_code = CASE
           WHEN permission_code LIKE 'integration:dinghuobao:%'
               THEN REPLACE(permission_code, 'integration:dinghuobao:', 'integration:dhb:')
           ELSE permission_code
       END,
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE deleted_at IS NULL
   AND (resource_code LIKE 'DINGHUOBAO%'
        OR permission_code LIKE 'integration:dinghuobao:%');

UPDATE iam_resource_ui
   SET route_key = REPLACE(route_key, 'supply.dinghuobao', 'supply.dhb'),
       route_path = REPLACE(route_path, '/supply-chain/dinghuobao', '/supply-chain/dhb'),
       updated_at = UTC_TIMESTAMP(6)
 WHERE route_key LIKE 'supply.dinghuobao%'
    OR route_path LIKE '/supply-chain/dinghuobao%';

-- Source: V18__iam_sales_management_and_feishu_app.sql
-- IAM V18：按销售工作台V1.1建立供应链销售管理菜单，并启用飞书销售工作台门户卡片。
-- 仅新增/演进运行时资源，不修改V6/V8等已执行历史迁移。

SET @seed_at = TIMESTAMP('2026-08-06 18:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @sales_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000057');
SET @sales_dashboard = UUID_TO_BIN('019facf2-0000-7000-8000-000000000058');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');

-- 飞书应用仍是外部应用；Portal目标是受控引导页，不复制PC打卡能力。
UPDATE iam_application
   SET app_name = '飞书销售工作台',
       icon_key = 'app-feishu-sales',
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @app_feishu_sales
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET display_name = '销售管理', version = version + 1, updated_at = @seed_at
 WHERE id = @sales_menu
   AND resource_code = 'SUPPLY_CHAIN.MENU.SALES';

UPDATE iam_resource
   SET resource_code = 'SUPPLY_CHAIN.PAGE.SALES_DASHBOARD',
       display_name = '销售管控台',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @sales_dashboard
   AND resource_code = 'SUPPLY_CHAIN.PAGE.SALES_INDEX';

UPDATE iam_resource_ui
   SET route_key = 'supply.sales.dashboard',
       route_path = '/supply-chain/sales',
       icon_key = 'Odometer',
       updated_at = @seed_at
 WHERE resource_id = @sales_dashboard;

SET @r121 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000121');
SET @r122 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000122');
SET @r123 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000123');
SET @r124 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000124');
SET @r125 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000125');
SET @r126 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000126');
SET @r127 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000127');
SET @r128 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000128');
SET @r129 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000129');
SET @r130 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000130');
SET @r131 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000131');
SET @r132 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000132');
SET @r133 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000133');
SET @r134 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000134');
SET @r135 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000135');
SET @r136 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000136');
SET @r137 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000137');
SET @r138 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000138');
SET @r139 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000139');
SET @r140 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000140');
SET @r141 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000141');
SET @r142 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000142');
SET @r143 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000143');
SET @r144 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000144');
SET @r145 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000145');
SET @r146 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000146');
SET @r147 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000147');
SET @r148 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000148');
SET @r149 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000149');
SET @r150 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000150');
SET @r151 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000151');
SET @r152 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000152');
SET @r153 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000153');
SET @r154 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000154');
SET @r155 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000155');
SET @r156 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000156');
SET @r157 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000157');
SET @r158 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000158');
SET @r159 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000159');
SET @r160 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000160');
SET @r161 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000161');
SET @r162 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000162');
SET @r163 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000163');
SET @r164 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000164');
SET @r165 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000165');
SET @r166 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000166');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@r121,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_ATTENDANCE','MENU',NULL,'外勤考勤',20,'ACTIVE',@seed_at,@seed_at),
    (@r122,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_TODAY','PAGE',NULL,'今日状态',10,'ACTIVE',@seed_at,@seed_at),
    (@r123,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_PUNCHES','PAGE',NULL,'打卡明细',20,'ACTIVE',@seed_at,@seed_at),
    (@r124,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_DAYS','PAGE',NULL,'工作日结',30,'ACTIVE',@seed_at,@seed_at),
    (@r125,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_INTERRUPTION','PAGE',NULL,'定位中断摘要',40,'ACTIVE',@seed_at,@seed_at),
    (@r126,@app_supply_chain,@r121,'SUPPLY_CHAIN.PAGE.SALES_ATTENDANCE_ADJUSTMENT','PAGE',NULL,'补卡与异常',50,'ACTIVE',@seed_at,@seed_at),

    (@r127,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_VISIT','MENU',NULL,'拜访管理',30,'ACTIVE',@seed_at,@seed_at),
    (@r128,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN','PAGE',NULL,'拜访计划',10,'ACTIVE',@seed_at,@seed_at),
    (@r129,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_RECORD','PAGE',NULL,'拜访记录',20,'ACTIVE',@seed_at,@seed_at),
    (@r130,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW','PAGE',NULL,'待复核',30,'ACTIVE',@seed_at,@seed_at),
    (@r131,@app_supply_chain,@r127,'SUPPLY_CHAIN.PAGE.SALES_VISIT_APPEAL','PAGE',NULL,'申诉与调整',40,'ACTIVE',@seed_at,@seed_at),

    (@r132,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_STORE','MENU',NULL,'门店覆盖',40,'ACTIVE',@seed_at,@seed_at),
    (@r133,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_ASSIGNED','PAGE',NULL,'负责门店',10,'ACTIVE',@seed_at,@seed_at),
    (@r134,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_UNCOVERED','PAGE',NULL,'未覆盖门店',20,'ACTIVE',@seed_at,@seed_at),
    (@r135,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_VISITED','PAGE',NULL,'已拜访门店',30,'ACTIVE',@seed_at,@seed_at),
    (@r136,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_EFFECTIVE','PAGE',NULL,'有效拜访门店',40,'ACTIVE',@seed_at,@seed_at),
    (@r137,@app_supply_chain,@r132,'SUPPLY_CHAIN.PAGE.SALES_STORE_CANDIDATE','PAGE',NULL,'新客户门店线索',50,'ACTIVE',@seed_at,@seed_at),

    (@r138,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_ORGANIZATION','MENU',NULL,'销售组织',50,'ACTIVE',@seed_at,@seed_at),
    (@r139,@app_supply_chain,@r138,'SUPPLY_CHAIN.PAGE.SALES_PROFILE','PAGE',NULL,'销售画像',10,'ACTIVE',@seed_at,@seed_at),
    (@r140,@app_supply_chain,@r138,'SUPPLY_CHAIN.PAGE.SALES_TEAM','PAGE',NULL,'销售团队',20,'ACTIVE',@seed_at,@seed_at),
    (@r141,@app_supply_chain,@r138,'SUPPLY_CHAIN.PAGE.SALES_ORG_SCOPE','PAGE',NULL,'任职与城市范围',30,'ACTIVE',@seed_at,@seed_at),

    (@r142,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_TASK','MENU',NULL,'任务与目标',60,'ACTIVE',@seed_at,@seed_at),
    (@r143,@app_supply_chain,@r142,'SUPPLY_CHAIN.PAGE.SALES_VISIT_TASK','PAGE',NULL,'拜访任务',10,'ACTIVE',@seed_at,@seed_at),
    (@r144,@app_supply_chain,@r142,'SUPPLY_CHAIN.PAGE.SALES_TARGET','PAGE',NULL,'目标分配',20,'ACTIVE',@seed_at,@seed_at),
    (@r145,@app_supply_chain,@r142,'SUPPLY_CHAIN.PAGE.SALES_TARGET_EXEMPTION','PAGE',NULL,'目标减免',30,'ACTIVE',@seed_at,@seed_at),

    (@r146,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_EXCEPTION','MENU',NULL,'异常与复核',70,'ACTIVE',@seed_at,@seed_at),
    (@r147,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_PUNCH','PAGE',NULL,'打卡异常',10,'ACTIVE',@seed_at,@seed_at),
    (@r148,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_LOCATION','PAGE',NULL,'定位异常',20,'ACTIVE',@seed_at,@seed_at),
    (@r149,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_EVIDENCE','PAGE',NULL,'拜访证据异常',30,'ACTIVE',@seed_at,@seed_at),
    (@r150,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_RECORDING','PAGE',NULL,'录音与AI异常',40,'ACTIVE',@seed_at,@seed_at),
    (@r151,@app_supply_chain,@r146,'SUPPLY_CHAIN.PAGE.SALES_EXCEPTION_REVIEW','PAGE',NULL,'主管复核',50,'ACTIVE',@seed_at,@seed_at),

    (@r152,@app_supply_chain,@sales_menu,'SUPPLY_CHAIN.MENU.SALES_POLICY','MENU',NULL,'规则配置',80,'ACTIVE',@seed_at,@seed_at),
    (@r153,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_FIELD','PAGE',NULL,'外勤规则',10,'ACTIVE',@seed_at,@seed_at),
    (@r154,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_VISIT','PAGE',NULL,'拜访规则',20,'ACTIVE',@seed_at,@seed_at),
    (@r155,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_RECORDING_AI','PAGE',NULL,'录音与AI规则',30,'ACTIVE',@seed_at,@seed_at),
    (@r156,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_SCOPE','PAGE',NULL,'适用范围',40,'ACTIVE',@seed_at,@seed_at),
    (@r157,@app_supply_chain,@r152,'SUPPLY_CHAIN.PAGE.SALES_POLICY_RELEASE','PAGE',NULL,'发布与历史版本',50,'ACTIVE',@seed_at,@seed_at),

    (@r158,@app_supply_chain,@sales_dashboard,'SUPPLY_CHAIN.API.SALES_DASHBOARD_READ','API','sales:dashboard:read','查询销售管控台',10,'ACTIVE',@seed_at,@seed_at),
    (@r159,@app_supply_chain,@r122,'SUPPLY_CHAIN.API.SALES_WORK_DAY_READ','API','sales:work-day:read','查询销售工作日',10,'ACTIVE',@seed_at,@seed_at),
    (@r160,@app_supply_chain,@r129,'SUPPLY_CHAIN.API.SALES_VISIT_READ','API','sales:visit:read','查询销售拜访',10,'ACTIVE',@seed_at,@seed_at),
    (@r161,@app_supply_chain,@r130,'SUPPLY_CHAIN.API.SALES_VISIT_REVIEW','API','sales:visit:review','复核销售拜访',10,'ACTIVE',@seed_at,@seed_at),
    (@r162,@app_supply_chain,@r148,'SUPPLY_CHAIN.API.SALES_LOCATION_READ','API','sales:location:sensitive:read','查看精确定位',10,'ACTIVE',@seed_at,@seed_at),
    (@r163,@app_supply_chain,@r150,'SUPPLY_CHAIN.API.SALES_RECORDING_PLAY','API','sales:recording:sensitive:play','播放拜访录音',10,'ACTIVE',@seed_at,@seed_at),
    (@r164,@app_supply_chain,@r153,'SUPPLY_CHAIN.API.SALES_POLICY_READ','API','sales:policy:read','查询销售规则',10,'ACTIVE',@seed_at,@seed_at),
    (@r165,@app_supply_chain,@r153,'SUPPLY_CHAIN.API.SALES_POLICY_WRITE','API','sales:policy:write','维护销售规则草稿',20,'ACTIVE',@seed_at,@seed_at),
    (@r166,@app_supply_chain,@r157,'SUPPLY_CHAIN.API.SALES_POLICY_PUBLISH','API','sales:policy:publish','发布销售规则',10,'ACTIVE',@seed_at,@seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@r121,'supply.sales.attendance.menu',NULL,'Clock',1,0,@seed_at,@seed_at),
    (@r122,'supply.sales.attendance.today','/supply-chain/sales/attendance/today',NULL,1,0,@seed_at,@seed_at),
    (@r123,'supply.sales.attendance.punches','/supply-chain/sales/attendance/punches',NULL,1,0,@seed_at,@seed_at),
    (@r124,'supply.sales.attendance.days','/supply-chain/sales/attendance/days',NULL,1,0,@seed_at,@seed_at),
    (@r125,'supply.sales.attendance.interruptions','/supply-chain/sales/attendance/interruptions',NULL,1,0,@seed_at,@seed_at),
    (@r126,'supply.sales.attendance.adjustments','/supply-chain/sales/attendance/adjustments',NULL,1,0,@seed_at,@seed_at),
    (@r127,'supply.sales.visits.menu',NULL,'Location',1,0,@seed_at,@seed_at),
    (@r128,'supply.sales.visits.plans','/supply-chain/sales/visits/plans',NULL,1,0,@seed_at,@seed_at),
    (@r129,'supply.sales.visits.records','/supply-chain/sales/visits/records',NULL,1,0,@seed_at,@seed_at),
    (@r130,'supply.sales.visits.reviews','/supply-chain/sales/visits/reviews',NULL,1,0,@seed_at,@seed_at),
    (@r131,'supply.sales.visits.appeals','/supply-chain/sales/visits/appeals',NULL,1,0,@seed_at,@seed_at),
    (@r132,'supply.sales.stores.menu',NULL,'Shop',1,0,@seed_at,@seed_at),
    (@r133,'supply.sales.stores.assigned','/supply-chain/sales/stores/assigned',NULL,1,0,@seed_at,@seed_at),
    (@r134,'supply.sales.stores.uncovered','/supply-chain/sales/stores/uncovered',NULL,1,0,@seed_at,@seed_at),
    (@r135,'supply.sales.stores.visited','/supply-chain/sales/stores/visited',NULL,1,0,@seed_at,@seed_at),
    (@r136,'supply.sales.stores.effective','/supply-chain/sales/stores/effective',NULL,1,0,@seed_at,@seed_at),
    (@r137,'supply.sales.stores.candidates','/supply-chain/sales/stores/candidates',NULL,1,0,@seed_at,@seed_at),
    (@r138,'supply.sales.organization.menu',NULL,'User',1,0,@seed_at,@seed_at),
    (@r139,'supply.sales.organization.profiles','/supply-chain/sales/organization/profiles',NULL,1,0,@seed_at,@seed_at),
    (@r140,'supply.sales.organization.teams','/supply-chain/sales/organization/teams',NULL,1,0,@seed_at,@seed_at),
    (@r141,'supply.sales.organization.scopes','/supply-chain/sales/organization/scopes',NULL,1,0,@seed_at,@seed_at),
    (@r142,'supply.sales.tasks.menu',NULL,'Flag',1,0,@seed_at,@seed_at),
    (@r143,'supply.sales.tasks.visits','/supply-chain/sales/tasks/visits',NULL,1,0,@seed_at,@seed_at),
    (@r144,'supply.sales.tasks.targets','/supply-chain/sales/tasks/targets',NULL,1,0,@seed_at,@seed_at),
    (@r145,'supply.sales.tasks.exemptions','/supply-chain/sales/tasks/exemptions',NULL,1,0,@seed_at,@seed_at),
    (@r146,'supply.sales.exceptions.menu',NULL,'Warning',1,0,@seed_at,@seed_at),
    (@r147,'supply.sales.exceptions.punch','/supply-chain/sales/exceptions/punch',NULL,1,0,@seed_at,@seed_at),
    (@r148,'supply.sales.exceptions.location','/supply-chain/sales/exceptions/location',NULL,1,0,@seed_at,@seed_at),
    (@r149,'supply.sales.exceptions.evidence','/supply-chain/sales/exceptions/evidence',NULL,1,0,@seed_at,@seed_at),
    (@r150,'supply.sales.exceptions.recording','/supply-chain/sales/exceptions/recording',NULL,1,0,@seed_at,@seed_at),
    (@r151,'supply.sales.exceptions.reviews','/supply-chain/sales/exceptions/reviews',NULL,1,0,@seed_at,@seed_at),
    (@r152,'supply.sales.policies.menu',NULL,'Setting',1,0,@seed_at,@seed_at),
    (@r153,'supply.sales.policies.field','/supply-chain/sales/policies/field',NULL,1,0,@seed_at,@seed_at),
    (@r154,'supply.sales.policies.visit','/supply-chain/sales/policies/visit',NULL,1,0,@seed_at,@seed_at),
    (@r155,'supply.sales.policies.recording-ai','/supply-chain/sales/policies/recording-ai',NULL,1,0,@seed_at,@seed_at),
    (@r156,'supply.sales.policies.scopes','/supply-chain/sales/policies/scopes',NULL,1,0,@seed_at,@seed_at),
    (@r157,'supply.sales.policies.releases','/supply-chain/sales/policies/releases',NULL,1,0,@seed_at,@seed_at);

-- 新资源进入现行标准套餐；历史套餐版本不改字段，只补充资源关系。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @r121 resource_id UNION ALL SELECT @r122 UNION ALL SELECT @r123 UNION ALL SELECT @r124
    UNION ALL SELECT @r125 UNION ALL SELECT @r126 UNION ALL SELECT @r127 UNION ALL SELECT @r128
    UNION ALL SELECT @r129 UNION ALL SELECT @r130 UNION ALL SELECT @r131 UNION ALL SELECT @r132
    UNION ALL SELECT @r133 UNION ALL SELECT @r134 UNION ALL SELECT @r135 UNION ALL SELECT @r136
    UNION ALL SELECT @r137 UNION ALL SELECT @r138 UNION ALL SELECT @r139 UNION ALL SELECT @r140
    UNION ALL SELECT @r141 UNION ALL SELECT @r142 UNION ALL SELECT @r143 UNION ALL SELECT @r144
    UNION ALL SELECT @r145 UNION ALL SELECT @r146 UNION ALL SELECT @r147 UNION ALL SELECT @r148
    UNION ALL SELECT @r149 UNION ALL SELECT @r150 UNION ALL SELECT @r151 UNION ALL SELECT @r152
    UNION ALL SELECT @r153 UNION ALL SELECT @r154 UNION ALL SELECT @r155 UNION ALL SELECT @r156
    UNION ALL SELECT @r157 UNION ALL SELECT @r158 UNION ALL SELECT @r159 UNION ALL SELECT @r160
    UNION ALL SELECT @r161 UNION ALL SELECT @r162 UNION ALL SELECT @r163 UNION ALL SELECT @r164
    UNION ALL SELECT @r165 UNION ALL SELECT @r166
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- 已存在租户超级管理员需要显式补授权；新租户由Bootstrap按套餐自动获得。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resource_list.resource_id, 'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
  JOIN (
    SELECT @feishu_sales_root resource_id UNION ALL
    SELECT @r121 UNION ALL SELECT @r122 UNION ALL SELECT @r123 UNION ALL SELECT @r124
    UNION ALL SELECT @r125 UNION ALL SELECT @r126 UNION ALL SELECT @r127 UNION ALL SELECT @r128
    UNION ALL SELECT @r129 UNION ALL SELECT @r130 UNION ALL SELECT @r131 UNION ALL SELECT @r132
    UNION ALL SELECT @r133 UNION ALL SELECT @r134 UNION ALL SELECT @r135 UNION ALL SELECT @r136
    UNION ALL SELECT @r137 UNION ALL SELECT @r138 UNION ALL SELECT @r139 UNION ALL SELECT @r140
    UNION ALL SELECT @r141 UNION ALL SELECT @r142 UNION ALL SELECT @r143 UNION ALL SELECT @r144
    UNION ALL SELECT @r145 UNION ALL SELECT @r146 UNION ALL SELECT @r147 UNION ALL SELECT @r148
    UNION ALL SELECT @r149 UNION ALL SELECT @r150 UNION ALL SELECT @r151 UNION ALL SELECT @r152
    UNION ALL SELECT @r153 UNION ALL SELECT @r154 UNION ALL SELECT @r155 UNION ALL SELECT @r156
    UNION ALL SELECT @r157 UNION ALL SELECT @r158 UNION ALL SELECT @r159 UNION ALL SELECT @r160
    UNION ALL SELECT @r161 UNION ALL SELECT @r162 UNION ALL SELECT @r163 UNION ALL SELECT @r164
    UNION ALL SELECT @r165 UNION ALL SELECT @r166
  ) resource_list
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V19__iam_align_supply_navigation_v21.sql
-- IAM V19：按总体架构V2.1收口供应链一级业务域，并清理订货宝时期的临时菜单命名。
-- V18已在共享DEV执行，本迁移只演进运行时资源，不改写任何历史迁移。

SET @changed_at = TIMESTAMP('2026-08-06 19:00:00.000000');
SET @supply_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @dashboard = UUID_TO_BIN('019facf2-0000-7000-8000-000000000050');
SET @city = UUID_TO_BIN('019facf2-0000-7000-8000-000000000051');
SET @city_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000052');
SET @crm = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @crm_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000054');
SET @orders = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_access = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000057');
SET @erp = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @erp_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000060');
SET @hr = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @hr_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @channel = UUID_TO_BIN('019facf2-0000-7000-8000-000000000063');
SET @channel_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000064');
SET @bi = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @settings = UUID_TO_BIN('019facf2-0000-7000-8000-000000000067');
SET @settings_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000068');
SET @integration = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');

-- 一级业务域顺序严格对应V2.1 pathroot，不再使用“入口”等临时名称。
UPDATE iam_resource
   SET display_name = CASE id
       WHEN @dashboard THEN '供应链首页'
       WHEN @erp THEN 'ERP'
       WHEN @crm THEN 'CRM'
       WHEN @orders THEN '订单管理'
       WHEN @sales THEN '销售管理'
       WHEN @city THEN '城市运营'
       WHEN @bi THEN 'BI 数据看板'
       WHEN @hr THEN '人事与绩效'
       WHEN @channel THEN '渠道代理'
       WHEN @integration THEN '外部集成与数据同步'
       WHEN @settings THEN '业务设置'
       END,
       sort_order = CASE id
       WHEN @dashboard THEN 10 WHEN @erp THEN 20 WHEN @crm THEN 30
       WHEN @orders THEN 40 WHEN @sales THEN 50 WHEN @city THEN 60
       WHEN @bi THEN 70 WHEN @hr THEN 80 WHEN @channel THEN 90
       WHEN @integration THEN 100 WHEN @settings THEN 110 END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@dashboard,@erp,@crm,@orders,@sales,@city,@bi,@hr,@channel,@integration,@settings)
   AND parent_id = @supply_root;

UPDATE iam_resource_ui
   SET icon_key = CASE resource_id
       WHEN @dashboard THEN 'House'
       WHEN @erp THEN 'Goods'
       WHEN @crm THEN 'Shop'
       WHEN @orders THEN 'List'
       WHEN @sales THEN 'Location'
       WHEN @city THEN 'OfficeBuilding'
       WHEN @bi THEN 'TrendCharts'
       WHEN @hr THEN 'UserFilled'
       WHEN @channel THEN 'Avatar'
       WHEN @integration THEN 'Connection'
       WHEN @settings THEN 'Setting'
       END,
       updated_at = @changed_at
 WHERE resource_id IN (@dashboard,@erp,@crm,@orders,@sales,@city,@bi,@hr,@channel,@integration,@settings);

UPDATE iam_resource
   SET display_name = CASE id
       WHEN @city_home THEN '城市工作台'
       WHEN @crm_home THEN 'CRM 工作台'
       WHEN @erp_home THEN 'ERP 工作台'
       WHEN @hr_home THEN '员工档案'
       WHEN @channel_home THEN '代理档案'
       WHEN @bi_home THEN '经营驾驶舱'
       WHEN @settings_home THEN '业务设置概览'
       END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@city_home,@crm_home,@erp_home,@hr_home,@channel_home,@bi_home,@settings_home);

-- 将早期“订货宝菜单镜像”收口为订单域结构；保留稳定资源ID和直接路由兼容性。
SET @order_provider = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @order_stock_up = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @order_shipping = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @order_return = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @order_partner = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @order_fulfillment = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_after_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @order_center = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @order_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @order_all = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @order_pending = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @order_exception = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');

UPDATE iam_resource
   SET display_name = '订单接入', sort_order = 20, version = version + 1, updated_at = @changed_at
 WHERE id = @order_access;

UPDATE iam_resource
   SET display_name = '订货宝订单', sort_order = 10, version = version + 1, updated_at = @changed_at
 WHERE id = @order_provider;

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.MENU.ORDER_FULFILLMENT',
       resource_type = 'MENU', display_name = '履约编排', sort_order = 40,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_fulfillment;

UPDATE iam_resource
   SET parent_id = @order_fulfillment,
       display_name = CASE id WHEN @order_shipping THEN '发货与配送状态' ELSE '配送伙伴' END,
       sort_order = CASE id WHEN @order_shipping THEN 30 ELSE 40 END,
       version = version + 1, updated_at = @changed_at
 WHERE id IN (@order_shipping,@order_partner);

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.MENU.ORDER_AFTER_SALES',
       resource_type = 'MENU', display_name = '售后管理', sort_order = 50,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_after_sales;

UPDATE iam_resource
   SET parent_id = @order_after_sales, display_name = '退货', sort_order = 10,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_return;

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.MENU.ORDER_CENTER',
       resource_type = 'MENU', display_name = '订单中心', sort_order = 30,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_center;

UPDATE iam_resource
   SET parent_id = @orders, resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_DASHBOARD',
       resource_type = 'PAGE', display_name = '订单工作台', sort_order = 10,
       version = version + 1, updated_at = @changed_at
 WHERE id = @order_home;

UPDATE iam_resource
   SET parent_id = @order_center,
       resource_code = CASE id
           WHEN @order_all THEN 'SUPPLY_CHAIN.PAGE.ORDER_ALL'
           WHEN @order_pending THEN 'SUPPLY_CHAIN.PAGE.ORDER_PENDING'
           ELSE 'SUPPLY_CHAIN.PAGE.ORDER_EXCEPTION' END,
       resource_type = 'PAGE',
       display_name = CASE id
           WHEN @order_all THEN '全部订单'
           WHEN @order_pending THEN '待处理订单'
           ELSE '异常订单' END,
       sort_order = CASE id WHEN @order_all THEN 10 WHEN @order_pending THEN 20 ELSE 30 END,
       version = version + 1, updated_at = @changed_at
 WHERE id IN (@order_all,@order_pending,@order_exception);

UPDATE iam_resource_ui
   SET route_key = 'supply.order.fulfillment.menu', route_path = NULL,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_fulfillment;
UPDATE iam_resource_ui
   SET route_key = 'supply.order.after-sales.menu', route_path = NULL,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_after_sales;
UPDATE iam_resource_ui
   SET route_key = 'supply.order.center.menu', route_path = NULL,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_center;
UPDATE iam_resource_ui
   SET route_key = 'supply.order.index', route_path = '/supply-chain/order',
       icon_key = 'House', visible = 1, updated_at = @changed_at
 WHERE resource_id = @order_home;
UPDATE iam_resource_ui
   SET route_key = CASE resource_id
           WHEN @order_all THEN 'supply.order.all'
           WHEN @order_pending THEN 'supply.order.pending'
           ELSE 'supply.order.exceptions' END,
       route_path = CASE resource_id
           WHEN @order_all THEN '/supply-chain/order/all'
           WHEN @order_pending THEN '/supply-chain/order/pending'
           ELSE '/supply-chain/order/exceptions' END,
       icon_key = NULL, visible = 1, updated_at = @changed_at
 WHERE resource_id IN (@order_all,@order_pending,@order_exception);

-- 仓库实际出库属于ERP；旧统计和订货宝后台镜像页保留直达兼容，但退出正式侧栏。
UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id IN (
    @order_stock_up,
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000107'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000108'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000109'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000110'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000117'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000118')
 );

-- 外部集成只保留供应链数据控制面，不再使用供应商名称作为一级业务域。
UPDATE iam_resource
   SET display_name = CASE id
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000084') THEN '集成工作台'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000085') THEN '原始数据查询'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000086') THEN '同步任务'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000087') THEN '重试与死信'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000088') THEN '订货宝连接'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000089') THEN '字段映射'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000090') THEN '数据对账'
       WHEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000091') THEN '主权与切换状态'
       END,
       version = version + 1, updated_at = @changed_at
 WHERE id BETWEEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000084')
              AND UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');

UPDATE iam_tenant tenant_record
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1 FROM iam_role role_record
        WHERE role_record.tenant_id = tenant_record.id
          AND role_record.role_code = 'TENANT_SUPER_ADMIN'
          AND role_record.status = 'ACTIVE'
          AND role_record.deleted_at IS NULL
   );

-- Source: V20__iam_complete_supply_navigation_v21.sql
-- IAM V20：按总体架构V2.1补齐供应链业务域二三级菜单，并将Portal飞书卡片收口到销售管理后台。
-- 缺失业务API不生成模拟数据；菜单、路由和权限挂载点先保持数据库动态配置。

SET @seed_at = TIMESTAMP('2026-08-06 20:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @erp = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @crm = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @orders = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_access = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @order_fulfillment = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_after_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @city = UUID_TO_BIN('019facf2-0000-7000-8000-000000000051');
SET @bi = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @hr = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @channel = UUID_TO_BIN('019facf2-0000-7000-8000-000000000063');
SET @integration = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @settings = UUID_TO_BIN('019facf2-0000-7000-8000-000000000067');

-- 门户卡片不再建设独立PC飞书后台，统一进入供应链销售管理。
UPDATE iam_application
   SET app_type = 'INTERNAL',
       launch_mode = 'INTERNAL_ROUTE',
       target_uri = '/supply-chain/sales',
       version = version + 1,
       updated_at = @seed_at
 WHERE id = @app_feishu_sales
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_MASTER_DATA','MENU',NULL,'商品与主数据',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_PRODUCTS','PAGE',NULL,'商品/SPU',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SKUS','PAGE',NULL,'SKU',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_CATEGORIES','PAGE',NULL,'分类',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_BRANDS','PAGE',NULL,'品牌',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SPECIFICATIONS','PAGE',NULL,'规格与包装',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_SUPPLIERS','MENU',NULL,'供应商',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PROFILES','PAGE',NULL,'供应商档案',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PRODUCTS','PAGE',NULL,'供应商商品',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'SUPPLY_CHAIN.PAGE.ERP_SUPPLIERS_PRICES','PAGE',NULL,'供应商价格',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_PROCUREMENT','MENU',NULL,'采购管理',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_REQUESTS','PAGE',NULL,'采购申请',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_ORDERS','PAGE',NULL,'采购订单',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_RECEIPTS','PAGE',NULL,'到货与入库',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_RETURNS','PAGE',NULL,'采购退货',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_WAREHOUSE','MENU',NULL,'仓库作业',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_LOCATIONS','PAGE',NULL,'仓库与库位',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_INBOUND','PAGE',NULL,'入库作业',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_OUTBOUND','PAGE',NULL,'出库作业',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_TRANSFERS','PAGE',NULL,'调拨作业',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'SUPPLY_CHAIN.PAGE.ERP_WAREHOUSE_STOCKTAKING','PAGE',NULL,'盘点作业',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_INVENTORY','MENU',NULL,'库存管理',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_OVERVIEW','PAGE',NULL,'库存总览',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_AVAILABILITY','PAGE',NULL,'可用/锁定/在途库存',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_MOVEMENTS','PAGE',NULL,'库存流水',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_BATCHES','PAGE',NULL,'批次与效期',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_ALERTS','PAGE',NULL,'库存预警',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),@app_supply_chain,@erp,'SUPPLY_CHAIN.MENU.ERP_COST_SETTLEMENT','MENU',NULL,'成本与采购结算',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_PURCHASE_PRICES','PAGE',NULL,'采购价格',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_RECEIPT_COSTS','PAGE',NULL,'入库成本',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_INVENTORY_COSTS','PAGE',NULL,'库存成本',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'SUPPLY_CHAIN.PAGE.ERP_COST_SETTLEMENT_PAYABLE_BASIS','PAGE',NULL,'供应商应付依据',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),@app_supply_chain,@crm,'SUPPLY_CHAIN.MENU.CRM_CUSTOMERS','MENU',NULL,'客户与商家',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000200'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_PROFILES','PAGE',NULL,'客户/商家档案',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000201'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_STORES','PAGE',NULL,'门店档案',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000202'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_LEVELS_TAGS','PAGE',NULL,'客户等级与标签',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000203'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_CUSTOMER_360','PAGE',NULL,'客户 360',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),@app_supply_chain,@crm,'SUPPLY_CHAIN.MENU.CRM_ASSIGNMENTS','MENU',NULL,'客户归属',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000205'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_SALES','PAGE',NULL,'销售归属',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000206'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_CITY_TEAMS','PAGE',NULL,'城市与团队归属',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000207'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_HISTORY','PAGE',NULL,'归属变更记录',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),@app_supply_chain,@crm,'SUPPLY_CHAIN.MENU.CRM_CREDIT_POLICY','MENU',NULL,'信用与结算政策',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000209'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_LIMITS','PAGE',NULL,'信用额度',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000210'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_TERMS','PAGE',NULL,'账期与结算周期',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000211'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_PAYMENT_METHODS','PAGE',NULL,'付款方式',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000212'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_INVOICING','PAGE',NULL,'开票资料',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000213'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'SUPPLY_CHAIN.PAGE.CRM_CREDIT_POLICY_APPROVALS','PAGE',NULL,'政策审批记录',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),@app_supply_chain,@city,'SUPPLY_CHAIN.MENU.CITY_SCOPE','MENU',NULL,'城市与服务范围',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000215'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_PROFILES','PAGE',NULL,'城市运营档案',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000216'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_SERVICE_AREAS','PAGE',NULL,'服务区域',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000217'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_FULFILLMENT_NODES','PAGE',NULL,'履约节点',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000218'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'SUPPLY_CHAIN.PAGE.CITY_SCOPE_OWNERS','PAGE',NULL,'城市责任人',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),@app_supply_chain,@city,'SUPPLY_CHAIN.MENU.CITY_TASKS','MENU',NULL,'运营任务',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000220'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_TODOS','PAGE',NULL,'城市待办',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000221'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_FULFILLMENT_EXCEPTIONS','PAGE',NULL,'履约异常',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000222'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_CUSTOMER_EXCEPTIONS','PAGE',NULL,'客户经营异常',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000223'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'SUPPLY_CHAIN.PAGE.CITY_TASKS_ACTIVITIES','PAGE',NULL,'城市活动与复盘',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),@app_supply_chain,@city,'SUPPLY_CHAIN.MENU.CITY_CONFIGURATION','MENU',NULL,'城市配置',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000225'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'SUPPLY_CHAIN.PAGE.CITY_CONFIGURATION_TARGETS','PAGE',NULL,'城市目标',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000226'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'SUPPLY_CHAIN.PAGE.CITY_CONFIGURATION_BUDGETS','PAGE',NULL,'预算与成本配置',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000227'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'SUPPLY_CHAIN.PAGE.CITY_CONFIGURATION_PARTNERS','PAGE',NULL,'合作方配置',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000228'),@app_supply_chain,@order_access,'SUPPLY_CHAIN.PAGE.ORDER_ACCESS_BACKSTAGE','PAGE',NULL,'后台代客下单',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000229'),@app_supply_chain,@order_access,'SUPPLY_CHAIN.PAGE.ORDER_ACCESS_EXCEPTION','PAGE',NULL,'来源异常',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000230'),@app_supply_chain,@order_fulfillment,'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_OWNERSHIP','PAGE',NULL,'履约归属',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000231'),@app_supply_chain,@order_fulfillment,'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_INVENTORY','PAGE',NULL,'库存与仓库协同',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000232'),@app_supply_chain,@order_fulfillment,'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_EXCEPTION','PAGE',NULL,'履约异常',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000233'),@app_supply_chain,@order_after_sales,'SUPPLY_CHAIN.PAGE.ORDER_AFTER_SALES_EXCHANGE','PAGE',NULL,'换货与补发',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000234'),@app_supply_chain,@order_after_sales,'SUPPLY_CHAIN.PAGE.ORDER_AFTER_SALES_APPROVAL','PAGE',NULL,'售后审批',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),@app_supply_chain,@orders,'SUPPLY_CHAIN.MENU.ORDER_SETTLEMENT','MENU',NULL,'订单结算事实',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000236'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE','PAGE',NULL,'应收依据',10,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000237'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS','PAGE',NULL,'回款状态',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000238'),@app_supply_chain,UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION','PAGE',NULL,'对账差异',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000239'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_ORDERS_FULFILLMENT','PAGE',NULL,'订单与履约',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000240'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_SALES','PAGE',NULL,'销售经营',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000241'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_CITIES','PAGE',NULL,'城市经营',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000242'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_CUSTOMERS_STORES','PAGE',NULL,'客户与门店',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000243'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_PRODUCTS_PROCUREMENT_INVENTORY','PAGE',NULL,'商品、采购与库存',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000244'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_FINANCE_COLLECTIONS','PAGE',NULL,'财务与回款',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000245'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_SYNC_QUALITY','PAGE',NULL,'数据同步质量',80,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000246'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_METRICS','PAGE',NULL,'指标中心',90,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000247'),@app_supply_chain,@bi,'SUPPLY_CHAIN.PAGE.BI_DASHBOARDS','PAGE',NULL,'看板管理',100,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000248'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_ASSIGNMENTS','PAGE',NULL,'任职与调动',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000249'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_CALENDAR_POLICIES','PAGE',NULL,'工作日历与考勤政策',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000250'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_ATTENDANCE_APPEALS','PAGE',NULL,'正式考勤与申诉',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000251'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_PAYROLL_COMMISSION','PAGE',NULL,'薪酬与提成',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000252'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_PERFORMANCE','PAGE',NULL,'绩效核算',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000253'),@app_supply_chain,@hr,'SUPPLY_CHAIN.PAGE.HR_MONTHLY_CLOSE','PAGE',NULL,'月结与冲回',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000254'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_RELATIONSHIPS','PAGE',NULL,'代理关系树',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000255'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_LEVELS','PAGE',NULL,'代理等级',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000256'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_QUOTAS','PAGE',NULL,'额度与占用',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000257'),@app_supply_chain,@channel,'SUPPLY_CHAIN.PAGE.CHANNEL_APPROVALS','PAGE',NULL,'审批与释放',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000258'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_PRODUCT_INVENTORY','PAGE',NULL,'商品与库存参数',20,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000259'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_PROCUREMENT','PAGE',NULL,'采购规则',30,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000260'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_CUSTOMER_LEVELS_TAGS','PAGE',NULL,'客户等级与标签',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000261'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_CREDIT_POLICY_TEMPLATES','PAGE',NULL,'信用与结算政策模板',50,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000262'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_ORDER_AFTER_SALES','PAGE',NULL,'订单和售后规则',60,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000263'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_FULFILLMENT_ALLOCATION','PAGE',NULL,'履约分配规则',70,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000264'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_CITY_SERVICE_SCOPE','PAGE',NULL,'城市服务范围',80,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000265'),@app_supply_chain,@settings,'SUPPLY_CHAIN.PAGE.SETTINGS_NUMBERING_DICTIONARIES','PAGE',NULL,'业务编号与领域字典',90,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000266'),@app_supply_chain,@integration,'SUPPLY_CHAIN.PAGE.INTEGRATION_SYNC_BATCHES','PAGE',NULL,'同步批次与游标',40,'ACTIVE',@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000267'),@app_supply_chain,@integration,'SUPPLY_CHAIN.PAGE.INTEGRATION_EXTERNAL_ID_MAPPINGS','PAGE',NULL,'外部 ID 映射',80,'ACTIVE',@seed_at,@seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),'supply.erp.master-data.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),'supply.erp.master-data.products','/supply-chain/erp/master-data/products',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),'supply.erp.master-data.skus','/supply-chain/erp/master-data/skus',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),'supply.erp.master-data.categories','/supply-chain/erp/master-data/categories',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),'supply.erp.master-data.brands','/supply-chain/erp/master-data/brands',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),'supply.erp.master-data.specifications','/supply-chain/erp/master-data/specifications',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),'supply.erp.suppliers.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),'supply.erp.suppliers.profiles','/supply-chain/erp/suppliers/profiles',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),'supply.erp.suppliers.products','/supply-chain/erp/suppliers/products',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),'supply.erp.suppliers.prices','/supply-chain/erp/suppliers/prices',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),'supply.erp.procurement.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),'supply.erp.procurement.requests','/supply-chain/erp/procurement/requests',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),'supply.erp.procurement.orders','/supply-chain/erp/procurement/orders',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),'supply.erp.procurement.receipts','/supply-chain/erp/procurement/receipts',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),'supply.erp.procurement.returns','/supply-chain/erp/procurement/returns',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),'supply.erp.warehouse.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),'supply.erp.warehouse.locations','/supply-chain/erp/warehouse/locations',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),'supply.erp.warehouse.inbound','/supply-chain/erp/warehouse/inbound',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),'supply.erp.warehouse.outbound','/supply-chain/erp/warehouse/outbound',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),'supply.erp.warehouse.transfers','/supply-chain/erp/warehouse/transfers',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),'supply.erp.warehouse.stocktaking','/supply-chain/erp/warehouse/stocktaking',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),'supply.erp.inventory.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),'supply.erp.inventory.overview','/supply-chain/erp/inventory/overview',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),'supply.erp.inventory.availability','/supply-chain/erp/inventory/availability',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),'supply.erp.inventory.movements','/supply-chain/erp/inventory/movements',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),'supply.erp.inventory.batches','/supply-chain/erp/inventory/batches',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),'supply.erp.inventory.alerts','/supply-chain/erp/inventory/alerts',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),'supply.erp.cost-settlement.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),'supply.erp.cost-settlement.purchase-prices','/supply-chain/erp/cost-settlement/purchase-prices',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),'supply.erp.cost-settlement.receipt-costs','/supply-chain/erp/cost-settlement/receipt-costs',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),'supply.erp.cost-settlement.inventory-costs','/supply-chain/erp/cost-settlement/inventory-costs',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),'supply.erp.cost-settlement.payable-basis','/supply-chain/erp/cost-settlement/payable-basis',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000199'),'supply.crm.customers.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000200'),'supply.crm.customers.profiles','/supply-chain/crm/customers/profiles',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000201'),'supply.crm.customers.stores','/supply-chain/crm/customers/stores',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000202'),'supply.crm.customers.levels-tags','/supply-chain/crm/customers/levels-tags',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000203'),'supply.crm.customers.customer-360','/supply-chain/crm/customers/customer-360',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000204'),'supply.crm.assignments.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000205'),'supply.crm.assignments.sales','/supply-chain/crm/assignments/sales',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000206'),'supply.crm.assignments.city-teams','/supply-chain/crm/assignments/city-teams',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000207'),'supply.crm.assignments.history','/supply-chain/crm/assignments/history',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000208'),'supply.crm.credit-policy.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000209'),'supply.crm.credit-policy.limits','/supply-chain/crm/credit-policy/limits',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000210'),'supply.crm.credit-policy.terms','/supply-chain/crm/credit-policy/terms',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000211'),'supply.crm.credit-policy.payment-methods','/supply-chain/crm/credit-policy/payment-methods',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000212'),'supply.crm.credit-policy.invoicing','/supply-chain/crm/credit-policy/invoicing',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000213'),'supply.crm.credit-policy.approvals','/supply-chain/crm/credit-policy/approvals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000214'),'supply.city.scope.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000215'),'supply.city.scope.profiles','/supply-chain/city/scope/profiles',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000216'),'supply.city.scope.service-areas','/supply-chain/city/scope/service-areas',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000217'),'supply.city.scope.fulfillment-nodes','/supply-chain/city/scope/fulfillment-nodes',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000218'),'supply.city.scope.owners','/supply-chain/city/scope/owners',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000219'),'supply.city.tasks.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000220'),'supply.city.tasks.todos','/supply-chain/city/tasks/todos',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000221'),'supply.city.tasks.fulfillment-exceptions','/supply-chain/city/tasks/fulfillment-exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000222'),'supply.city.tasks.customer-exceptions','/supply-chain/city/tasks/customer-exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000223'),'supply.city.tasks.activities','/supply-chain/city/tasks/activities',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000224'),'supply.city.configuration.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000225'),'supply.city.configuration.targets','/supply-chain/city/configuration/targets',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000226'),'supply.city.configuration.budgets','/supply-chain/city/configuration/budgets',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000227'),'supply.city.configuration.partners','/supply-chain/city/configuration/partners',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000228'),'supply.order.access.backstage','/supply-chain/order/access/backstage',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000229'),'supply.order.access.exceptions','/supply-chain/order/access/exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000230'),'supply.order.fulfillment.ownership','/supply-chain/order/fulfillment/ownership',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000231'),'supply.order.fulfillment.inventory','/supply-chain/order/fulfillment/inventory',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000232'),'supply.order.fulfillment.exceptions','/supply-chain/order/fulfillment/exceptions',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000233'),'supply.order.after-sales.exchanges','/supply-chain/order/after-sales/exchanges',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000234'),'supply.order.after-sales.approvals','/supply-chain/order/after-sales/approvals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000235'),'supply.order.settlement.menu',NULL,NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000236'),'supply.order.settlement.receivable','/supply-chain/order/settlement/receivable',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000237'),'supply.order.settlement.collections','/supply-chain/order/settlement/collections',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000238'),'supply.order.settlement.reconciliation','/supply-chain/order/settlement/reconciliation',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000239'),'supply.bi.orders-fulfillment','/supply-chain/bi/orders-fulfillment',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000240'),'supply.bi.sales','/supply-chain/bi/sales',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000241'),'supply.bi.cities','/supply-chain/bi/cities',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000242'),'supply.bi.customers-stores','/supply-chain/bi/customers-stores',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000243'),'supply.bi.products-procurement-inventory','/supply-chain/bi/products-procurement-inventory',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000244'),'supply.bi.finance-collections','/supply-chain/bi/finance-collections',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000245'),'supply.bi.sync-quality','/supply-chain/bi/sync-quality',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000246'),'supply.bi.metrics','/supply-chain/bi/metrics',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000247'),'supply.bi.dashboards','/supply-chain/bi/dashboards',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000248'),'supply.hr.assignments','/supply-chain/hr/assignments',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000249'),'supply.hr.calendar-policies','/supply-chain/hr/calendar-policies',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000250'),'supply.hr.attendance-appeals','/supply-chain/hr/attendance-appeals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000251'),'supply.hr.payroll-commission','/supply-chain/hr/payroll-commission',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000252'),'supply.hr.performance','/supply-chain/hr/performance',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000253'),'supply.hr.monthly-close','/supply-chain/hr/monthly-close',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000254'),'supply.channel.relationships','/supply-chain/channel/relationships',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000255'),'supply.channel.levels','/supply-chain/channel/levels',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000256'),'supply.channel.quotas','/supply-chain/channel/quotas',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000257'),'supply.channel.approvals','/supply-chain/channel/approvals',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000258'),'supply.settings.product-inventory','/supply-chain/settings/product-inventory',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000259'),'supply.settings.procurement','/supply-chain/settings/procurement',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000260'),'supply.settings.customer-levels-tags','/supply-chain/settings/customer-levels-tags',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000261'),'supply.settings.credit-policy-templates','/supply-chain/settings/credit-policy-templates',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000262'),'supply.settings.order-after-sales','/supply-chain/settings/order-after-sales',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000263'),'supply.settings.fulfillment-allocation','/supply-chain/settings/fulfillment-allocation',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000264'),'supply.settings.city-service-scope','/supply-chain/settings/city-service-scope',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000265'),'supply.settings.numbering-dictionaries','/supply-chain/settings/numbering-dictionaries',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000266'),'supply.integration.sync-batches','/supply-chain/integration/sync-batches',NULL,1,0,@seed_at,@seed_at),
    (UUID_TO_BIN('019facf2-0000-7000-8000-000000000267'),'supply.integration.external-id-mappings','/supply-chain/integration/external-id-mappings',NULL,1,0,@seed_at,@seed_at);

-- 外部集成正式使用独立integration pathroot，不再把供应商名称暴露为技术路由边界。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 10,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.overview',
       ui_record.route_path = '/supply-chain/integration',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 20,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.connections',
       ui_record.route_path = '/supply-chain/integration/connections',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000088');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 30,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.sync-tasks',
       ui_record.route_path = '/supply-chain/integration/sync-tasks',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000086');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 50,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.field-mappings',
       ui_record.route_path = '/supply-chain/integration/field-mappings',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000089');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 60,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.retries',
       ui_record.route_path = '/supply-chain/integration/retries-dead-letters',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000087');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 70,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.reconciliation',
       ui_record.route_path = '/supply-chain/integration/reconciliation',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000090');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 90,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.raw-data',
       ui_record.route_path = '/supply-chain/integration/raw-data',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000085');
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.sort_order = 100,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @seed_at,
       ui_record.route_key = 'supply.integration.sovereignty',
       ui_record.route_path = '/supply-chain/integration/sovereignty',
       ui_record.updated_at = @seed_at
 WHERE resource_record.id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');

-- 新菜单进入现行标准套餐并授予既有租户超级管理员；普通角色仍由菜单管理按需配置。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_record.id, @seed_at, NULL
  FROM iam_resource resource_record
 WHERE resource_record.id BETWEEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000167') AND UUID_TO_BIN('019facf2-0000-7000-8000-000000000267')
ON DUPLICATE KEY UPDATE created_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resource_record.id, 'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN iam_resource resource_record
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND resource_record.id BETWEEN UUID_TO_BIN('019facf2-0000-7000-8000-000000000167') AND UUID_TO_BIN('019facf2-0000-7000-8000-000000000267')
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V21__iam_restore_feishu_sales_workbench_entry.sql
-- 飞书销售工作台是销售人员移动H5入口，不是供应链销售管理后台。
-- 保留Portal受控启动页，由启动页打开独立Workbench；真实飞书地址通过前端环境配置注入。
SET @seed_at = CURRENT_TIMESTAMP(6);

UPDATE iam_application
   SET app_type = 'EXTERNAL',
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       version = version + 1,
       updated_at = @seed_at
 WHERE app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V22__iam_tenant_menu_configuration.sql
-- 租户菜单配置层：平台资源目录仍是唯一功能主数据，租户只能覆盖展示属性并创建无路由分组。
-- 最终导航边界：有效资源 ∩ 套餐资源 ∩ 租户启用配置 ∩ 用户角色授权。

CREATE TABLE iam_tenant_menu_group (
    id             BINARY(16)      NOT NULL,
    tenant_id      BINARY(16)      NOT NULL,
    application_id BINARY(16)      NOT NULL,
    parent_id      BINARY(16)      NULL,
    group_code     VARCHAR(128)    NOT NULL,
    display_name   VARCHAR(128)    NOT NULL,
    icon_key       VARCHAR(128)    NULL,
    sort_order     INT             NOT NULL DEFAULT 0,
    visible        TINYINT UNSIGNED NOT NULL DEFAULT 1,
    status         VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at     DATETIME(6)     NOT NULL,
    created_by     BINARY(16)      NULL,
    updated_at     DATETIME(6)     NOT NULL,
    updated_by     BINARY(16)      NULL,
    deleted_at     DATETIME(6)     NULL,
    deleted_by     BINARY(16)      NULL,
    delete_reason  VARCHAR(255)    NULL,
    CONSTRAINT pk_iam_tenant_menu_group PRIMARY KEY (id),
    CONSTRAINT uk_iam_tenant_menu_group_code UNIQUE (tenant_id, application_id, group_code),
    CONSTRAINT uk_iam_tenant_menu_group_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_tenant_menu_group_visible CHECK (visible IN (0, 1)),
    CONSTRAINT ck_iam_tenant_menu_group_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_tenant_menu_group_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_tenant_menu_group_application FOREIGN KEY (application_id)
        REFERENCES iam_application (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_tenant_menu_group_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES iam_tenant_menu_group (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_tenant_menu_group_tree (tenant_id, application_id, parent_id, status, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户自定义无路由菜单分组';

CREATE TABLE iam_tenant_menu_config (
    tenant_id             BINARY(16)       NOT NULL,
    resource_id           BINARY(16)       NOT NULL,
    display_name_override VARCHAR(128)     NULL,
    icon_key_override     VARCHAR(128)     NULL,
    sort_order_override   INT              NULL,
    parent_group_id       BINARY(16)       NULL,
    visible               TINYINT UNSIGNED NOT NULL DEFAULT 0,
    version               BIGINT UNSIGNED  NOT NULL DEFAULT 0,
    created_at            DATETIME(6)      NOT NULL,
    created_by            BINARY(16)       NULL,
    updated_at            DATETIME(6)      NOT NULL,
    updated_by            BINARY(16)       NULL,
    CONSTRAINT pk_iam_tenant_menu_config PRIMARY KEY (tenant_id, resource_id),
    CONSTRAINT ck_iam_tenant_menu_config_visible CHECK (visible IN (0, 1)),
    CONSTRAINT fk_iam_tenant_menu_config_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_tenant_menu_config_resource FOREIGN KEY (resource_id)
        REFERENCES iam_resource (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_tenant_menu_config_group FOREIGN KEY (tenant_id, parent_group_id)
        REFERENCES iam_tenant_menu_group (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_tenant_menu_config_group (tenant_id, parent_group_id, visible)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户对套餐内平台菜单的展示覆盖配置';

SET @seed_at = CURRENT_TIMESTAMP(6);
SET @app_system_admin = UUID_TO_BIN('019facf1-0000-7000-8000-000000000002');
SET @system_setting_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000071');
SET @menu_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000268');
SET @menu_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000269');
SET @menu_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000270');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@menu_page, @app_system_admin, @system_setting_menu, 'SYSTEM_ADMIN.PAGE.MENU_CONFIG', 'PAGE', NULL,
     '菜单管理', 20, 'ACTIVE', @seed_at, @seed_at),
    (@menu_read, @app_system_admin, @menu_page, 'SYSTEM_ADMIN.API.MENU_READ', 'API', 'iam:menu:read',
     '查询租户菜单配置', 10, 'ACTIVE', @seed_at, @seed_at),
    (@menu_write, @app_system_admin, @menu_page, 'SYSTEM_ADMIN.API.MENU_WRITE', 'API', 'iam:menu:write',
     '维护租户菜单配置', 20, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@menu_page, 'system.menu-config.list', '/system-admin/menus', 'Menu', 1, 0, @seed_at, @seed_at);

-- 当前标准套餐包含菜单配置能力；租户超级管理员自动获得，普通角色仍需显式授权。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @menu_page AS resource_id UNION ALL SELECT @menu_read UNION ALL SELECT @menu_write
) added_resources;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
  CROSS JOIN (
      SELECT @menu_page AS resource_id UNION ALL SELECT @menu_read UNION ALL SELECT @menu_write
  ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

-- 迁移前已在使用的菜单保持原显示状态；以后新订阅的菜单由订阅流程创建为默认关闭。
INSERT INTO iam_tenant_menu_config (
    tenant_id, resource_id, visible, created_at, created_by, updated_at, updated_by
)
SELECT DISTINCT subscription.tenant_id, resource_record.id, resource_ui.visible,
       @seed_at, NULL, @seed_at, NULL
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
   AND resource_record.resource_type IN ('MENU', 'PAGE')
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE deleted_at IS NULL;

-- Source: V23__iam_sales_h5_capabilities.sql
-- IAM V23：销售H5纵向切片所需的最小API权限。
-- 不复用或改写V18历史资源；管理后台权限和H5作业权限分开维护。

SET @seed_at = TIMESTAMP('2026-08-06 19:00:00.000000');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @h5_context_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000271');
SET @h5_target_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000272');
SET @h5_work_day_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000273');
SET @h5_location_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000274');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_context_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.CONTEXT_READ', 'API', 'sales:context:read', '读取销售作业上下文', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_target_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_TARGET_READ', 'API', 'sales:visit-target:read', '读取销售拜访目标', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_work_day_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.WORK_DAY_WRITE', 'API', 'sales:work-day:write', '执行销售签到签退', 30,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_location_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.LOCATION_WRITE', 'API', 'sales:location:write', '提交销售定位证据', 40,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_context_read AS resource_id
    UNION ALL SELECT @h5_target_read
    UNION ALL SELECT @h5_work_day_write
    UNION ALL SELECT @h5_location_write
) added_resources;

-- 现有租户超级管理员可用于联调；普通销售角色仍需按租户权限流程显式授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_context_read AS resource_id
    UNION ALL SELECT @h5_target_read
    UNION ALL SELECT @h5_work_day_write
    UNION ALL SELECT @h5_location_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Source: V24__iam_restore_order_fulfillment_stock_up.sql
-- IAM V24：将既有“出库/发货”资源重新挂回订单管理 → 履约编排。
-- 不新增资源、不改路由路径，复用V12/V19的稳定资源ID和权限关系。

SET @changed_at = TIMESTAMP('2026-08-07 16:00:00.000000');
SET @orders = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_fulfillment = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_stock_up = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');

UPDATE iam_resource
   SET parent_id = @order_fulfillment,
       resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_STOCK_UP',
       resource_type = 'PAGE',
       display_name = '出库/发货',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_stock_up
   AND application_id = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003')
   AND parent_id = @orders;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.stock-up',
       route_path = '/supply-chain/order/stock-up',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @order_stock_up;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V25__iam_enable_order_fulfillment_stock_up_for_tenants.sql
-- IAM V25：补齐既有租户的“出库/发货”菜单配置。
-- V24恢复了平台资源，但V22已经把该资源复制为租户默认 visible=0；导航接口仍会因此过滤掉菜单。

SET @changed_at = TIMESTAMP('2026-08-07 16:40:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_stock_up = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');

-- 兼容V24尚未在目标环境执行的情况，确保平台资源和前端路由仍然一致。
UPDATE iam_resource
   SET parent_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106'),
       resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_FULFILLMENT_STOCK_UP',
       resource_type = 'PAGE',
       display_name = '出库/发货',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_stock_up
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.stock-up',
       route_path = '/supply-chain/order/stock-up',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @order_stock_up;

-- 确保标准套餐仍包含该稳定资源，避免导航查询因套餐关联缺失而过滤。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @order_stock_up, @changed_at, NULL
 WHERE NOT EXISTS (
     SELECT 1 FROM iam_package_resource
      WHERE package_version_id = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
        AND resource_id = @order_stock_up
 );

-- 既有租户超级管理员继续保有该页面资源授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, @order_stock_up, 'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_stock_up
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- 导航接口要求租户配置可见；只处理当前有效套餐，不影响已被租户主动关闭的其他菜单。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @order_stock_up, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_stock_up
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE visible = 1, updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V26__iam_rename_order_settlement_menu.sql
-- IAM V26：将订单结算菜单调整为财务用户更容易理解的名称。
-- “对账差异”保留为财务对账后的异常结果页；复用既有资源和路由，不删除“回款状态”资源，避免影响既有授权和书签。

SET @changed_at = TIMESTAMP('2026-08-07 17:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');

UPDATE iam_resource
   SET display_name = CASE resource_code
           WHEN 'SUPPLY_CHAIN.MENU.ORDER_SETTLEMENT' THEN '订单结算管理'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE' THEN '应收依据'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN '对账差异'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN '收付记录'
           ELSE display_name
       END,
       sort_order = CASE resource_code
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE' THEN 10
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN 20
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN 30
           ELSE sort_order
       END,
       version = version + 1,
       updated_at = @changed_at
 WHERE application_id = @app_supply_chain
   AND resource_code IN (
       'SUPPLY_CHAIN.MENU.ORDER_SETTLEMENT',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS'
   );

-- 使已登录租户重新读取最新导航；租户自定义 display_name_override 不被覆盖。
UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V27__iam_split_order_settlement_reconciliation_menu.sql
-- IAM V27：将订单结算管理下的“财务对账”和“对账差异”拆分为两个菜单入口。
-- 本迁移只维护菜单资源、路由和既有租户授权，不创建财务对账业务表，也不接入财务系统。

SET @changed_at = TIMESTAMP('2026-08-07 18:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_settlement = UUID_TO_BIN('019facf2-0000-7000-8000-000000000235');
SET @order_settlement_differences = UUID_TO_BIN('019facf2-0000-7000-8000-000000000275');

-- 原“对账差异”路由保留，调整为财务人工对账入口；收付记录顺延到第三项。
UPDATE iam_resource
   SET display_name = CASE resource_code
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN '财务对账'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN '收付记录'
           ELSE display_name
       END,
       sort_order = CASE resource_code
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN 20
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN 30
           ELSE sort_order
       END,
       version = version + 1,
       updated_at = @changed_at
 WHERE application_id = @app_supply_chain
   AND resource_code IN (
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS'
   );

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @order_settlement_differences,
    @app_supply_chain,
    @order_settlement,
    'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_DIFFERENCES',
    'PAGE',
    NULL,
    '对账差异',
    40,
    'ACTIVE',
    @changed_at,
    @changed_at
);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @order_settlement_differences,
    'supply.order.settlement.differences',
    '/supply-chain/order/settlement/differences',
    NULL,
    1,
    0,
    @changed_at,
    @changed_at
);

-- 新页面进入标准套餐，并授予既有租户超级管理员访问权。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),
       @order_settlement_differences,
       @changed_at,
       NULL
 WHERE NOT EXISTS (
     SELECT 1
       FROM iam_package_resource
      WHERE package_version_id = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
        AND resource_id = @order_settlement_differences
 );

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id,
       role_record.id,
       @order_settlement_differences,
       'ACTIVE',
       @changed_at,
       @changed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_settlement_differences
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- 新页面默认在当前有效租户中可见；不覆盖租户已有的展示名、图标或排序覆盖。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id,
       @order_settlement_differences,
       1,
       @changed_at,
       @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
   AND package_resource.resource_id = @order_settlement_differences
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE visible = 1, updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V28__iam_nest_order_settlement_collection_menu.sql
-- IAM V28：将“收付记录”调整为可展开菜单，并增加收款单、付款单页面。
-- 只调整导航资源和授权，不新增财务业务能力；收付记录作为菜单分组，不再作为可点击页面。

SET @changed_at = TIMESTAMP('2026-08-07 18:20:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @order_settlement = UUID_TO_BIN('019facf2-0000-7000-8000-000000000235');
SET @order_settlement_collections = UUID_TO_BIN('019facf2-0000-7000-8000-000000000237');
SET @order_settlement_receipts = UUID_TO_BIN('019facf2-0000-7000-8000-000000000276');
SET @order_settlement_payments = UUID_TO_BIN('019facf2-0000-7000-8000-000000000277');

-- 保留原资源编码，将“收付记录”从页面调整为无路由的可展开菜单。
UPDATE iam_resource
   SET resource_type = 'MENU',
       display_name = '收付记录',
       sort_order = 30,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_settlement_collections
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.settlement.collections',
       route_path = NULL,
       updated_at = @changed_at
 WHERE resource_id = @order_settlement_collections;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@order_settlement_receipts, @app_supply_chain, @order_settlement_collections,
     'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIPTS', 'PAGE', NULL, '收款单', 10, 'ACTIVE', @changed_at, @changed_at),
    (@order_settlement_payments, @app_supply_chain, @order_settlement_collections,
     'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_PAYMENTS', 'PAGE', NULL, '付款单', 20, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@order_settlement_receipts, 'supply.order.settlement.receipts',
     '/supply-chain/order/settlement/receipts', NULL, 1, 0, @changed_at, @changed_at),
    (@order_settlement_payments, 'supply.order.settlement.payments',
     '/supply-chain/order/settlement/payments', NULL, 1, 0, @changed_at, @changed_at);

-- 新页面进入标准套餐。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), added_resource.resource_id, @changed_at, NULL
  FROM (
      SELECT @order_settlement_receipts AS resource_id
      UNION ALL SELECT @order_settlement_payments
  ) added_resource
 WHERE NOT EXISTS (
     SELECT 1
       FROM iam_package_resource package_resource
      WHERE package_resource.package_version_id = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002')
        AND package_resource.resource_id = added_resource.resource_id
 );

-- 既有租户超级管理员继续获得新页面访问权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN (
      SELECT @order_settlement_receipts AS resource_id
      UNION ALL SELECT @order_settlement_payments
  ) added_resource ON added_resource.resource_id = package_resource.resource_id
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, added_resource.resource_id,
       1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN (
      SELECT @order_settlement_receipts AS resource_id
      UNION ALL SELECT @order_settlement_payments
  ) added_resource ON added_resource.resource_id = package_resource.resource_id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE visible = 1, updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V29__iam_sales_h5_visit_capabilities.sql
-- IAM V29：销售H5拜访纵向切片所需的API权限。
-- 附近门店（高德）、创建拜访/签退、本人拜访查询；沿用V23的H5应用与标准套餐，不改历史迁移。

SET @seed_at = TIMESTAMP('2026-08-08 12:00:00.000000');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @h5_visit_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000278');
SET @h5_visit_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000279');
SET @h5_poi_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027a');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_visit_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_READ', 'API', 'sales:visit:own:read', '查询本人拜访', 50,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_visit_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_WRITE', 'API', 'sales:visit:own:write', '创建拜访和签退', 60,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_poi_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.POI_READ', 'API', 'sales:poi:read', '查询附近门店', 70,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_visit_read AS resource_id
    UNION ALL SELECT @h5_visit_write
    UNION ALL SELECT @h5_poi_read
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- 现有租户超级管理员用于联调；普通销售角色仍需按租户权限流程显式授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_visit_read AS resource_id
    UNION ALL SELECT @h5_visit_write
    UNION ALL SELECT @h5_poi_read
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Source: V30__iam_sales_admin_and_track_capabilities.sql
-- IAM V30：销售管理维护 API 与 H5 本人轨迹查询权限。
-- 沿用 V18/V29 的资源与套餐模式，不改写历史迁移；普通角色仍需按租户权限流程显式授权。

SET @seed_at = TIMESTAMP('2026-08-09 12:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @r133 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000133');
SET @r139 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000139');
SET @r141 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000141');
SET @h5_track_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027b');
SET @admin_profile_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027c');
SET @admin_identity_bind = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027d');
SET @admin_store_projection_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027e');
SET @admin_assignment_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000027f');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_track_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.TRACK_READ', 'API', 'sales:track:own:read', '查询本人当日轨迹', 80,
     'ACTIVE', @seed_at, @seed_at),
    (@admin_profile_write, @app_supply_chain, @r139,
     'SUPPLY_CHAIN.API.SALES_PROFILE_WRITE', 'API', 'sales:profile:write', '维护销售画像', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@admin_identity_bind, @app_supply_chain, @r141,
     'SUPPLY_CHAIN.API.SALES_IDENTITY_BIND', 'API', 'sales:identity:bind', '绑定销售身份', 20,
     'ACTIVE', @seed_at, @seed_at),
    (@admin_store_projection_write, @app_supply_chain, @r133,
     'SUPPLY_CHAIN.API.SALES_STORE_PROJECTION_WRITE', 'API', 'sales:store-projection:write',
     '维护门店投影（临时前置）', 20, 'ACTIVE', @seed_at, @seed_at),
    (@admin_assignment_write, @app_supply_chain, @r133,
     'SUPPLY_CHAIN.API.SALES_ASSIGNMENT_WRITE', 'API', 'sales:assignment:write',
     '维护门店归属（临时前置）', 30, 'ACTIVE', @seed_at, @seed_at);

-- 新资源进入现行标准套餐；历史套餐版本不改字段，只补充资源关系。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_track_read AS resource_id
    UNION ALL SELECT @admin_profile_write
    UNION ALL SELECT @admin_identity_bind
    UNION ALL SELECT @admin_store_projection_write
    UNION ALL SELECT @admin_assignment_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- 现有租户超级管理员用于联调；普通销售与管理角色仍需按租户权限流程显式授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_track_read AS resource_id
    UNION ALL SELECT @admin_profile_write
    UNION ALL SELECT @admin_identity_bind
    UNION ALL SELECT @admin_store_projection_write
    UNION ALL SELECT @admin_assignment_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

-- Source: V31__iam_sales_h5_recording_capabilities.sql
-- IAM V31：销售H5拜访录音采集权限（上传片段、查询本人录音会话）。
-- 沿用 V29/V30 的资源与套餐模式，不改写历史迁移。

SET @seed_at = TIMESTAMP('2026-08-09 12:30:00.000000');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @h5_recording_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000280');
SET @h5_recording_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000281');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_recording_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.RECORDING_READ', 'API', 'sales:recording:own:read', '查询本人拜访录音', 90,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_recording_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.RECORDING_WRITE', 'API', 'sales:recording:own:write', '上传拜访录音片段', 100,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_recording_read AS resource_id
    UNION ALL SELECT @h5_recording_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @h5_recording_read AS resource_id
    UNION ALL SELECT @h5_recording_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

-- Source: V32__iam_complete_sales_h5_role_capabilities.sql
-- IAM V32：为已经具备销售 H5 拜访写权限的普通销售角色补齐轨迹与录音能力。
-- V30/V31 只自动授权了租户超级管理员，导致已能发起拜访的普通销售在查询录音/轨迹时返回 403。
-- 以既有 sales:visit:own:write 授权作为销售 H5 角色判据，不按角色名称猜测，也不授予任何管理端权限。

SET @seed_at = TIMESTAMP('2026-08-09 14:00:00.000000');

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id,
       source_grant.role_id,
       target_resource.id,
       'ACTIVE',
       @seed_at,
       @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_role role_record
    ON role_record.tenant_id = source_grant.tenant_id
   AND role_record.id = source_grant.role_id
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
  JOIN iam_resource source_resource
    ON source_resource.id = source_grant.resource_id
   AND source_resource.permission_code = 'sales:visit:own:write'
   AND source_resource.status = 'ACTIVE'
 CROSS JOIN iam_resource target_resource
 WHERE source_grant.status = 'ACTIVE'
   AND target_resource.status = 'ACTIVE'
   AND target_resource.permission_code IN (
       'sales:track:own:read',
       'sales:recording:own:read',
       'sales:recording:own:write'
   )
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

-- 令既有会话权限版本失效；用户重新进入飞书工作台后由 IAM 签发包含新权限的会话。
UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Source: V33__iam_erp_product_master_data_permissions.sql
-- IAM V33：ERP 商品主数据本地查询与订货宝同步权限。

SET @seed_at = TIMESTAMP('2026-08-10 07:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @erp_master_data_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @erp_sku_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');
SET @erp_product_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000282');
SET @erp_product_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000283');
SET @erp_product_tags_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

-- 一期菜单收口为商品中心的独立子页。
UPDATE iam_resource
   SET display_name = '商品中心', updated_at = @seed_at
 WHERE id = @erp_master_data_menu;

UPDATE iam_resource
   SET display_name = '商品档案', updated_at = @seed_at
 WHERE id = @erp_product_page;

UPDATE iam_resource
   SET display_name = '商品规格（SKU）', status = 'ACTIVE', updated_at = @seed_at
 WHERE id = @erp_sku_page;

UPDATE iam_resource_ui
   SET visible = 1, updated_at = @seed_at
 WHERE resource_id = @erp_sku_page;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@erp_product_read, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.API.ERP_PRODUCT_READ', 'API', 'erp:product:read',
     '查询ERP商品主数据', 10, 'ACTIVE', @seed_at, @seed_at),
    (@erp_product_write, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.API.ERP_PRODUCT_WRITE', 'API', 'erp:product:write',
     '同步订货宝商品主数据', 20, 'ACTIVE', @seed_at, @seed_at),
    (@erp_product_tags_page, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_TAGS', 'PAGE', NULL,
     '商品标签', 60, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @erp_product_tags_page, 'supply.erp.master-data.tags',
    '/supply-chain/erp/master-data/tags', NULL, 1, 0, @seed_at, @seed_at
);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @erp_product_read AS resource_id
    UNION ALL SELECT @erp_product_write
    UNION ALL SELECT @erp_product_tags_page
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @erp_product_read AS resource_id
    UNION ALL SELECT @erp_product_write
    UNION ALL SELECT @erp_product_tags_page
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V34__iam_align_erp_product_center_menu.sql
-- IAM V34：将 ERP 商品中心收口为商品档案、商品属性和数据同步三个子入口。
-- SKU 不再作为独立菜单页，商品档案详情通过 ERP SKU 查询接口展示 SKU。

SET @seed_at = TIMESTAMP('2026-08-10 09:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @erp_master_data_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @erp_sku_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');
SET @erp_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@erp_attributes_menu, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.MENU.ERP_MASTER_DATA_ATTRIBUTES', 'MENU', NULL,
     '商品属性', 20, 'ACTIVE', @seed_at, @seed_at),
    (@erp_sync_page, @app_supply_chain, @erp_master_data_menu,
     'SUPPLY_CHAIN.PAGE.ERP_MASTER_DATA_SYNC', 'PAGE', NULL,
     '数据同步', 30, 'ACTIVE', @seed_at, @seed_at);

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品分类', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品品牌', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品规格', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');

UPDATE iam_resource
   SET parent_id = @erp_attributes_menu, display_name = '商品标签', updated_at = @seed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

UPDATE iam_resource
   SET status = 'DISABLED', updated_at = @seed_at
 WHERE id = @erp_sku_page;

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @seed_at
 WHERE resource_id = @erp_sku_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.categories',
       route_path = '/supply-chain/erp/master-data/attributes/categories',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.brands',
       route_path = '/supply-chain/erp/master-data/attributes/brands',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.specifications',
       route_path = '/supply-chain/erp/master-data/attributes/specifications',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.attributes.tags',
       route_path = '/supply-chain/erp/master-data/attributes/tags',
       updated_at = @seed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@erp_attributes_menu, 'supply.erp.master-data.attributes.menu', NULL, NULL, 1, 0, @seed_at, @seed_at),
    (@erp_sync_page, 'supply.erp.master-data.sync',
     '/supply-chain/erp/master-data/sync', NULL, 1, 0, @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES
    (UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @erp_attributes_menu, @seed_at, NULL),
    (UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @erp_sync_page, @seed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @erp_attributes_menu AS resource_id
    UNION ALL SELECT @erp_sync_page
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V35__iam_backfill_erp_product_center_menu_config.sql
-- IAM V35：为 V34 新增的 ERP 商品中心菜单补齐既有租户默认配置。
-- V22 之后新增资源不会自动产生 iam_tenant_menu_config，导航查询的 INNER JOIN
-- 会因此过滤新菜单及其子页面；本迁移只补缺失配置，不覆盖租户已有的隐藏设置。

SET @changed_at = TIMESTAMP('2026-08-10 09:30:00.000000');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');
SET @erp_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');
SET @erp_tags_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resource_record.id,
       resource_ui.visible, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
  JOIN iam_resource_ui resource_ui
    ON resource_ui.resource_id = resource_record.id
  JOIN (
      SELECT @erp_attributes_menu AS resource_id
      UNION ALL SELECT @erp_sync_page
      UNION ALL SELECT @erp_tags_page
  ) added_resources
    ON added_resources.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V36__iam_erp_supply_data_pages_and_permissions.sql
-- IAM V36：一期只开放已实现的供应商、采购单、采购退货、仓库、入库单和库存余额子页。

SET @changed_at = TIMESTAMP('2026-08-10 12:45:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @erp = UUID_TO_BIN('019facf2-0000-7000-8000-000000000166');
SET @supply_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000287');
SET @supply_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000288');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@supply_read, @app_supply_chain, @erp, 'SUPPLY_CHAIN.API.ERP_SUPPLY_READ',
     'API', 'erp:supply:read', '查询ERP采购与库存数据', 30, 'ACTIVE', @changed_at, @changed_at),
    (@supply_write, @app_supply_chain, @erp, 'SUPPLY_CHAIN.API.ERP_SUPPLY_WRITE',
     'API', 'erp:supply:write', '同步订货宝采购与库存数据', 40, 'ACTIVE', @changed_at, @changed_at);

-- 使用 V20 已建立的稳定资源 ID，不重复创建菜单。
UPDATE iam_resource SET display_name='供应商管理', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000173');
UPDATE iam_resource SET display_name='供应商档案', status='ACTIVE', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000174');
UPDATE iam_resource SET display_name='采购单', status='ACTIVE', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000179');
UPDATE iam_resource SET display_name='采购退货', status='ACTIVE', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000181');
UPDATE iam_resource SET display_name='仓储管理', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
UPDATE iam_resource SET display_name='仓库档案', status='ACTIVE', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource SET display_name='入库单', status='ACTIVE', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource SET display_name='库存余额', status='ACTIVE', updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');

-- 没有一期接口能力的页面不在菜单中伪装可用。
UPDATE iam_resource_ui SET visible=0, updated_at=@changed_at
 WHERE resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000198')
 );

UPDATE iam_resource_ui SET visible=1, updated_at=@changed_at
 WHERE resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189')
 );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @changed_at, NULL
FROM (SELECT @supply_read AS resource_id UNION ALL SELECT @supply_write) resources
ON DUPLICATE KEY UPDATE created_at=created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (SELECT @supply_read AS resource_id UNION ALL SELECT @supply_write) resources
 WHERE role_record.role_code='TENANT_SUPER_ADMIN'
   AND role_record.role_type='SYSTEM'
   AND role_record.status='ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@changed_at;

-- 既有租户菜单配置与资源 UI 同步；只影响本期明确支持或明确隐藏的页面。
UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id=config.resource_id
   SET config.visible=ui.visible, config.updated_at=@changed_at
 WHERE config.resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000198')
 );

UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V37__iam_integrate_erp_menu_and_placeholders.sql
-- IAM V37：按 ERP Portal 菜单方案统一展示已实现页面与接口预占页面。
-- 本迁移只调整菜单层级、展示名称和可见性，不新增 ERP 业务处理，也不生成模拟数据。

SET @changed_at = TIMESTAMP('2026-08-11 17:30:00.000000');
SET @erp_master_data_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');

-- 商品/SPU、SKU、分类、品牌和规格统一挂在“商品与主数据”下；保留已有 route_key，避免 Portal 深链接失效。
UPDATE iam_resource
   SET display_name = '商品与主数据', updated_at = @changed_at
 WHERE id = @erp_master_data_menu;

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '商品/SPU', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, status = 'ACTIVE', display_name = 'SKU', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '分类', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '品牌', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '规格与包装', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '商品标签', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');

UPDATE iam_resource
   SET parent_id = @erp_master_data_menu, display_name = '数据同步', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');

-- 商品属性是旧的中间分组，保留资源以兼容历史授权，但不再作为 Portal 菜单节点展示。
UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_attributes_menu;

-- 供应商、采购、仓库、库存和成本结算使用截图中的业务名称。
UPDATE iam_resource SET display_name = '供应商', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000173');
UPDATE iam_resource SET display_name = '采购订单', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000179');
UPDATE iam_resource SET display_name = '到货与入库', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000180');
UPDATE iam_resource SET display_name = '仓库作业', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
UPDATE iam_resource SET display_name = '仓库与库位', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource SET display_name = '入库作业', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource SET display_name = '库存总览', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');

-- 已接入 ERP 本地查询/同步的页面，以及暂未接入接口但需要提前占位的页面。
UPDATE iam_resource_ui
   SET visible = 1, updated_at = @changed_at
 WHERE resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000284'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000286')
 );

-- 同步既有租户配置；新租户由订阅初始化流程按 iam_resource_ui.visible 获取默认值。
UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id = config.resource_id
   SET config.visible = ui.visible, config.updated_at = @changed_at
 WHERE config.resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000168'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000169'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000170'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000171'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000172'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000173'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000174'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000175'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000176'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000177'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000181'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000182'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000188'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000194'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000195'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000196'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000197'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000198'),
    @erp_attributes_menu,
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000284'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000286')
 );

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V38__iam_hide_erp_product_sync_menu.sql
-- IAM V38：移除 ERP 商品主数据下的“数据同步”业务菜单入口。
-- 同步能力与资源权限保留，仅不再作为 ERP 业务菜单展示。

SET @changed_at = TIMESTAMP('2026-08-11 17:45:00.000000');
SET @erp_product_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_product_sync_page;

UPDATE iam_tenant_menu_config
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_product_sync_page;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V39__iam_sales_visit_photo_evidence_capabilities.sql
-- IAM V39：销售本人现场门头照读写与主管敏感证据查看权限。
-- 本人能力跟随既有 sales:visit:own:write 角色；主管查看跟随 sales:visit:review，避免按角色名猜测。

SET @seed_at = TIMESTAMP('2026-08-11 11:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @evidence_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000149');
SET @h5_evidence_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000289');
SET @h5_evidence_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028a');
SET @management_evidence_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028b');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_evidence_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.EVIDENCE_READ', 'API', 'sales:evidence:own:read', '查询本人拜访照片证据', 110,
     'ACTIVE', @seed_at, @seed_at),
    (@h5_evidence_write, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.EVIDENCE_WRITE', 'API', 'sales:evidence:own:write', '拍摄并上传拜访门头照', 120,
     'ACTIVE', @seed_at, @seed_at),
    (@management_evidence_read, @app_supply_chain, @evidence_page,
     'SUPPLY_CHAIN.API.SALES_EVIDENCE_READ', 'API', 'sales:evidence:sensitive:read', '查看拜访照片证据', 20,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_evidence_read AS resource_id
    UNION ALL SELECT @h5_evidence_write
    UNION ALL SELECT @management_evidence_read
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, target_resource.id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id = source_grant.resource_id
   AND source_resource.permission_code = 'sales:visit:own:write'
   AND source_resource.status = 'ACTIVE'
 CROSS JOIN iam_resource target_resource
 WHERE source_grant.status = 'ACTIVE'
   AND target_resource.status = 'ACTIVE'
   AND target_resource.permission_code IN ('sales:evidence:own:read', 'sales:evidence:own:write')
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, @management_evidence_read,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id = source_grant.resource_id
   AND source_resource.permission_code = 'sales:visit:review'
   AND source_resource.status = 'ACTIVE'
 WHERE source_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Source: V40__iam_sales_visit_plan_capabilities.sql
-- IAM V40：主管维护拜访计划，销售本人读取自己的今日计划。

SET @seed_at = TIMESTAMP('2026-08-11 12:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @app_feishu_sales = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @feishu_sales_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @visit_plan_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000128');
SET @h5_plan_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028c');
SET @management_plan_read = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028d');
SET @management_plan_write = UUID_TO_BIN('019facf2-0000-7000-8000-00000000028e');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@h5_plan_read, @app_feishu_sales, @feishu_sales_root,
     'FEISHU_SALES.API.VISIT_PLAN_READ', 'API', 'sales:visit-plan:own:read', '查询本人今日拜访计划', 130,
     'ACTIVE', @seed_at, @seed_at),
    (@management_plan_read, @app_supply_chain, @visit_plan_page,
     'SUPPLY_CHAIN.API.SALES_VISIT_PLAN_READ', 'API', 'sales:visit-plan:read', '查询拜访计划', 10,
     'ACTIVE', @seed_at, @seed_at),
    (@management_plan_write, @app_supply_chain, @visit_plan_page,
     'SUPPLY_CHAIN.API.SALES_VISIT_PLAN_WRITE', 'API', 'sales:visit-plan:write', '维护拜访计划', 20,
     'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @h5_plan_read AS resource_id
    UNION ALL SELECT @management_plan_read
    UNION ALL SELECT @management_plan_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, @h5_plan_read,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id=source_grant.resource_id
   AND source_resource.permission_code='sales:visit:own:read'
   AND source_resource.status='ACTIVE'
 WHERE source_grant.status='ACTIVE'
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT source_grant.tenant_id, source_grant.role_id, target_resource.id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource source_grant
  JOIN iam_resource source_resource
    ON source_resource.id=source_grant.resource_id
   AND source_resource.permission_code='sales:visit:review'
   AND source_resource.status='ACTIVE'
 CROSS JOIN iam_resource target_resource
 WHERE source_grant.status='ACTIVE'
   AND target_resource.status='ACTIVE'
   AND target_resource.permission_code IN ('sales:visit-plan:read','sales:visit-plan:write')
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version=policy_version+1, version=version+1, updated_at=@seed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V41__iam_hide_order_access_navigation.sql
-- IAM V41：订单管理不再展示“订单接入”及其子菜单。
-- 只退出正式导航，保留稳定资源和直达路由，避免在替代能力尚未完整落地前破坏历史授权与兼容入口。

SET @changed_at = TIMESTAMP('2026-08-12 18:30:00.000000');
SET @order_access = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @order_access_backstage = UUID_TO_BIN('019facf2-0000-7000-8000-000000000228');
SET @order_access_exception = UUID_TO_BIN('019facf2-0000-7000-8000-000000000229');

-- 平台默认导航隐藏，后续新租户不会再看到该分组。
UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@order_access, @order_access_backstage, @order_access_exception);

-- 已有租户可能保存过 visible=1 的覆盖配置，需要同步收口。
UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@order_access, @order_access_backstage, @order_access_exception);

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V42__iam_crm_customer_permissions.sql
-- IAM V42：CRM 客户本地查询与订货宝同步权限。

SET @seed_at = TIMESTAMP('2026-08-12 16:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_customer_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000290');
SET @crm_customer_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000291');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@crm_customer_read, @app_supply_chain, @crm_customers_menu,
     'SUPPLY_CHAIN.API.CRM_CUSTOMER_READ', 'API', 'crm:customer:read',
     '查询CRM客户主数据', 10, 'ACTIVE', @seed_at, @seed_at),
    (@crm_customer_write, @app_supply_chain, @crm_customers_menu,
     'SUPPLY_CHAIN.API.CRM_CUSTOMER_WRITE', 'API', 'crm:customer:write',
     '同步订货宝CRM客户主数据', 20, 'ACTIVE', @seed_at, @seed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @seed_at, NULL
FROM (
    SELECT @crm_customer_read AS resource_id
    UNION ALL SELECT @crm_customer_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @crm_customer_read AS resource_id
    UNION ALL SELECT @crm_customer_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@seed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Source: V43__iam_hide_crm_customer_360_navigation.sql
-- IAM V43：CRM 不再展示“客户 360”子菜单。
-- 只退出正式导航，保留稳定资源与历史授权，避免破坏已发放权限关系。

SET @changed_at = TIMESTAMP('2026-08-13 10:10:00.000000');
SET @crm_customer_360 = UUID_TO_BIN('019facf2-0000-7000-8000-000000000203');

-- 平台默认导航隐藏，后续新租户不会再看到该页面。
UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_360;

-- 已有租户可能保存过 visible=1 的覆盖配置，需要同步收口。
UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_360;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V44__iam_split_crm_master_data_navigation.sql
-- IAM V44：将 CRM 已落地的客户主数据能力拆分为独立导航页面。
-- 历史迁移不可改写；客户类型复用既有“客户等级与标签”页面，新增归属地区和外部员工页面。

SET @changed_at = TIMESTAMP('2026-08-13 14:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_assignments_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000204');
SET @crm_customer_areas = UUID_TO_BIN('019facf2-0000-7000-8000-000000000292');
SET @crm_external_staff = UUID_TO_BIN('019facf2-0000-7000-8000-000000000293');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@crm_customer_areas, @app_supply_chain, @crm_customers_menu,
     'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_AREAS', 'PAGE', NULL,
     '归属地区', 40, 'ACTIVE', @changed_at, @changed_at),
    (@crm_external_staff, @app_supply_chain, @crm_assignments_menu,
     'SUPPLY_CHAIN.PAGE.CRM_ASSIGNMENTS_EXTERNAL_STAFF', 'PAGE', NULL,
     '外部员工', 40, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key,
    visible, keep_alive, created_at, updated_at
) VALUES
    (@crm_customer_areas, 'supply.crm.customers.areas',
     '/supply-chain/crm/customers/areas', NULL, 1, 0, @changed_at, @changed_at),
    (@crm_external_staff, 'supply.crm.assignments.external-staff',
     '/supply-chain/crm/assignments/external-staff', NULL, 1, 0, @changed_at, @changed_at);

-- 新页面进入现行标准套餐，并授予既有租户超级管理员；普通角色仍由 IAM 按需授权。
INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @changed_at, NULL
FROM (
    SELECT @crm_customer_areas AS resource_id
    UNION ALL SELECT @crm_external_staff
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- V22 后租户导航使用菜单配置 INNER JOIN；补齐现有有效租户，避免新增页面被查询过滤。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resource_record.id,
       resource_ui.visible, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
  JOIN iam_resource_ui resource_ui
    ON resource_ui.resource_id = resource_record.id
  JOIN (
      SELECT @crm_customer_areas AS resource_id
      UNION ALL SELECT @crm_external_staff
  ) added_resources
    ON added_resources.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @crm_customer_areas AS resource_id
    UNION ALL SELECT @crm_external_staff
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V45__iam_customer_management_navigation.sql
-- IAM V45：将 CRM 客户主数据整理为客户管理下的四个同级页面。

SET @changed_at = TIMESTAMP('2026-08-13 15:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_shipping_addresses = UUID_TO_BIN('019facf2-0000-7000-8000-000000000294');

UPDATE iam_resource
   SET display_name='客户管理', version=version+1, updated_at=@changed_at
 WHERE id=@crm_customers_menu;

UPDATE iam_resource
   SET display_name='客户档案', version=version+1, updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000200');

UPDATE iam_resource
   SET display_name='客户类型', version=version+1, updated_at=@changed_at
 WHERE id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000202');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @crm_shipping_addresses, @app_supply_chain, @crm_customers_menu,
    'SUPPLY_CHAIN.PAGE.CRM_CUSTOMERS_SHIPPING_ADDRESSES', 'PAGE', NULL,
    '收货地址簿', 20, 'ACTIVE', @changed_at, @changed_at
);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key,
    visible, keep_alive, created_at, updated_at
) VALUES (
    @crm_shipping_addresses, 'supply.crm.customers.shipping-addresses',
    '/supply-chain/crm/customers/shipping-addresses', NULL,
    1, 0, @changed_at, @changed_at
);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), @crm_shipping_addresses, @changed_at, NULL
ON DUPLICATE KEY UPDATE created_at=created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @crm_shipping_addresses, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE updated_at=@changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, @crm_shipping_addresses,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 WHERE role_record.role_code='TENANT_SUPER_ADMIN'
   AND role_record.role_type='SYSTEM'
   AND role_record.status='ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@changed_at;

UPDATE iam_tenant
   SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V46__iam_replace_erp_warehouse_navigation.sql
-- IAM V46：移除 ERP“仓库作业”菜单，将“库存管理”改为“仓库管理”，并统一挂载仓库业务子页。
-- 只复用 V20 已存在的资源 ID，避免租户授权丢失；页面是否可用仍由 Portal 的实际接口映射决定。

SET @changed_at = TIMESTAMP('2026-08-15 12:00:00.000000');
SET @erp_warehouse_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
SET @erp_inventory_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000188');

-- “仓库作业”及其旧菜单节点从动态导航中移除；资源保留以兼容历史授权记录。
UPDATE iam_resource
   SET status = 'DISABLED', updated_at = @changed_at
 WHERE id = @erp_warehouse_menu;

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id = @erp_warehouse_menu;

-- 复用原仓库/库存资源作为新“仓库管理”八个子页，保持既有套餐和角色授权关系。
UPDATE iam_resource
   SET display_name = '仓库管理', updated_at = @changed_at
 WHERE id = @erp_inventory_menu;

UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_WAREHOUSES',
       display_name = '仓库信息', sort_order = 80, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_INBOUND',
       display_name = '入库单', sort_order = 30, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_OUTBOUND',
       display_name = '出库单', sort_order = 40, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000185');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_TRANSFERS',
       display_name = '库存调拨', sort_order = 60, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000186');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_STOCKTAKING',
       display_name = '库存盘点', sort_order = 70, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000187');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_DASHBOARD',
       display_name = '库存看板', sort_order = 10, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_BALANCES',
       display_name = '库存', sort_order = 20, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000190');
UPDATE iam_resource
   SET parent_id = @erp_inventory_menu, resource_code = 'SUPPLY_CHAIN.PAGE.ERP_INVENTORY_MOVEMENTS',
       display_name = '出入库流水', sort_order = 50, status = 'ACTIVE', updated_at = @changed_at
 WHERE id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000191');

UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.warehouses',
       route_path = '/supply-chain/erp/inventory/warehouses', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.inbound',
       route_path = '/supply-chain/erp/inventory/inbound', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.outbound',
       route_path = '/supply-chain/erp/inventory/outbound', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000185');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.transfers',
       route_path = '/supply-chain/erp/inventory/transfers', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000186');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.stocktaking',
       route_path = '/supply-chain/erp/inventory/stocktaking', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000187');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.dashboard',
       route_path = '/supply-chain/erp/inventory/dashboard', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.inventory',
       route_path = '/supply-chain/erp/inventory/inventory', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000190');
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.inventory.movements',
       route_path = '/supply-chain/erp/inventory/movements', visible = 1, updated_at = @changed_at
 WHERE resource_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000191');

UPDATE iam_resource_ui
   SET visible = 1, updated_at = @changed_at
 WHERE resource_id = @erp_inventory_menu;

-- 立即同步既有租户的菜单覆盖；新租户沿用资源 UI 的默认值。
UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id = config.resource_id
   SET config.visible = ui.visible, config.updated_at = @changed_at
 WHERE config.resource_id IN (
    @erp_warehouse_menu, @erp_inventory_menu,
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000183'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000184'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000185'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000186'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000187'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000189'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000190'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000191')
 );

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V47__iam_hide_legacy_erp_inventory_navigation.sql
-- IAM V47：隐藏旧“批次与效期”“库存预警”入口。
-- V46 已在环境中执行，不能修改其内容；本次补充变更单独迁移。

SET @changed_at = TIMESTAMP('2026-08-15 14:30:00.000000');

UPDATE iam_resource
   SET status = 'DISABLED', updated_at = @changed_at
 WHERE id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193')
 );

UPDATE iam_resource_ui
   SET visible = 0, updated_at = @changed_at
 WHERE resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193')
 );

UPDATE iam_tenant_menu_config config
JOIN iam_resource_ui ui ON ui.resource_id = config.resource_id
   SET config.visible = ui.visible, config.updated_at = @changed_at
 WHERE config.resource_id IN (
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000192'),
    UUID_TO_BIN('019facf2-0000-7000-8000-000000000193')
 );

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V48__iam_business_dictionary_permissions.sql
-- IAM V48：供应链业务字典页面和公共业务设置服务权限。

SET @changed_at = TIMESTAMP('2026-08-15 10:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @dict_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000265');
SET @dict_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000295');
SET @dict_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000296');

UPDATE iam_resource
   SET display_name='业务字典', status='ACTIVE', updated_at=@changed_at
 WHERE id=@dict_page;

UPDATE iam_resource_ui
   SET visible=1, updated_at=@changed_at
 WHERE resource_id=@dict_page;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@dict_read, @app_supply_chain, @dict_page,
     'SUPPLY_CHAIN.API.BUSINESS_DICTIONARY_READ', 'API',
     'business-settings:dict:read', '查询业务字典', 10, 'ACTIVE', @changed_at, @changed_at),
    (@dict_write, @app_supply_chain, @dict_page,
     'SUPPLY_CHAIN.API.BUSINESS_DICTIONARY_WRITE', 'API',
     'business-settings:dict:write', '维护业务字典', 20, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'), resource_id, @changed_at, NULL
FROM (SELECT @dict_read AS resource_id UNION ALL SELECT @dict_write) resources
ON DUPLICATE KEY UPDATE created_at=created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (SELECT @dict_read AS resource_id UNION ALL SELECT @dict_write) resources
 WHERE role_record.role_code='TENANT_SUPER_ADMIN'
   AND role_record.role_type='SYSTEM'
   AND role_record.status='ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status='ACTIVE', updated_at=@changed_at;

UPDATE iam_tenant_menu_config
   SET visible=1, updated_at=@changed_at
 WHERE resource_id=@dict_page;

UPDATE iam_tenant
   SET policy_version=policy_version+1,
       version=version+1,
       updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V49__iam_grant_dictionary_read_to_business_roles.sql
-- ERP、CRM、Order 页面已经统一通过业务字典解析来源枚举。
-- 将字典只读能力授予已有任一相关查询权限的角色；字典维护权限仍只属于明确授权的管理员。
SET @changed_at = TIMESTAMP('2026-08-15 18:30:00.000000');
SET @dict_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000295');

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT role_record.tenant_id, role_record.id, @dict_read,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_role_resource current_grant
    ON current_grant.tenant_id = role_record.tenant_id
   AND current_grant.role_id = role_record.id
   AND current_grant.status = 'ACTIVE'
  JOIN iam_resource current_resource
    ON current_resource.id = current_grant.resource_id
   AND current_resource.status = 'ACTIVE'
 WHERE role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND current_resource.permission_code IN (
       'erp:product:read',
       'erp:supply:read',
       'crm:customer:read',
       'order:read'
   )
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant tenant_record
SET tenant_record.policy_version = tenant_record.policy_version + 1,
    tenant_record.version = tenant_record.version + 1,
    tenant_record.updated_at = @changed_at
WHERE tenant_record.status = 'ACTIVE'
  AND tenant_record.deleted_at IS NULL
  AND EXISTS (
      SELECT 1
        FROM iam_role_resource dictionary_grant
       WHERE dictionary_grant.tenant_id = tenant_record.id
         AND dictionary_grant.resource_id = @dict_read
         AND dictionary_grant.status = 'ACTIVE'
  );

-- Source: V50__iam_procurement_payment_placeholder.sql
-- IAM V50：在“采购管理”下增加“采购付款单”菜单及稳定路由占位。
-- 当前不接入付款业务接口；仅向已有采购页面权限的角色追加该页面资源。

SET @changed_at = TIMESTAMP('2026-08-17 16:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @procurement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000177');
SET @procurement_payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000297');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @procurement_payment_page, @app_supply_chain, @procurement_menu,
    'SUPPLY_CHAIN.PAGE.ERP_PROCUREMENT_PAYMENTS', 'PAGE', NULL,
    '采购付款单', 50, 'ACTIVE', @changed_at, @changed_at
);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key,
    visible, keep_alive, created_at, updated_at
) VALUES (
    @procurement_payment_page, 'supply.erp.procurement.payments',
    '/supply-chain/erp/procurement/payments', NULL,
    1, 0, @changed_at, @changed_at
);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT UUID_TO_BIN('019facf3-0000-7000-8000-000000000002'),
       @procurement_payment_page, @changed_at, NULL
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @procurement_payment_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE updated_at = @changed_at;

-- 沿用既有采购菜单的业务角色边界，不将占位页扩大到无采购权限的角色。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT role_record.tenant_id, role_record.id, @procurement_payment_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
  JOIN iam_role_resource existing_grant
    ON existing_grant.tenant_id = role_record.tenant_id
   AND existing_grant.role_id = role_record.id
   AND existing_grant.status = 'ACTIVE'
 WHERE role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
   AND existing_grant.resource_id IN (
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000178'),
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000179'),
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000180'),
       UUID_TO_BIN('019facf2-0000-7000-8000-000000000181')
   )
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource payment_grant
        WHERE payment_grant.tenant_id = tenant_record.id
          AND payment_grant.resource_id = @procurement_payment_page
          AND payment_grant.status = 'ACTIVE'
   );

-- Source: V51__iam_hide_unimplemented_supply_navigation.sql
-- IAM V51：业务导航只展示已有真实接口和页面实现的供应链能力。
-- 页面资源继续保留，待领域接口完成后由后续迁移显式恢复；采购付款单是已确认的业务占位，继续可见。

SET @changed_at = TIMESTAMP('2026-08-17 17:30:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');

-- 尚无领域页面实现的一级模块不进入业务导航。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.city.menu',
       'supply.hr.menu',
       'supply.channel.menu',
       'supply.bi.menu',
       'supply.erp.index'
   );

-- ERP 页面采用真实接口白名单；采购付款单是本次明确要求保留的只读占位。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_record.resource_type = 'PAGE'
   AND ui_record.route_key LIKE 'supply.erp.%'
   AND ui_record.route_key NOT IN (
       'supply.erp.master-data.products',
       'supply.erp.master-data.skus',
       'supply.erp.master-data.attributes.categories',
       'supply.erp.master-data.attributes.brands',
       'supply.erp.master-data.attributes.specifications',
       'supply.erp.master-data.attributes.tags',
       'supply.erp.suppliers.profiles',
       'supply.erp.procurement.orders',
       'supply.erp.procurement.receipts',
       'supply.erp.procurement.returns',
       'supply.erp.procurement.payments',
       'supply.erp.inventory.inventory',
       'supply.erp.inventory.inbound',
       'supply.erp.inventory.warehouses'
   );

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key = 'supply.erp.cost-settlement.menu';

-- CRM 仅开放已有客户、地址、类型、地区和外部员工查询契约的页面。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_record.resource_type = 'PAGE'
   AND ui_record.route_key LIKE 'supply.crm.%'
   AND ui_record.route_key NOT IN (
       'supply.crm.index',
       'supply.crm.customers.profiles',
       'supply.crm.customers.shipping-addresses',
       'supply.crm.customers.levels-tags',
       'supply.crm.customers.areas',
       'supply.crm.assignments.external-staff'
   );

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key = 'supply.crm.credit-policy.menu';

-- 订单中心仅开放已经落到本地订单、履约、退货和收付款查询接口的页面。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_record.resource_type = 'PAGE'
   AND ui_record.route_key LIKE 'supply.order.%'
   AND ui_record.route_key NOT IN (
       'supply.order.all',
       'supply.order.pending',
       'supply.order.exceptions',
       'supply.order.order-list',
       'supply.order.stock-up',
       'supply.order.shipments',
       'supply.order.returns',
       'supply.order.settlement.receipts',
       'supply.order.settlement.payments'
   );

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key = 'supply.order.stats';

-- 销售管理当前只开放真实实现的管控台、拜访计划、待复核、定位对比和主管复核。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.sales.attendance.menu',
       'supply.sales.attendance.today',
       'supply.sales.attendance.punches',
       'supply.sales.attendance.days',
       'supply.sales.attendance.interruptions',
       'supply.sales.attendance.adjustments',
       'supply.sales.visits.records',
       'supply.sales.visits.appeals',
       'supply.sales.stores.menu',
       'supply.sales.stores.assigned',
       'supply.sales.stores.uncovered',
       'supply.sales.stores.visited',
       'supply.sales.stores.effective',
       'supply.sales.stores.candidates',
       'supply.sales.organization.menu',
       'supply.sales.organization.profiles',
       'supply.sales.organization.teams',
       'supply.sales.organization.scopes',
       'supply.sales.tasks.menu',
       'supply.sales.tasks.visits',
       'supply.sales.tasks.targets',
       'supply.sales.tasks.exemptions',
       'supply.sales.exceptions.punch',
       'supply.sales.exceptions.evidence',
       'supply.sales.exceptions.recording',
       'supply.sales.policies.menu',
       'supply.sales.policies.field',
       'supply.sales.policies.visit',
       'supply.sales.policies.recording-ai',
       'supply.sales.policies.scopes',
       'supply.sales.policies.releases'
   );

-- 业务设置仅保留已有真实服务端契约的业务字典。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.setting.index',
       'supply.settings.product-inventory',
       'supply.settings.procurement',
       'supply.settings.customer-levels-tags',
       'supply.settings.credit-policy-templates',
       'supply.settings.order-after-sales',
       'supply.settings.fulfillment-allocation',
       'supply.settings.city-service-scope'
   );

-- 集成区仅展示已有连接、任务、映射、镜像和日志接口的页面。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET ui_record.visible = 0,
       ui_record.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN (
       'supply.integration.sync-batches',
       'supply.integration.external-id-mappings',
       'supply.integration.reconciliation',
       'supply.integration.sovereignty'
   );

-- 同步收口已有租户的菜单偏好，避免后续管理操作把平台已隐藏的占位能力重新开启。
UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource resource_record ON resource_record.id = menu_config.resource_id
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET menu_config.visible = 0,
       menu_config.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.visible = 0;

-- 名称必须忠实于当前接口：现有页面查询订单镜像和同步日志，不具备 Raw/死信重试能力。
UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.display_name = CASE ui_record.route_key
           WHEN 'supply.integration.raw-data' THEN '订单镜像'
           WHEN 'supply.integration.retries' THEN '同步日志'
           ELSE resource_record.display_name
       END,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at,
       ui_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND ui_record.route_key IN ('supply.integration.raw-data', 'supply.integration.retries');

-- 平台可见性变化需要推进租户策略版本，避免已有导航缓存继续暴露占位能力。
UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V51_1__iam_v52_resource_status_compatibility.sql
-- 历史 V52 将旧页面写为 INACTIVE，但 V1 的资源约束只有 ACTIVE/DISABLED。
-- 不改写已执行的 V52；仅在回放 V52 的迁移窗口接受旧停用值。
-- V52.1 会将旧值归一化为 DISABLED 并恢复原约束，业务运行期间不保留兼容逻辑。
-- 已越过 V52 的环境须在备份和版本清单核对后，一次性 outOfOrder 应用 51.1/52.1。
ALTER TABLE iam_resource
    DROP CHECK ck_iam_resource_status,
    ADD CONSTRAINT ck_iam_resource_status CHECK (status IN ('ACTIVE', 'DISABLED', 'INACTIVE'));

-- Source: V52__iam_business_first_supply_navigation.sql
-- IAM V52：供应链菜单按“我方业务主流程”和“外部同步”重新分组。
--
-- 业务口径：
-- 1. ERP 商品中心只展示我方商品、分类、品牌、标签等可维护业务资料。
-- 2. 订货宝同步只作为后台来源接入，后续直接映射写入我方业务表。
-- 3. SKU、订货宝规格等旧同步档案不再注册业务菜单，避免继续以订货宝模型驱动操作流程。

SET @changed_at = TIMESTAMP('2026-08-20 16:45:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @erp_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000059');
SET @erp_product_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @erp_sku_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000169');
SET @erp_category_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000170');
SET @erp_brand_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000171');
SET @erp_specification_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');
SET @erp_supplier_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000173');
SET @erp_supplier_profile_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000174');
SET @erp_supplier_product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000175');
SET @erp_supplier_price_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000176');
SET @erp_procurement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000177');
SET @erp_procurement_request_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000178');
SET @erp_procurement_order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000179');
SET @erp_procurement_receipt_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000180');
SET @erp_procurement_return_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000181');
SET @erp_warehouse_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000182');
SET @erp_inventory_warehouse_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000183');
SET @erp_stock_in_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000184');
SET @erp_stock_out_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000185');
SET @erp_transfer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000186');
SET @erp_stocktaking_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000187');
SET @erp_tag_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000284');
SET @erp_attributes_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000285');
SET @erp_legacy_sync_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000286');
SET @erp_inventory_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000188');
SET @erp_inventory_dashboard_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000189');
SET @erp_inventory_balance_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000190');
SET @erp_inventory_movement_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000191');
SET @erp_inventory_batch_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000192');
SET @erp_inventory_alert_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000193');
SET @erp_cost_settlement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000194');
SET @erp_procurement_payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000297');

SET @crm_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_customer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000200');
SET @crm_store_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000201');
SET @crm_customer_type_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000202');
SET @crm_customer_360_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000203');
SET @crm_assignments_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000204');
SET @crm_assignment_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000205');
SET @crm_assignment_city_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000206');
SET @crm_assignment_history_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000207');
SET @crm_credit_policy_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000208');
SET @crm_customer_area_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000292');
SET @crm_external_staff_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000293');
SET @crm_shipping_address_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000294');

SET @order_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_access_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000056');
SET @order_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @order_stock_up_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000102');
SET @order_shipment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');
SET @order_return_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000104');
SET @order_delivery_partner_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000105');
SET @order_fulfillment_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000106');
SET @order_after_sales_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000111');
SET @order_center_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000112');
SET @order_index_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000113');
SET @order_all_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000114');
SET @order_pending_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000115');
SET @order_exception_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000116');
SET @order_settlement_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000235');
SET @order_settlement_collection_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000237');
SET @order_settlement_receipt_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000276');
SET @order_settlement_payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000277');

SET @integration_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @integration_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @integration_raw_data_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000085');
SET @integration_sync_tasks_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000086');
SET @integration_sync_logs_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000087');
SET @integration_connection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000088');
SET @integration_field_mapping_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000089');
SET @integration_reconciliation_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000090');
SET @integration_sovereignty_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000091');
SET @integration_sync_batches_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000266');
SET @integration_external_mapping_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000267');
SET @integration_sync_control_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000300');
SET @settings_dictionary_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000265');

-- ERP 商品中心：只放我方可维护的商品资料。
UPDATE iam_resource
   SET display_name = '商品中心',
       sort_order = 20,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_product_menu;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品管理',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_product_page;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品分类',
       sort_order = 20,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_category_page;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品品牌',
       sort_order = 30,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_brand_page;

UPDATE iam_resource
   SET parent_id = @erp_product_menu,
       display_name = '商品标签',
       sort_order = 40,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_tag_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_product_menu, @erp_product_page, @erp_category_page, @erp_brand_page, @erp_tag_page);

-- 数据字典：前端和新业务表统一采用“数据字典 / 数据字典项”口径。
UPDATE iam_resource
   SET display_name = '数据字典',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @settings_dictionary_page;

-- 旧订货宝商品同步细项不再挂在 ERP 主流程菜单。
UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_sku_page, @erp_specification_page, @erp_attributes_menu, @erp_legacy_sync_page);

UPDATE iam_tenant_menu_config
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_product_menu, @erp_product_page, @erp_category_page, @erp_brand_page, @erp_tag_page);

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (@erp_sku_page, @erp_specification_page, @erp_attributes_menu, @erp_legacy_sync_page);

-- ERP 库存管理：库存调拨是独立业务菜单，不再放在入库单页面流程里混合表达。
UPDATE iam_resource
   SET display_name = '库存管理',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_inventory_menu;

-- ERP 业务主菜单：只展示新方案已经有页面承接的业务入口。
UPDATE iam_resource
   SET display_name = 'ERP',
       sort_order = 20,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @erp_root_menu;

UPDATE iam_resource
   SET display_name = CASE id
           WHEN @erp_supplier_menu THEN '供应商'
           WHEN @erp_supplier_profile_page THEN '供应商档案'
           WHEN @erp_procurement_menu THEN '采购管理'
           WHEN @erp_procurement_order_page THEN '采购订单'
           WHEN @erp_procurement_payment_page THEN '采购付款单'
           WHEN @erp_inventory_menu THEN '库存管理'
           WHEN @erp_inventory_balance_page THEN '库存'
           WHEN @erp_stock_in_page THEN '入库单'
           WHEN @erp_stock_out_page THEN '出库单'
           WHEN @erp_transfer_page THEN '库存调拨'
           WHEN @erp_inventory_warehouse_page THEN '仓库信息'
           ELSE display_name
       END,
       sort_order = CASE id
           WHEN @erp_supplier_menu THEN 30
           WHEN @erp_supplier_profile_page THEN 10
           WHEN @erp_procurement_menu THEN 40
           WHEN @erp_procurement_order_page THEN 10
           WHEN @erp_procurement_payment_page THEN 20
           WHEN @erp_inventory_menu THEN 50
           WHEN @erp_inventory_balance_page THEN 10
           WHEN @erp_stock_in_page THEN 20
           WHEN @erp_stock_out_page THEN 30
           WHEN @erp_transfer_page THEN 40
           WHEN @erp_inventory_warehouse_page THEN 50
           ELSE sort_order
       END,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (
    @erp_supplier_menu,
    @erp_supplier_profile_page,
    @erp_procurement_menu,
    @erp_procurement_order_page,
    @erp_procurement_payment_page,
    @erp_inventory_menu,
    @erp_inventory_balance_page,
    @erp_stock_in_page,
    @erp_stock_out_page,
    @erp_transfer_page,
    @erp_inventory_warehouse_page
 );

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_root_menu,
    @erp_product_menu,
    @erp_product_page,
    @erp_category_page,
    @erp_brand_page,
    @erp_tag_page,
    @erp_supplier_menu,
    @erp_supplier_profile_page,
    @erp_procurement_menu,
    @erp_procurement_order_page,
    @erp_procurement_payment_page,
    @erp_inventory_menu,
    @erp_inventory_balance_page,
    @erp_stock_in_page,
    @erp_stock_out_page,
    @erp_transfer_page,
    @erp_inventory_warehouse_page
 );

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_supplier_product_page,
    @erp_supplier_price_page,
    @erp_procurement_request_page,
    @erp_procurement_receipt_page,
    @erp_procurement_return_page,
    @erp_warehouse_menu,
    @erp_stocktaking_page,
    @erp_inventory_dashboard_page,
    @erp_inventory_movement_page,
    @erp_inventory_batch_page,
    @erp_inventory_alert_page,
    @erp_cost_settlement_menu
 );

-- CRM 新方案：客户、商家、门店合并为“客户管理”，不再拆门店/地址/外部员工菜单。
UPDATE iam_resource
   SET display_name = 'CRM',
       sort_order = 30,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_root_menu;

UPDATE iam_resource
   SET parent_id = @crm_root_menu,
       display_name = '客户管理',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@crm_root_menu, @crm_customer_page);

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @crm_customers_menu,
    @crm_store_page,
    @crm_customer_type_page,
    @crm_customer_360_page,
    @crm_assignments_menu,
    @crm_assignment_sales_page,
    @crm_assignment_city_page,
    @crm_assignment_history_page,
    @crm_credit_policy_menu,
    @crm_customer_area_page,
    @crm_external_staff_page,
    @crm_shipping_address_page
 );

-- Order 新方案：订单管理只展示销售订单，页面走 order-sales 新接口。
UPDATE iam_resource
   SET display_name = '订单管理',
       sort_order = 40,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_root_menu;

UPDATE iam_resource
   SET parent_id = @order_root_menu,
       resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS',
       resource_type = 'PAGE',
       display_name = '销售订单',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @order_sales_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.order.sales-orders',
       route_path = '/supply-chain/order/sales-orders',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @order_sales_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@order_root_menu, @order_sales_page);

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @order_access_menu,
    @order_stock_up_page,
    @order_shipment_page,
    @order_return_page,
    @order_delivery_partner_page,
    @order_fulfillment_menu,
    @order_after_sales_menu,
    @order_center_menu,
    @order_index_page,
    @order_all_page,
    @order_pending_page,
    @order_exception_page,
    @order_settlement_menu,
    @order_settlement_collection_menu,
    @order_settlement_receipt_page,
    @order_settlement_payment_page
 );

-- 租户菜单显隐跟随本次业务主流程白名单，避免前端页面和 IAM 菜单不一致。
INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, visible_resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @erp_root_menu AS resource_id
    UNION ALL SELECT @erp_product_menu
    UNION ALL SELECT @erp_product_page
    UNION ALL SELECT @erp_category_page
    UNION ALL SELECT @erp_brand_page
    UNION ALL SELECT @erp_tag_page
    UNION ALL SELECT @erp_supplier_menu
    UNION ALL SELECT @erp_supplier_profile_page
    UNION ALL SELECT @erp_procurement_menu
    UNION ALL SELECT @erp_procurement_order_page
    UNION ALL SELECT @erp_procurement_payment_page
    UNION ALL SELECT @erp_inventory_menu
    UNION ALL SELECT @erp_inventory_balance_page
    UNION ALL SELECT @erp_stock_in_page
    UNION ALL SELECT @erp_stock_out_page
    UNION ALL SELECT @erp_transfer_page
    UNION ALL SELECT @erp_inventory_warehouse_page
    UNION ALL SELECT @crm_root_menu
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @order_root_menu
    UNION ALL SELECT @order_sales_page
    UNION ALL SELECT @settings_dictionary_page
 ) visible_resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_sku_page,
    @erp_specification_page,
    @erp_attributes_menu,
    @erp_legacy_sync_page,
    @erp_supplier_product_page,
    @erp_supplier_price_page,
    @erp_procurement_request_page,
    @erp_procurement_receipt_page,
    @erp_procurement_return_page,
    @erp_warehouse_menu,
    @erp_stocktaking_page,
    @erp_inventory_dashboard_page,
    @erp_inventory_movement_page,
    @erp_inventory_batch_page,
    @erp_inventory_alert_page,
    @erp_cost_settlement_menu,
    @crm_customers_menu,
    @crm_store_page,
    @crm_customer_type_page,
    @crm_customer_360_page,
    @crm_assignments_menu,
    @crm_assignment_sales_page,
    @crm_assignment_city_page,
    @crm_assignment_history_page,
    @crm_credit_policy_menu,
    @crm_customer_area_page,
    @crm_external_staff_page,
    @crm_shipping_address_page,
    @order_access_menu,
    @order_stock_up_page,
    @order_shipment_page,
    @order_return_page,
    @order_delivery_partner_page,
    @order_fulfillment_menu,
    @order_after_sales_menu,
    @order_center_menu,
    @order_index_page,
    @order_all_page,
    @order_pending_page,
    @order_exception_page,
    @order_settlement_menu,
    @order_settlement_collection_menu,
    @order_settlement_receipt_page,
    @order_settlement_payment_page
 );

-- 新主流程页面授予租户超级管理员；普通角色后续仍走 IAM 精细授权。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, visible_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @erp_product_page AS resource_id
    UNION ALL SELECT @erp_category_page
    UNION ALL SELECT @erp_brand_page
    UNION ALL SELECT @erp_tag_page
    UNION ALL SELECT @erp_supplier_profile_page
    UNION ALL SELECT @erp_procurement_order_page
    UNION ALL SELECT @erp_procurement_payment_page
    UNION ALL SELECT @erp_inventory_balance_page
    UNION ALL SELECT @erp_stock_in_page
    UNION ALL SELECT @erp_stock_out_page
    UNION ALL SELECT @erp_transfer_page
    UNION ALL SELECT @erp_inventory_warehouse_page
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @order_sales_page
    UNION ALL SELECT @settings_dictionary_page
 ) visible_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- 外部同步：只保留订货宝同步中心单入口；旧分散同步子页面不再作为菜单暴露。
UPDATE iam_resource
   SET display_name = '外部同步',
       sort_order = 90,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_menu;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_menu;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @integration_sync_control_menu, @app_supply_chain, @integration_menu,
    'SUPPLY_CHAIN.MENU.INTEGRATION_SYNC_CONTROL', 'MENU', NULL,
    '订货宝同步', 10, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @integration_sync_control_menu, 'supply.integration.sync-control.menu', NULL, NULL, 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET parent_id = @integration_sync_control_menu,
       display_name = '订货宝同步中心',
       status = 'ACTIVE',
       sort_order = 10,
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_overview_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.overview',
       route_path = '/supply-chain/integration',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_overview_page;

UPDATE iam_resource_ui
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (
    @integration_menu,
    @integration_sync_control_menu,
    @integration_overview_page
 );

UPDATE iam_resource
   SET status = 'INACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_sku_page,
    @erp_specification_page,
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, package_resources.resource_id, @changed_at, NULL
 FROM (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
 ) package_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resource_record.id, resource_ui.visible, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
  JOIN iam_resource resource_record
    ON resource_record.id IN (
        @integration_menu,
        @integration_sync_control_menu,
        @integration_overview_page
    )
  JOIN iam_resource_ui resource_ui
    ON resource_ui.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = VALUES(visible),
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id IN (
    @erp_sku_page,
    @erp_specification_page,
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

DELETE FROM iam_role_resource
 WHERE resource_id IN (
    @integration_raw_data_page,
    @integration_connection_page,
    @integration_sync_tasks_page,
    @integration_sync_logs_page,
    @integration_field_mapping_page,
    @integration_reconciliation_page,
    @integration_sovereignty_page,
    @integration_sync_batches_page,
    @integration_external_mapping_page
 );

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, visible_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
 ) visible_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V52_1__iam_remove_v52_resource_status_compatibility.sql
-- 历史 V52 已完成；停用语义不变，将旧值归一化后恢复原始严格约束。
UPDATE iam_resource SET status = 'DISABLED' WHERE status = 'INACTIVE';
ALTER TABLE iam_resource
    DROP CHECK ck_iam_resource_status,
    ADD CONSTRAINT ck_iam_resource_status CHECK (status IN ('ACTIVE', 'DISABLED'));

-- Source: V53__iam_product_specification_business_page.sql
-- IAM V53：新增 ERP 商品规格业务页。
--
-- 业务口径：
-- 1. “商品规格”属于 ERP 商品中心，用于维护我方商品多规格和子规格值。
-- 2. “订货宝规格档案”继续保留在外部同步，不进入 ERP 主流程菜单。

SET @changed_at = TIMESTAMP('2026-08-20 17:35:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @erp_product_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @erp_product_specification_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000302');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @erp_product_specification_page,
    @app_supply_chain,
    @erp_product_menu,
    'SUPPLY_CHAIN.PAGE.ERP_PRODUCT_SPECIFICATIONS',
    'PAGE',
    NULL,
    '商品规格',
    50,
    'ACTIVE',
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    parent_id = VALUES(parent_id),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @erp_product_specification_page,
    'supply.erp.master-data.attributes.specifications',
    '/supply-chain/erp/master-data/attributes/specifications',
    NULL,
    1,
    0,
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @erp_product_specification_page, @changed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @erp_product_specification_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.package_version_id = @standard_package_version
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

-- Source: V54__iam_delete_legacy_dhb_order_navigation.sql
-- IAM V54：物理删除旧订货宝镜像菜单和旧 Order 分散菜单。
--
-- V52 已经将这些入口隐藏；本迁移按 route_key 审计后删除资源、租户菜单配置、
-- 套餐授权和角色授权，避免前端注册表继续背旧页面兼容债。

SET @changed_at = TIMESTAMP('2026-08-21 10:40:00.000000');

CREATE TABLE IF NOT EXISTS iam_resource_cleanup_v54 (
    resource_id BINARY(16) NOT NULL,
    CONSTRAINT pk_iam_resource_cleanup_v54 PRIMARY KEY (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='V54待删除旧菜单资源ID';

TRUNCATE TABLE iam_resource_cleanup_v54;

INSERT IGNORE INTO iam_resource_cleanup_v54 (resource_id)
SELECT DISTINCT ui.resource_id
  FROM iam_resource_ui ui
 WHERE ui.route_key LIKE 'supply.order.%'
   AND ui.route_key NOT IN ('supply.order.menu', 'supply.order.sales-orders');

INSERT IGNORE INTO iam_resource_cleanup_v54 (resource_id)
SELECT DISTINCT ui.resource_id
  FROM iam_resource_ui ui
 WHERE ui.route_key LIKE 'supply.dinghuobao.%'
    OR ui.route_key IN (
        'supply.integration.raw-data',
        'supply.integration.connections',
        'supply.integration.sync-tasks',
        'supply.integration.sync-logs',
        'supply.integration.retries',
        'supply.integration.field-mappings',
        'supply.integration.reconciliation',
        'supply.integration.sovereignty',
        'supply.integration.sync-batches',
        'supply.integration.external-id-mappings'
    );

DELETE role_resource
  FROM iam_role_resource role_resource
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = role_resource.resource_id;

DELETE package_resource
  FROM iam_package_resource package_resource
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = package_resource.resource_id;

DELETE menu_config
  FROM iam_tenant_menu_config menu_config
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = menu_config.resource_id;

UPDATE iam_resource child_resource
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = child_resource.parent_id
   SET child_resource.parent_id = NULL,
       child_resource.version = child_resource.version + 1,
       child_resource.updated_at = @changed_at;

UPDATE iam_resource resource_record
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = resource_record.id
   SET resource_record.parent_id = NULL,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at;

DELETE resource_ui
  FROM iam_resource_ui resource_ui
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = resource_ui.resource_id;

DELETE resource_record
  FROM iam_resource resource_record
  JOIN iam_resource_cleanup_v54 cleanup
    ON cleanup.resource_id = resource_record.id;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

DROP TABLE iam_resource_cleanup_v54;

-- Source: V55__iam_delete_legacy_dhb_navigation_leftovers.sql
-- IAM V55：清理 V54 遗漏的旧订货宝导航残留。
--
-- V17 已将 supply.dinghuobao.* 改名为 supply.dhb.*，V54 只删除了旧长名称，
-- 导致运行时导航仍返回 supply.dhb.menu 以及 legacy-dhb 档案页。前端按已注册
-- routeKey 失败关闭，所以这里补齐清理并保留“外部同步 / 订货宝同步中心”单入口。

SET @changed_at = TIMESTAMP('2026-08-21 18:35:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @integration_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @integration_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @integration_sync_control_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000300');

CREATE TABLE IF NOT EXISTS iam_resource_cleanup_v55 (
    resource_id BINARY(16) NOT NULL,
    CONSTRAINT pk_iam_resource_cleanup_v55 PRIMARY KEY (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='V55待删除旧订货宝导航资源ID';

TRUNCATE TABLE iam_resource_cleanup_v55;

-- 外部同步根菜单沿用历史资源 ID，但 routeKey 必须收敛到新前端注册表。
UPDATE iam_resource
   SET display_name = '外部同步',
       sort_order = 90,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_menu;

UPDATE iam_resource
   SET parent_id = @integration_menu,
       display_name = '订货宝同步',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_sync_control_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.sync-control.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_sync_control_menu;

UPDATE iam_resource
   SET parent_id = @integration_sync_control_menu,
       display_name = '订货宝同步中心',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_overview_page
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.overview',
       route_path = '/supply-chain/integration',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_overview_page;

INSERT IGNORE INTO iam_resource_cleanup_v55 (resource_id)
SELECT DISTINCT ui.resource_id
  FROM iam_resource_ui ui
  JOIN iam_resource resource_record ON resource_record.id = ui.resource_id
 WHERE resource_record.application_id = @app_supply_chain
   AND ui.resource_id NOT IN (@integration_menu, @integration_sync_control_menu, @integration_overview_page)
   AND (
       ui.route_key LIKE 'supply.dhb.%'
       OR ui.route_key IN (
           'supply.integration.legacy-dhb.menu',
           'supply.integration.dhb-products',
           'supply.integration.dhb-skus',
           'supply.integration.dhb-specifications'
       )
   );

DELETE role_resource
  FROM iam_role_resource role_resource
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = role_resource.resource_id;

DELETE package_resource
  FROM iam_package_resource package_resource
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = package_resource.resource_id;

DELETE menu_config
  FROM iam_tenant_menu_config menu_config
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = menu_config.resource_id;

UPDATE iam_resource child_resource
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = child_resource.parent_id
   SET child_resource.parent_id = NULL,
       child_resource.version = child_resource.version + 1,
       child_resource.updated_at = @changed_at;

UPDATE iam_resource resource_record
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = resource_record.id
   SET resource_record.parent_id = NULL,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at;

DELETE resource_ui
  FROM iam_resource_ui resource_ui
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = resource_ui.resource_id;

DELETE resource_record
  FROM iam_resource resource_record
  JOIN iam_resource_cleanup_v55 cleanup
    ON cleanup.resource_id = resource_record.id;

UPDATE iam_tenant_menu_config
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id IN (@integration_menu, @integration_sync_control_menu, @integration_overview_page);

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

DROP TABLE iam_resource_cleanup_v55;

-- Source: V56__iam_staff_position_management.sql
-- IAM V56：人员中心与岗位中心。
--
-- 业务口径：
-- 1. iam_staff_profile 是我方人员主档，组织架构和岗位以我方模型为准。
-- 2. 订货宝员工只作为过渡来源，落 iam_external_staff_binding 记录来源字段、幂等哈希和最后出现时间。
-- 3. 账号、角色、权限仍由 iam_user / iam_role / iam_role_resource 管控，人员通过绑定关联登录身份。

SET @changed_at = TIMESTAMP('2026-08-22 22:40:00.000000');
SET @app_system_admin = UUID_TO_BIN('019facf1-0000-7000-8000-000000000002');
SET @organization_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000026');
SET @staff_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000330');
SET @staff_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000331');
SET @staff_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000332');
SET @staff_sync = UUID_TO_BIN('019facf2-0000-7000-8000-000000000333');
SET @position_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000334');
SET @position_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000335');
SET @position_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000336');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

CREATE TABLE iam_position (
    id             BINARY(16)      NOT NULL COMMENT '岗位ID，UUID二进制存储',
    tenant_id      BINARY(16)      NOT NULL COMMENT '租户ID',
    position_code  VARCHAR(50)     NOT NULL COMMENT '岗位编码，由IAM编码规则生成',
    position_name  VARCHAR(128)    NOT NULL COMMENT '岗位名称，例如销售经理、仓库主管',
    description    VARCHAR(500)    NULL COMMENT '岗位职责或备注说明',
    sort_order     INT             NOT NULL DEFAULT 0 COMMENT '岗位展示排序，数字越小越靠前',
    status         VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '岗位状态：ACTIVE启用，DISABLED停用',
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by     BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by     BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at     DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by     BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason  VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_position PRIMARY KEY (id),
    CONSTRAINT uk_iam_position_code UNIQUE (tenant_id, position_code),
    CONSTRAINT uk_iam_position_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_position_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_position_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_position_status (tenant_id, status, sort_order, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户岗位主档';

CREATE TABLE iam_staff_profile (
    id                      BINARY(16)      NOT NULL COMMENT '人员ID，UUID二进制存储',
    tenant_id               BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_code              VARCHAR(50)     NOT NULL COMMENT '员工编码，由IAM编码规则生成',
    staff_name              VARCHAR(128)    NOT NULL COMMENT '员工姓名，以我方人员档案为准',
    mobile                  VARCHAR(32)     NULL COMMENT '员工手机号',
    email                   VARCHAR(128)    NULL COMMENT '员工邮箱',
    employment_status       VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '在职状态：ACTIVE在职，DISABLED停用，LEFT离职',
    primary_organization_id BINARY(16)      NULL COMMENT '主组织ID，指向我方组织架构',
    primary_position_id     BINARY(16)      NULL COMMENT '主岗位ID，指向我方岗位主档',
    record_origin           VARCHAR(32)     NOT NULL DEFAULT 'MANUAL' COMMENT '档案来源：MANUAL手工，DINGHUOBAO订货宝导入，IMPORT批量导入',
    remark                  VARCHAR(500)    NULL COMMENT '人员备注',
    version                 BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at              DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by              BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at              DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by              BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at              DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by              BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason           VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_staff_profile PRIMARY KEY (id),
    CONSTRAINT uk_iam_staff_profile_code UNIQUE (tenant_id, staff_code),
    CONSTRAINT uk_iam_staff_profile_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_staff_profile_status CHECK (employment_status IN ('ACTIVE', 'DISABLED', 'LEFT')),
    CONSTRAINT ck_iam_staff_profile_origin CHECK (record_origin IN ('MANUAL', 'DINGHUOBAO', 'IMPORT')),
    CONSTRAINT fk_iam_staff_profile_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_profile_org FOREIGN KEY (tenant_id, primary_organization_id)
        REFERENCES iam_organization (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_profile_position FOREIGN KEY (tenant_id, primary_position_id)
        REFERENCES iam_position (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_staff_profile_org (tenant_id, primary_organization_id, employment_status, deleted_at),
    INDEX idx_iam_staff_profile_position (tenant_id, primary_position_id, employment_status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='租户人员主档';

CREATE TABLE iam_staff_assignment (
    id              BINARY(16)      NOT NULL COMMENT '任职关系ID，UUID二进制存储',
    tenant_id       BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_id        BINARY(16)      NOT NULL COMMENT '人员ID',
    organization_id BINARY(16)      NOT NULL COMMENT '任职组织ID，指向我方组织架构',
    position_id     BINARY(16)      NULL COMMENT '任职岗位ID，指向我方岗位主档',
    assignment_type VARCHAR(32)     NOT NULL DEFAULT 'PRIMARY' COMMENT '任职类型：PRIMARY主任职，SECONDARY兼任',
    status          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '任职状态：ACTIVE有效，INACTIVE失效',
    effective_from  DATETIME(6)     NOT NULL COMMENT '任职生效时间',
    effective_to    DATETIME(6)     NULL COMMENT '任职失效时间，NULL表示当前有效',
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by      BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at      DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by      BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at      DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by      BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason   VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_staff_assignment PRIMARY KEY (id),
    CONSTRAINT ck_iam_staff_assignment_type CHECK (assignment_type IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_iam_staff_assignment_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_iam_staff_assignment_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT fk_iam_staff_assignment_staff FOREIGN KEY (tenant_id, staff_id)
        REFERENCES iam_staff_profile (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_assignment_org FOREIGN KEY (tenant_id, organization_id)
        REFERENCES iam_organization (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_assignment_position FOREIGN KEY (tenant_id, position_id)
        REFERENCES iam_position (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_staff_assignment_staff (tenant_id, staff_id, status, effective_to),
    INDEX idx_iam_staff_assignment_org (tenant_id, organization_id, status, effective_to),
    INDEX idx_iam_staff_assignment_position (tenant_id, position_id, status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员任职关系';

CREATE TABLE iam_staff_user_binding (
    id             BINARY(16)      NOT NULL COMMENT '人员账号绑定ID，UUID二进制存储',
    tenant_id      BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_id       BINARY(16)      NOT NULL COMMENT '人员ID',
    user_id        BINARY(16)      NOT NULL COMMENT 'IAM登录账号ID',
    status         VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '绑定状态：ACTIVE有效，INACTIVE失效',
    bound_at       DATETIME(6)     NOT NULL COMMENT '绑定生效时间',
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by     BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by     BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at     DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by     BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason  VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_staff_user_binding PRIMARY KEY (id),
    CONSTRAINT uk_iam_staff_user_binding_staff UNIQUE (tenant_id, staff_id),
    CONSTRAINT ck_iam_staff_user_binding_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_iam_staff_user_binding_staff FOREIGN KEY (tenant_id, staff_id)
        REFERENCES iam_staff_profile (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_user_binding_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES iam_user (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_staff_user_binding_user (tenant_id, user_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='人员与登录账号绑定';

CREATE TABLE iam_external_staff_binding (
    id                     BINARY(16)      NOT NULL COMMENT '外部员工绑定ID，UUID二进制存储',
    tenant_id              BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_id               BINARY(16)      NOT NULL COMMENT '我方人员ID',
    connector_id           BINARY(16)      NULL COMMENT '订货宝连接配置ID，来源于Integration连接配置',
    source_system          VARCHAR(32)     NOT NULL COMMENT '外部来源系统，当前固定DINGHUOBAO',
    source_tenant_key      VARCHAR(128)    NOT NULL DEFAULT 'DEFAULT' COMMENT '外部租户或店铺标识，缺省DEFAULT',
    source_staff_id        VARCHAR(128)    NOT NULL COMMENT '订货宝staff_id，唯一识别来源员工',
    source_staff_type      VARCHAR(32)     NULL COMMENT '订货宝staff_type：salesman、boss、indoorwork、driver',
    source_account_name    VARCHAR(128)    NULL COMMENT '订货宝accounts_name，登录账号名',
    source_staff_name      VARCHAR(128)    NULL COMMENT '订货宝staff_name，员工姓名快照',
    source_title           VARCHAR(128)    NULL COMMENT '订货宝title，职位快照，不直接生成我方岗位',
    source_branch_name     VARCHAR(128)    NULL COMMENT '订货宝branch_name，部门快照，不直接生成我方组织',
    source_accounts_mobile VARCHAR(32)     NULL COMMENT '订货宝accounts_mobile，账号手机号快照',
    source_about           VARCHAR(500)    NULL COMMENT '订货宝about，备注快照',
    source_role            VARCHAR(128)    NULL COMMENT '订货宝role，角色快照，不直接生成我方IAM角色',
    source_invite_code     VARCHAR(64)     NULL COMMENT '订货宝invite_code，邀请码快照',
    source_mobile          VARCHAR(32)     NULL COMMENT '订货宝mobile，员工手机号快照',
    source_email           VARCHAR(128)    NULL COMMENT '订货宝email，邮箱快照',
    source_qq              VARCHAR(64)     NULL COMMENT '订货宝qq，QQ号码快照',
    source_status          VARCHAR(32)     NULL COMMENT '订货宝状态原值，例如T启用、F停用',
    source_payload_hash    VARCHAR(128)    NULL COMMENT '来源负载哈希，用于幂等判断和跳过未变化记录',
    source_payload_json    JSON            NULL COMMENT '订货宝员工原始行JSON快照，便于问题回放和字段补偿',
    source_created_at      DATETIME(6)     NULL COMMENT '订货宝create_date，员工创建时间',
    source_updated_at      DATETIME(6)     NULL COMMENT '订货宝update_date，员工最后更新时间',
    source_presence        VARCHAR(32)     NOT NULL DEFAULT 'PRESENT' COMMENT '来源存在状态：PRESENT仍存在，MISSING来源缺失',
    last_seen_at           DATETIME(6)     NOT NULL COMMENT '最近一次同步看到该来源员工的时间',
    version                BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at             DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by             BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at             DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by             BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at             DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by             BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason          VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_external_staff_binding PRIMARY KEY (id),
    CONSTRAINT uk_iam_external_staff_binding_source UNIQUE
        (tenant_id, source_system, source_tenant_key, source_staff_id),
    CONSTRAINT ck_iam_external_staff_binding_system CHECK (source_system IN ('DINGHUOBAO')),
    CONSTRAINT ck_iam_external_staff_binding_presence CHECK (source_presence IN ('PRESENT', 'MISSING')),
    CONSTRAINT fk_iam_external_staff_binding_staff FOREIGN KEY (tenant_id, staff_id)
        REFERENCES iam_staff_profile (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_external_staff_binding_staff (tenant_id, staff_id, source_presence),
    INDEX idx_iam_external_staff_binding_seen (tenant_id, source_system, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部员工来源绑定与订货宝字段快照';

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@staff_page, @app_system_admin, @organization_menu,
     'SYSTEM_ADMIN.PAGE.STAFF_LIST', 'PAGE', NULL,
     '人员管理', 20, 'ACTIVE', @changed_at, @changed_at),
    (@staff_read, @app_system_admin, @staff_page,
     'SYSTEM_ADMIN.API.STAFF_READ', 'API', 'iam:staff:read',
     '查询人员', 10, 'ACTIVE', @changed_at, @changed_at),
    (@staff_write, @app_system_admin, @staff_page,
     'SYSTEM_ADMIN.API.STAFF_WRITE', 'API', 'iam:staff:write',
     '维护人员', 20, 'ACTIVE', @changed_at, @changed_at),
    (@staff_sync, @app_system_admin, @staff_page,
     'SYSTEM_ADMIN.API.STAFF_SYNC', 'API', 'iam:staff:sync',
     '同步外部员工', 30, 'ACTIVE', @changed_at, @changed_at),
    (@position_page, @app_system_admin, @organization_menu,
     'SYSTEM_ADMIN.PAGE.POSITION_LIST', 'PAGE', NULL,
     '岗位管理', 30, 'ACTIVE', @changed_at, @changed_at),
    (@position_read, @app_system_admin, @position_page,
     'SYSTEM_ADMIN.API.POSITION_READ', 'API', 'iam:position:read',
     '查询岗位', 10, 'ACTIVE', @changed_at, @changed_at),
    (@position_write, @app_system_admin, @position_page,
     'SYSTEM_ADMIN.API.POSITION_WRITE', 'API', 'iam:position:write',
     '维护岗位', 20, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@staff_page, 'system.staff.list', '/system-admin/staff', NULL, 1, 0, @changed_at, @changed_at),
    (@position_page, 'system.position.list', '/system-admin/positions', NULL, 1, 0, @changed_at, @changed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL
FROM (
    SELECT @staff_page AS resource_id UNION ALL SELECT @staff_read UNION ALL SELECT @staff_write
    UNION ALL SELECT @staff_sync UNION ALL SELECT @position_page UNION ALL SELECT @position_read
    UNION ALL SELECT @position_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @staff_page AS resource_id UNION ALL SELECT @staff_read UNION ALL SELECT @staff_write
    UNION ALL SELECT @staff_sync UNION ALL SELECT @position_page UNION ALL SELECT @position_read
    UNION ALL SELECT @position_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

INSERT INTO iam_tenant_menu_config (
    tenant_id, resource_id, visible, created_at, created_by, updated_at, updated_by
)
SELECT DISTINCT subscription.tenant_id, resource_record.id, resource_ui.visible,
       @changed_at, NULL, @changed_at, NULL
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
   AND resource_record.resource_type IN ('MENU', 'PAGE')
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
   AND resource_record.id IN (@staff_page, @position_page)
ON DUPLICATE KEY UPDATE visible = VALUES(visible), updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V57__iam_role_permission_field_comments.sql
-- IAM V57：补齐角色、用户角色、资源授权和数据范围字段描述。
--
-- 业务口径：
-- 1. 角色和权限以我方IAM为准，订货宝角色不直接映射成我方权限。
-- 2. 自定义角色由系统生成 JSyyyyMMdd#### 编码；系统角色仍由IAM迁移和初始化维护。
-- 3. iam_role_resource 是角色权限事实表；页面只展示业务化分组，数据库保留完整授权审计链路。

SET @changed_at = TIMESTAMP('2026-08-22 23:30:00.000000');

UPDATE iam_resource
   SET display_name = '角色权限',
       updated_at = @changed_at,
       version = version + 1
 WHERE resource_code IN ('SYSTEM_ADMIN.MENU.ROLE', 'SYSTEM_ADMIN.PAGE.ROLE_LIST')
   AND display_name <> '角色权限';

ALTER TABLE iam_role
    MODIFY COLUMN id            BINARY(16)      NOT NULL COMMENT '角色ID，UUID二进制存储',
    MODIFY COLUMN tenant_id     BINARY(16)      NOT NULL COMMENT '租户ID',
    MODIFY COLUMN role_code     VARCHAR(64)     NOT NULL COMMENT '角色编码；系统角色为稳定系统编码，自定义角色由IAM编码规则生成',
    MODIFY COLUMN role_name     VARCHAR(128)    NOT NULL COMMENT '角色名称，例如仓库管理员、财务管理员',
    MODIFY COLUMN role_type     VARCHAR(32)     NOT NULL COMMENT '角色类型：SYSTEM系统保护，CUSTOM租户自定义',
    MODIFY COLUMN description   VARCHAR(500)    NULL COMMENT '角色说明或适用场景',
    MODIFY COLUMN status        VARCHAR(32)     NOT NULL COMMENT '角色状态：ACTIVE启用，DISABLED停用',
    MODIFY COLUMN version       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    MODIFY COLUMN created_at    DATETIME(6)     NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by    BINARY(16)      NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at    DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by    BINARY(16)      NULL COMMENT '最后更新人用户ID',
    MODIFY COLUMN deleted_at    DATETIME(6)     NULL COMMENT '逻辑删除时间',
    MODIFY COLUMN deleted_by    BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    MODIFY COLUMN delete_reason VARCHAR(255)    NULL COMMENT '逻辑删除原因';

ALTER TABLE iam_role COMMENT = '租户角色主档';

ALTER TABLE iam_user_role
    MODIFY COLUMN tenant_id      BINARY(16)  NOT NULL COMMENT '租户ID',
    MODIFY COLUMN user_id        BINARY(16)  NOT NULL COMMENT 'IAM登录账号ID',
    MODIFY COLUMN role_id        BINARY(16)  NOT NULL COMMENT '角色ID',
    MODIFY COLUMN status         VARCHAR(32) NOT NULL COMMENT '用户角色关系状态：ACTIVE有效，INACTIVE失效',
    MODIFY COLUMN effective_from DATETIME(6) NOT NULL COMMENT '角色分配生效时间',
    MODIFY COLUMN effective_to   DATETIME(6) NULL COMMENT '角色分配失效时间，NULL表示当前有效',
    MODIFY COLUMN created_at     DATETIME(6) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by     BINARY(16)  NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at     DATETIME(6) NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by     BINARY(16)  NULL COMMENT '最后更新人用户ID';

ALTER TABLE iam_user_role COMMENT = '用户与角色分配关系';

ALTER TABLE iam_role_resource
    MODIFY COLUMN tenant_id   BINARY(16)  NOT NULL COMMENT '租户ID',
    MODIFY COLUMN role_id     BINARY(16)  NOT NULL COMMENT '角色ID',
    MODIFY COLUMN resource_id BINARY(16)  NOT NULL COMMENT '资源ID，指向我方应用、菜单、页面、按钮或API资源',
    MODIFY COLUMN status      VARCHAR(32) NOT NULL COMMENT '授权状态：ACTIVE有效，INACTIVE失效',
    MODIFY COLUMN created_at  DATETIME(6) NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by  BINARY(16)  NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at  DATETIME(6) NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by  BINARY(16)  NULL COMMENT '最后更新人用户ID';

ALTER TABLE iam_role_resource COMMENT = '角色与资源授权关系';

ALTER TABLE iam_data_scope_policy
    MODIFY COLUMN id             BINARY(16)      NOT NULL COMMENT '数据范围策略ID，UUID二进制存储',
    MODIFY COLUMN tenant_id      BINARY(16)      NOT NULL COMMENT '租户ID',
    MODIFY COLUMN role_id        BINARY(16)      NOT NULL COMMENT '适用角色ID',
    MODIFY COLUMN application_id BINARY(16)      NOT NULL COMMENT '适用应用ID',
    MODIFY COLUMN scope_type     VARCHAR(32)     NOT NULL COMMENT '数据范围类型：SELF本人，MY_STORES所属门店，MY_CITY所属城市，MY_REGION所属区域，ALL全部',
    MODIFY COLUMN scope_key      VARCHAR(160)    NOT NULL COMMENT '范围键；同一角色同一应用下唯一',
    MODIFY COLUMN scope_ref      BINARY(16)      NULL COMMENT '范围引用ID，预留给组织、区域或门店等业务对象',
    MODIFY COLUMN status         VARCHAR(32)     NOT NULL COMMENT '策略状态：ACTIVE有效，INACTIVE失效',
    MODIFY COLUMN version        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    MODIFY COLUMN created_at     DATETIME(6)     NOT NULL COMMENT '创建时间',
    MODIFY COLUMN created_by     BINARY(16)      NULL COMMENT '创建人用户ID',
    MODIFY COLUMN updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    MODIFY COLUMN updated_by     BINARY(16)      NULL COMMENT '最后更新人用户ID',
    MODIFY COLUMN deleted_at     DATETIME(6)     NULL COMMENT '逻辑删除时间',
    MODIFY COLUMN deleted_by     BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    MODIFY COLUMN delete_reason  VARCHAR(255)    NULL COMMENT '逻辑删除原因';

ALTER TABLE iam_data_scope_policy COMMENT = '角色维度的数据范围策略';

-- Source: V58__iam_delete_legacy_crm_external_staff_navigation.sql
-- IAM V58：删除旧 CRM 外部员工导航资源。
--
-- 新方案口径：
-- 1. 员工、岗位、角色、组织统一归 IAM 员工中心管理。
-- 2. CRM 只保存业务归属关系中的 iam_staff_code，不再暴露 CRM 外部员工页面。
-- 3. 订货宝员工来源只保留在 iam_external_staff_binding，供同步映射和运维排查使用。

SET @changed_at = TIMESTAMP('2026-08-23 01:10:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @crm_external_staff_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000293');

CREATE TABLE IF NOT EXISTS iam_resource_cleanup_v58 (
    resource_id BINARY(16) NOT NULL,
    CONSTRAINT pk_iam_resource_cleanup_v58 PRIMARY KEY (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='V58待删除旧CRM外部员工导航资源ID';

TRUNCATE TABLE iam_resource_cleanup_v58;

INSERT IGNORE INTO iam_resource_cleanup_v58 (resource_id)
SELECT resource_record.id
  FROM iam_resource resource_record
  JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
 WHERE resource_record.application_id = @app_supply_chain
   AND (resource_record.id = @crm_external_staff_page
        OR ui_record.route_key = 'supply.crm.assignments.external-staff'
        OR ui_record.route_path = '/supply-chain/crm/assignments/external-staff');

DELETE role_resource
  FROM iam_role_resource role_resource
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = role_resource.resource_id;

DELETE package_resource
  FROM iam_package_resource package_resource
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = package_resource.resource_id;

DELETE menu_config
  FROM iam_tenant_menu_config menu_config
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = menu_config.resource_id;

UPDATE iam_resource child_resource
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = child_resource.parent_id
   SET child_resource.parent_id = NULL,
       child_resource.version = child_resource.version + 1,
       child_resource.updated_at = @changed_at;

DELETE resource_ui
  FROM iam_resource_ui resource_ui
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = resource_ui.resource_id;

DELETE resource_record
  FROM iam_resource resource_record
  JOIN iam_resource_cleanup_v58 cleanup
    ON cleanup.resource_id = resource_record.id;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

DROP TABLE iam_resource_cleanup_v58;

-- Source: V59__iam_audit_log_service_actor_scope.sql
-- IAM V59：允许服务间可信调用写入审计日志。
--
-- 订货宝统一同步由 Integration 以 SERVICE 身份调用 IAM/ERP/CRM/Order 内部接口。
-- 旧审计约束只允许 SYSTEM，导致 IAM 人员同步完成后写审计失败，整批订货宝编排变为 FAILED。

ALTER TABLE iam_audit_log
    DROP CHECK ck_iam_audit_actor_scope;

ALTER TABLE iam_audit_log
    ADD CONSTRAINT ck_iam_audit_actor_scope
        CHECK (actor_scope IN ('PLATFORM', 'TENANT', 'ANONYMOUS', 'SYSTEM', 'SERVICE'));

-- Source: V60__iam_order_sales_shipment_navigation.sql
-- IAM V60：启用 Order 新方案销售发货单页面。
-- 只恢复发货单业务入口，不恢复旧订货宝发货/物流页面实现。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @order_shipment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @order_shipment_page,
    @app_supply_chain,
    @order_root_menu,
    'SUPPLY_CHAIN.PAGE.ORDER_SALES_SHIPMENTS',
    'PAGE',
    NULL,
    '发货单',
    20,
    'ACTIVE',
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @order_shipment_page,
    'supply.order.shipments',
    '/supply-chain/order/shipments',
    'Truck',
    1,
    0,
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @order_shipment_page, @changed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @order_shipment_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_role_resource rr
   SET rr.status = 'ACTIVE',
       rr.updated_at = @changed_at
 WHERE rr.resource_id = @order_shipment_page;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @order_shipment_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @order_sales_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    updated_at = VALUES(updated_at);

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource shipment_grant
        WHERE shipment_grant.tenant_id = tenant_record.id
          AND shipment_grant.resource_id = @order_shipment_page
          AND shipment_grant.status = 'ACTIVE'
   );

-- Source: V61__iam_order_sales_payment_navigation.sql
-- Order 新增销售回款页面；回款属于自研订单流程，不暴露订货宝旧收款页面。

SET @seed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @sales_order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @payment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000340');

INSERT INTO iam_resource (id, application_id, parent_id, resource_code, resource_type, permission_code, display_name, sort_order, status, created_at, updated_at)
VALUES (@payment_page, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SALES_PAYMENTS', 'PAGE', NULL, '销售回款', 35, 'ACTIVE', @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @seed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @payment_page, 'supply.order.sales-payments',
    '/supply-chain/order/sales-payments', 'WalletCards',
    1, 0, @seed_at, @seed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @payment_page, @seed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @payment_page, 1, @seed_at, @seed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @payment_page,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @sales_order_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource payment_grant
        WHERE payment_grant.tenant_id = tenant_record.id
          AND payment_grant.resource_id = @payment_page
          AND payment_grant.status = 'ACTIVE'
   );

-- Source: V62__iam_order_sales_refund_navigation.sql
-- Order 新增销售退款页面；订货宝 getPaymentList 只作为来源数据，不暴露旧付款单镜像页。

SET @seed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @sales_order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @refund_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000341');

INSERT INTO iam_resource (id, application_id, parent_id, resource_code, resource_type, permission_code, display_name, sort_order, status, created_at, updated_at)
VALUES (@refund_page, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SALES_REFUNDS', 'PAGE', NULL, '销售退款', 40, 'ACTIVE', @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @seed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @refund_page, 'supply.order.sales-refunds',
    '/supply-chain/order/sales-refunds', 'Undo2',
    1, 0, @seed_at, @seed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @refund_page, @seed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @refund_page, 1, @seed_at, @seed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @refund_page,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @sales_order_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource refund_grant
        WHERE refund_grant.tenant_id = tenant_record.id
          AND refund_grant.resource_id = @refund_page
          AND refund_grant.status = 'ACTIVE'
   );

-- Source: V63__iam_product_specification_role_grants.sql
-- IAM V63：补齐 ERP 商品规格页面的角色授权。
--
-- V53 已经新增自研“商品规格”页面资源、路由和租户菜单配置；这里补齐角色授权，
-- 避免资源存在但登录角色拿不到菜单。授权边界沿用“商品管理”页面，不扩大到无商品权限角色。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @legacy_specification_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000172');
SET @product_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000168');
SET @product_specification_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000302');

-- 旧“规格与包装/订货宝规格档案”页曾经占用过新商品规格 routeKey。
-- route_key 有唯一约束，必须先释放旧页，否则新页不会真正拿到 UI 路由。
UPDATE iam_resource_ui
   SET route_key = 'supply.erp.master-data.legacy-specifications',
       route_path = NULL,
       visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @legacy_specification_page
   AND route_key = 'supply.erp.master-data.attributes.specifications';

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @legacy_specification_page;

UPDATE iam_resource
   SET parent_id = UUID_TO_BIN('019facf2-0000-7000-8000-000000000167'),
       display_name = '商品规格',
       sort_order = 50,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @product_specification_page;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @product_specification_page,
    'supply.erp.master-data.attributes.specifications',
    '/supply-chain/erp/master-data/attributes/specifications',
    NULL,
    1,
    0,
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config
   SET visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @product_specification_page;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @product_specification_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @product_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource specification_grant
        WHERE specification_grant.tenant_id = tenant_record.id
          AND specification_grant.resource_id = @product_specification_page
          AND specification_grant.status = 'ACTIVE'
   );

-- Source: V64__iam_order_fund_document_navigation.sql
-- Order 新增资金收付款页面；订货宝收款单/付款单映射到自研资金单据，不再把付款单暴露成销售退款。

SET @seed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @sales_order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @fund_document_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000342');

INSERT INTO iam_resource (id, application_id, parent_id, resource_code, resource_type, permission_code, display_name, sort_order, status, created_at, updated_at)
VALUES (@fund_document_page, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_FUND_DOCUMENTS', 'PAGE', NULL, '资金收付款', 38, 'ACTIVE', @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @seed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @fund_document_page, 'supply.order.fund-documents',
    '/supply-chain/order/fund-documents', 'Wallet',
    1, 0, @seed_at, @seed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @fund_document_page, @seed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @fund_document_page, 1, @seed_at, @seed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @fund_document_page,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @sales_order_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource fund_document_grant
        WHERE fund_document_grant.tenant_id = tenant_record.id
          AND fund_document_grant.resource_id = @fund_document_page
          AND fund_document_grant.status = 'ACTIVE'
   );

-- Source: V65__iam_order_fund_document_display_name.sql
-- Order 客户资金流水页面命名修正；V64 已在共享 DEV 以“资金收付款”执行，本迁移做后续演进。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @fund_document_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000342');

UPDATE iam_resource
   SET display_name = '客户资金流水',
       sort_order = 38,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @fund_document_page
   AND (
       display_name <> '客户资金流水'
       OR sort_order <> 38
       OR status <> 'ACTIVE'
   );

UPDATE iam_resource_ui
   SET route_key = 'supply.order.fund-documents',
       route_path = '/supply-chain/order/fund-documents',
       icon_key = 'Wallet',
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @fund_document_page
   AND (
       route_key <> 'supply.order.fund-documents'
       OR route_path <> '/supply-chain/order/fund-documents'
       OR icon_key <> 'Wallet'
       OR visible <> 1
       OR keep_alive <> 0
   );

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id = @fund_document_page
          AND menu_config.visible = 1
   );

-- Source: V66__iam_workbench_app_navigation_and_collaboration.sql
-- IAM V66：重建门户工作台应用导航与协作权限。
--
-- 共享 DEV 已执行过同名迁移，但源文件缺失；本文件按共享 DEV 当前已落地的业务状态重建，
-- 用于 Flyway repair 后恢复源码与数据库历史的一致性。

SET @seed_at = TIMESTAMP('2026-08-20 18:00:00.000000');
SET @app_workbench = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @workbench_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @workbench_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000343');
SET @workbench_attendance = UUID_TO_BIN('019facf2-0000-7000-8000-000000000344');
SET @workbench_targets = UUID_TO_BIN('019facf2-0000-7000-8000-000000000345');
SET @workbench_visit = UUID_TO_BIN('019facf2-0000-7000-8000-000000000346');
SET @workbench_track = UUID_TO_BIN('019facf2-0000-7000-8000-000000000347');
SET @workbench_chat = UUID_TO_BIN('019facf2-0000-7000-8000-000000000348');
SET @workbench_profile = UUID_TO_BIN('019facf2-0000-7000-8000-000000000349');
SET @workbench_collaboration_im = UUID_TO_BIN('019facf2-0000-7000-8000-00000000034a');
SET @workbench_collaboration_meeting = UUID_TO_BIN('019facf2-0000-7000-8000-00000000034b');

UPDATE iam_application
   SET app_name = '门户工作台',
       app_type = 'EXTERNAL',
       icon_key = 'app-workbench',
       sort_order = 50,
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       status = 'ACTIVE',
       version = CASE
           WHEN NOT (app_name <=> '门户工作台')
             OR NOT (app_type <=> 'EXTERNAL')
             OR NOT (icon_key <=> 'app-workbench')
             OR NOT (sort_order <=> 50)
             OR NOT (launch_mode <=> 'FEISHU_DEEPLINK')
             OR NOT (target_uri <=> '/sales-workbench')
             OR NOT (status <=> 'ACTIVE')
           THEN version + 1
           ELSE version
       END,
       updated_at = @seed_at
 WHERE id = @app_workbench
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET resource_code = 'WORKBENCH.ROOT',
       display_name = '门户工作台',
       sort_order = 10,
       status = 'ACTIVE',
       version = CASE
           WHEN NOT (resource_code <=> 'WORKBENCH.ROOT')
             OR NOT (display_name <=> '门户工作台')
             OR NOT (sort_order <=> 10)
             OR NOT (status <=> 'ACTIVE')
           THEN version + 1
           ELSE version
       END,
       updated_at = @seed_at
 WHERE id = @workbench_root
   AND application_id = @app_workbench;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, version, created_at, updated_at
) VALUES
    (@workbench_home, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.HOME', 'PAGE', NULL, '工作台', 10, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_attendance, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.ATTENDANCE', 'PAGE', NULL, '外勤考勤', 20, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_targets, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.TARGETS', 'PAGE', NULL, '客户与门店', 30, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_visit, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.VISIT', 'PAGE', NULL, '拜访打卡', 40, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_track, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.TRACK', 'PAGE', NULL, '工作记录', 50, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_chat, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.CHAT', 'PAGE', NULL, '内部沟通', 60, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_profile, @app_workbench, @workbench_root,
     'WORKBENCH.PAGE.PROFILE', 'PAGE', NULL, '个人', 70, 'ACTIVE', 1, @seed_at, @seed_at),
    (@workbench_collaboration_im, @app_workbench, @workbench_root,
     'WORKBENCH.API.COLLABORATION_IM', 'API', 'collaboration:im:use',
     '使用内部IM', 80, 'ACTIVE', 0, @seed_at, @seed_at),
    (@workbench_collaboration_meeting, @app_workbench, @workbench_root,
     'WORKBENCH.API.COLLABORATION_MEETING', 'API', 'collaboration:meeting:use',
     '使用语音会议', 90, 'ACTIVE', 0, @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = VALUES(status),
    version = VALUES(version),
    updated_at = VALUES(updated_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@workbench_home, 'workbench.home', '/home', 'home-o', 1, 1, @seed_at, @seed_at),
    (@workbench_attendance, 'workbench.attendance', '/attendance', 'clock-o', 1, 1, @seed_at, @seed_at),
    (@workbench_targets, 'workbench.targets', '/targets', 'shop-o', 1, 0, @seed_at, @seed_at),
    (@workbench_visit, 'workbench.visit', '/visit', 'friends-o', 1, 0, @seed_at, @seed_at),
    (@workbench_track, 'workbench.track', '/track', 'location-o', 1, 1, @seed_at, @seed_at),
    (@workbench_chat, 'workbench.chat', '/chat', 'chat-o', 1, 1, @seed_at, @seed_at),
    (@workbench_profile, 'workbench.profile', '/profile', 'user-o', 1, 1, @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = VALUES(visible),
    keep_alive = VALUES(keep_alive),
    updated_at = VALUES(updated_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, workbench_resource.resource_id, @seed_at, NULL
  FROM (
    SELECT @workbench_root AS resource_id
    UNION ALL SELECT @workbench_home
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
    UNION ALL SELECT @workbench_collaboration_im
    UNION ALL SELECT @workbench_collaboration_meeting
  ) workbench_resource
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, workbench_page.resource_id, 1, @seed_at, @seed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @workbench_home AS resource_id
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
  ) workbench_page
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, workbench_resource.resource_id,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @workbench_root AS resource_id
    UNION ALL SELECT @workbench_home
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
    UNION ALL SELECT @workbench_collaboration_im
    UNION ALL SELECT @workbench_collaboration_meeting
  ) workbench_resource
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id IN (
              @workbench_home,
              @workbench_attendance,
              @workbench_targets,
              @workbench_visit,
              @workbench_track,
              @workbench_chat,
              @workbench_profile
          )
          AND menu_config.visible = 1
   );

-- Source: V67__iam_correct_workbench_authorization_metadata.sql
-- IAM V67：校正门户工作台授权元数据。
--
-- V66 已建立 Workbench 页面与协作权限；本迁移按共享 DEV 已执行历史补齐一次
-- 应用、资源、套餐、租户菜单和超级管理员授权的幂等校正。

SET @changed_at = TIMESTAMP('2026-08-24 17:15:34.000000');
SET @app_workbench = UUID_TO_BIN('019facf1-0000-7000-8000-000000000005');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @workbench_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000070');
SET @workbench_home = UUID_TO_BIN('019facf2-0000-7000-8000-000000000343');
SET @workbench_attendance = UUID_TO_BIN('019facf2-0000-7000-8000-000000000344');
SET @workbench_targets = UUID_TO_BIN('019facf2-0000-7000-8000-000000000345');
SET @workbench_visit = UUID_TO_BIN('019facf2-0000-7000-8000-000000000346');
SET @workbench_track = UUID_TO_BIN('019facf2-0000-7000-8000-000000000347');
SET @workbench_chat = UUID_TO_BIN('019facf2-0000-7000-8000-000000000348');
SET @workbench_profile = UUID_TO_BIN('019facf2-0000-7000-8000-000000000349');
SET @workbench_collaboration_im = UUID_TO_BIN('019facf2-0000-7000-8000-00000000034a');
SET @workbench_collaboration_meeting = UUID_TO_BIN('019facf2-0000-7000-8000-00000000034b');

UPDATE iam_application
   SET app_name = '门户工作台',
       app_type = 'EXTERNAL',
       icon_key = 'app-workbench',
       sort_order = 50,
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       status = 'ACTIVE',
       updated_at = @changed_at
 WHERE id = @app_workbench
   AND app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET resource_code = 'WORKBENCH.ROOT',
       display_name = '门户工作台',
       sort_order = 10,
       status = 'ACTIVE',
       updated_at = @changed_at
 WHERE id = @workbench_root
   AND application_id = @app_workbench;

UPDATE iam_resource
   SET parent_id = @workbench_root,
       resource_type = 'PAGE',
       permission_code = NULL,
       display_name = CASE id
           WHEN @workbench_home THEN '工作台'
           WHEN @workbench_attendance THEN '外勤考勤'
           WHEN @workbench_targets THEN '客户与门店'
           WHEN @workbench_visit THEN '拜访打卡'
           WHEN @workbench_track THEN '工作记录'
           WHEN @workbench_chat THEN '内部沟通'
           WHEN @workbench_profile THEN '个人'
           ELSE display_name
       END,
       sort_order = CASE id
           WHEN @workbench_home THEN 10
           WHEN @workbench_attendance THEN 20
           WHEN @workbench_targets THEN 30
           WHEN @workbench_visit THEN 40
           WHEN @workbench_track THEN 50
           WHEN @workbench_chat THEN 60
           WHEN @workbench_profile THEN 70
           ELSE sort_order
       END,
       status = 'ACTIVE',
       updated_at = @changed_at
 WHERE application_id = @app_workbench
   AND id IN (
       @workbench_home,
       @workbench_attendance,
       @workbench_targets,
       @workbench_visit,
       @workbench_track,
       @workbench_chat,
       @workbench_profile
   );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, workbench_resource.resource_id, @changed_at, NULL
  FROM (
    SELECT @workbench_root AS resource_id
    UNION ALL SELECT @workbench_home
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
    UNION ALL SELECT @workbench_collaboration_im
    UNION ALL SELECT @workbench_collaboration_meeting
  ) workbench_resource
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, workbench_page.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @workbench_home AS resource_id
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
  ) workbench_page
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, workbench_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @workbench_root AS resource_id
    UNION ALL SELECT @workbench_home
    UNION ALL SELECT @workbench_attendance
    UNION ALL SELECT @workbench_targets
    UNION ALL SELECT @workbench_visit
    UNION ALL SELECT @workbench_track
    UNION ALL SELECT @workbench_chat
    UNION ALL SELECT @workbench_profile
    UNION ALL SELECT @workbench_collaboration_im
    UNION ALL SELECT @workbench_collaboration_meeting
  ) workbench_resource
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id IN (
              @workbench_home,
              @workbench_attendance,
              @workbench_targets,
              @workbench_visit,
              @workbench_track,
              @workbench_chat,
              @workbench_profile
          )
          AND menu_config.visible = 1
   );

-- Source: V68__iam_crm_customer_directory_navigation.sql
-- IAM V68：恢复 CRM 客户地址、客户类型、归属地区入口。
--
-- 后端已存在客户地址簿、客户类型、客户归属地区查询契约；本迁移只恢复这些已接入页面，
-- 不恢复门店档案、客户360、外部员工或信用政策等仍未接入业务页的旧入口。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

SET @crm_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000053');
SET @crm_customers_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000199');
SET @crm_customer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000200');
SET @crm_customer_type_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000202');
SET @crm_customer_area_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000292');
SET @crm_shipping_address_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000294');

UPDATE iam_resource
   SET display_name = 'CRM',
       sort_order = 30,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_root_menu;

UPDATE iam_resource
   SET parent_id = @crm_root_menu,
       display_name = '客户管理',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customers_menu;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '客户档案',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_page;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '客户地址',
       sort_order = 20,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_shipping_address_page;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '客户类型',
       sort_order = 30,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_type_page;

UPDATE iam_resource
   SET parent_id = @crm_customers_menu,
       display_name = '归属地区',
       sort_order = 40,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @crm_customer_area_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.menu',
       route_path = NULL,
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customers_menu;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.profiles',
       route_path = '/supply-chain/crm/customers/profiles',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.shipping-addresses',
       route_path = '/supply-chain/crm/customers/shipping-addresses',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_shipping_address_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.levels-tags',
       route_path = '/supply-chain/crm/customers/levels-tags',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_type_page;

UPDATE iam_resource_ui
   SET route_key = 'supply.crm.customers.areas',
       route_path = '/supply-chain/crm/customers/areas',
       icon_key = NULL,
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_customer_area_page;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, crm_resource.resource_id, @changed_at, NULL
  FROM (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, crm_resource.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, crm_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
 WHERE existing_grant.resource_id = @crm_customer_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, crm_resource.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @crm_customers_menu AS resource_id
    UNION ALL SELECT @crm_customer_page
    UNION ALL SELECT @crm_shipping_address_page
    UNION ALL SELECT @crm_customer_type_page
    UNION ALL SELECT @crm_customer_area_page
  ) crm_resource
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id IN (
              @crm_customers_menu,
              @crm_customer_page,
              @crm_shipping_address_page,
              @crm_customer_type_page,
              @crm_customer_area_page
          )
          AND menu_config.visible = 1
   );

-- Source: V69__iam_hide_crm_workspace_navigation.sql
-- IAM V69：删除侧栏“CRM 工作台”入口。
--
-- 该页面是 CRM 根域的早期工作台入口；当前侧栏只保留“客户管理”下已接入的客户档案、客户地址、
-- 客户类型和归属地区页面。资源和授权关系继续保留，避免破坏历史直达链接和权限数据。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @crm_workspace_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000054');

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_workspace_page
   AND route_key = 'supply.crm.index';

UPDATE iam_tenant_menu_config
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @crm_workspace_page;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id = @crm_workspace_page
          AND menu_config.visible = 0
   );

-- Source: V70__iam_analytics_bi_supply_dashboard_permissions.sql
-- IAM V70：供应链 BI 看板独立权限。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@analytics_dashboard_read, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_SUPPLY_DASHBOARD_READ', 'API',
     'analytics:dashboard:read', '查询供应链BI看板', 10, 'ACTIVE', @changed_at, @changed_at),
    (@analytics_refresh_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_SUPPLY_REFRESH_WRITE', 'API',
     'analytics:refresh:write', '刷新供应链BI数据', 20, 'ACTIVE', @changed_at, @changed_at),
    (@analytics_city_cost_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_CITY_COST_WRITE', 'API',
     'analytics:city-cost:write', '导入城市端成本', 30, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL
  FROM (
    SELECT @analytics_dashboard_read AS resource_id
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @analytics_dashboard_read,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @bi_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @analytics_dashboard_read AS resource_id
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V71__iam_analytics_bi_legacy_archive_permissions.sql
-- IAM V71：供应链 BI 飞书旧看板归档管理权限。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @analytics_legacy_archive_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000363');
SET @analytics_legacy_archive_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000364');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@analytics_legacy_archive_read, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_LEGACY_ARCHIVE_READ', 'API',
     'analytics:legacy-archive:read', '查询飞书旧看板归档', 40, 'ACTIVE', @changed_at, @changed_at),
    (@analytics_legacy_archive_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.ANALYTICS_LEGACY_ARCHIVE_WRITE', 'API',
     'analytics:legacy-archive:write', '登记飞书旧看板归档', 50, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL
  FROM (
    SELECT @analytics_legacy_archive_read AS resource_id
    UNION ALL SELECT @analytics_legacy_archive_write
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @analytics_legacy_archive_read AS resource_id
    UNION ALL SELECT @analytics_legacy_archive_write
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;

-- Source: V72__iam_restore_supply_bi_dashboard_navigation.sql
-- IAM V72：恢复已实现的供应链 BI 看板入口，同时继续隐藏旧 BI 占位页面。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @supply_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

-- 旧 BI 分组下有规划期占位页面，本次只恢复真实看板页面。
UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = 0,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key <> 'supply.bi.index';

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @bi_page, @app_supply_chain, @supply_root,
    'SUPPLY_CHAIN.PAGE.BI_INDEX', 'PAGE', NULL,
    'BI 数据看板', 60, 'ACTIVE', @changed_at, @changed_at
) ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @bi_page, 'supply.bi.index', '/supply-chain/bi', 'TrendCharts', 1, 0, @changed_at, @changed_at
) ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, package_resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) package_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @bi_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = 0,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key <> 'supply.bi.index';

-- 已有看板查询权限的角色补齐可导航页面。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT dashboard_grant.tenant_id, dashboard_grant.role_id, @bi_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource dashboard_grant
 WHERE dashboard_grant.resource_id = @analytics_dashboard_read
   AND dashboard_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

-- 已有页面授权的角色补齐看板接口查询权限。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT page_grant.tenant_id, page_grant.role_id, @analytics_dashboard_read,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource page_grant
 WHERE page_grant.resource_id = @bi_page
   AND page_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V73__iam_split_supply_bi_dashboard_navigation.sql
-- IAM V73：将供应链 BI 单页拆成业务可识别的数据看板菜单。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @supply_root = UUID_TO_BIN('019facf2-0000-7000-8000-000000000049');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_sales_collection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000370');
SET @bi_product_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000371');
SET @bi_city_cost_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000372');
SET @bi_inventory_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000373');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_menu, @app_supply_chain, @supply_root,
     'SUPPLY_CHAIN.MENU.BI', 'MENU', NULL, '数据看板', 60, 'ACTIVE', @changed_at, @changed_at),
    (@bi_overview_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_OVERVIEW', 'PAGE', NULL, '供应链经营总览', 10, 'ACTIVE', @changed_at, @changed_at),
    (@bi_sales_collection_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES_COLLECTION', 'PAGE', NULL, '销售与回款看板', 20, 'ACTIVE', @changed_at, @changed_at),
    (@bi_product_sales_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_PRODUCT_SALES', 'PAGE', NULL, '商品销售统计', 30, 'ACTIVE', @changed_at, @changed_at),
    (@bi_city_cost_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_CITY_COST', 'PAGE', NULL, '城市成本看板', 40, 'ACTIVE', @changed_at, @changed_at),
    (@bi_inventory_risk_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_INVENTORY_RISK', 'PAGE', NULL, '库存风险看板', 50, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_menu, 'supply.bi.menu', NULL, 'TrendCharts', 1, 0, @changed_at, @changed_at),
    (@bi_overview_page, 'supply.bi.index', '/supply-chain/bi', 'DataLine', 1, 0, @changed_at, @changed_at),
    (@bi_sales_collection_page, 'supply.bi.sales-collection', '/supply-chain/bi/sales-collection', 'Money', 1, 0, @changed_at, @changed_at),
    (@bi_product_sales_page, 'supply.bi.product-sales', '/supply-chain/bi/product-sales', 'Goods', 1, 0, @changed_at, @changed_at),
    (@bi_city_cost_page, 'supply.bi.city-cost', '/supply-chain/bi/city-cost', 'Coin', 1, 0, @changed_at, @changed_at),
    (@bi_inventory_risk_page, 'supply.bi.inventory-risk', '/supply-chain/bi/inventory-risk', 'Warning', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    updated_at = @changed_at;

UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = 0,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key NOT IN (
       'supply.bi.menu',
       'supply.bi.index',
       'supply.bi.sales-collection',
       'supply.bi.product-sales',
       'supply.bi.city-cost',
       'supply.bi.inventory-risk'
   );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, package_resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) package_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, visible_resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
 ) visible_resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = 0,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key NOT IN (
       'supply.bi.menu',
       'supply.bi.index',
       'supply.bi.sales-collection',
       'supply.bi.product-sales',
       'supply.bi.city-cost',
       'supply.bi.inventory-risk'
   );

-- 已有看板查询权限或旧 BI 页面权限的角色，补齐新的业务看板页面。
INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, visible_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
 ) visible_resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_collection_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V74__iam_supply_bi_gross_profit_and_payment_risk_navigation.sql
-- IAM V74：补齐供应链 BI 毛利分析和回款风险看板导航。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_city_cost_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000372');
SET @bi_inventory_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000373');
SET @bi_gross_profit_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000374');
SET @bi_payment_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000375');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_gross_profit_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_GROSS_PROFIT', 'PAGE', NULL, '销售毛利分析', 40, 'ACTIVE', @changed_at, @changed_at),
    (@bi_payment_risk_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_PAYMENT_RISK', 'PAGE', NULL, '回款风险看板', 50, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET sort_order = CASE id
       WHEN @bi_city_cost_page THEN 60
       WHEN @bi_inventory_risk_page THEN 70
       ELSE sort_order
   END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@bi_city_cost_page, @bi_inventory_risk_page);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_gross_profit_page, 'supply.bi.gross-profit', '/supply-chain/bi/gross-profit', 'Histogram', 1, 0, @changed_at, @changed_at),
    (@bi_payment_risk_page, 'supply.bi.payment-risk', '/supply-chain/bi/payment-risk', 'Warning', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    updated_at = @changed_at;

UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = 0,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key NOT IN (
       'supply.bi.menu',
       'supply.bi.index',
       'supply.bi.sales-collection',
       'supply.bi.product-sales',
       'supply.bi.gross-profit',
       'supply.bi.payment-risk',
       'supply.bi.city-cost',
       'supply.bi.inventory-risk'
   );

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_gross_profit_page AS resource_id
    UNION ALL SELECT @bi_payment_risk_page
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @bi_gross_profit_page AS resource_id
    UNION ALL SELECT @bi_payment_risk_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = 0,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%'
   AND resource_ui.route_key NOT IN (
       'supply.bi.menu',
       'supply.bi.index',
       'supply.bi.sales-collection',
       'supply.bi.product-sales',
       'supply.bi.gross-profit',
       'supply.bi.payment-risk',
       'supply.bi.city-cost',
       'supply.bi.inventory-risk'
   );

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_gross_profit_page AS resource_id
    UNION ALL SELECT @bi_payment_risk_page
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_gross_profit_page AS resource_id
    UNION ALL SELECT @bi_payment_risk_page
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V74_1__iam_prepare_v75_sales_resource_identity.sql
-- V75 使用新 ID ...376 插入已由 ...240 占用的 BI_SALES 唯一编码，
-- ON DUPLICATE KEY 只更新旧行，随后 UI 引用不存在的新 ID，导致空库回放失败。
-- 保留 V75 原文件：暂让出唯一编码，V75.1 随即合并回原 ID，供 V76 接续。
-- 已完成 V75 的环境中两步只是可逆的编码过渡，不更改最终导航和授权。
UPDATE iam_resource original
LEFT JOIN iam_resource duplicate ON duplicate.id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000376')
SET original.resource_code='SUPPLY_CHAIN.PAGE.BI_SALES_PRE_V75'
WHERE original.id=UUID_TO_BIN('019facf2-0000-7000-8000-000000000240')
  AND original.resource_code='SUPPLY_CHAIN.PAGE.BI_SALES'
  AND duplicate.id IS NULL;

-- Source: V75__iam_supply_bi_operating_dashboard_navigation.sql
-- IAM V75：按经营总览、销售管理、活动样例、商品库存运营重排供应链 BI 菜单。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_sales_collection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000370');
SET @bi_product_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000371');
SET @bi_city_cost_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000372');
SET @bi_inventory_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000373');
SET @bi_gross_profit_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000374');
SET @bi_payment_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000375');
SET @bi_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000376');
SET @bi_activity_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000377');
SET @bi_product_inventory_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000378');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_sales_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES', 'PAGE', NULL, '销售看板', 20, 'ACTIVE', @changed_at, @changed_at),
    (@bi_activity_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_ACTIVITY', 'PAGE', NULL, '活动看板', 30, 'ACTIVE', @changed_at, @changed_at),
    (@bi_product_inventory_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_PRODUCT_INVENTORY', 'PAGE', NULL, '商品/库存看板', 40, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET display_name = CASE id
       WHEN @bi_overview_page THEN '供应链经营总览'
       WHEN @bi_gross_profit_page THEN '销售毛利分析'
       WHEN @bi_city_cost_page THEN '城市成本看板'
       ELSE display_name
   END,
       sort_order = CASE id
       WHEN @bi_overview_page THEN 10
       WHEN @bi_gross_profit_page THEN 50
       WHEN @bi_city_cost_page THEN 60
       ELSE sort_order
   END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@bi_overview_page, @bi_gross_profit_page, @bi_city_cost_page);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_sales_page, 'supply.bi.sales', '/supply-chain/bi/sales', 'DataAnalysis', 1, 0, @changed_at, @changed_at),
    (@bi_activity_page, 'supply.bi.activity', '/supply-chain/bi/activity', 'Tickets', 1, 0, @changed_at, @changed_at),
    (@bi_product_inventory_page, 'supply.bi.product-inventory', '/supply-chain/bi/product-inventory', 'Goods', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    updated_at = @changed_at;

UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = CASE
       WHEN resource_ui.route_key IN (
           'supply.bi.menu',
           'supply.bi.index',
           'supply.bi.sales',
           'supply.bi.activity',
           'supply.bi.product-inventory',
           'supply.bi.gross-profit',
           'supply.bi.city-cost'
       ) THEN 1 ELSE 0 END,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%';

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @analytics_dashboard_read
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = CASE
       WHEN resource_ui.route_key IN (
           'supply.bi.menu',
           'supply.bi.index',
           'supply.bi.sales',
           'supply.bi.activity',
           'supply.bi.product-inventory',
           'supply.bi.gross-profit',
           'supply.bi.city-cost'
       ) THEN 1 ELSE 0 END,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%';

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_sales_page AS resource_id
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V75_1__iam_restore_canonical_sales_resource_identity.sql
-- 仅处理 V74.1 明确标记的过渡状态；保留原 ID、已有授权与租户配置，不留重复菜单。
SET @canonical_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000240');
SET @duplicate_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000376');
SET @needs_sales_restore = (SELECT COUNT(*) FROM iam_resource
    WHERE id=@canonical_sales AND resource_code='SUPPLY_CHAIN.PAGE.BI_SALES_PRE_V75');

INSERT IGNORE INTO iam_package_resource (package_version_id,resource_id,created_at,created_by)
SELECT package_version_id,@canonical_sales,created_at,created_by FROM iam_package_resource
WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
INSERT IGNORE INTO iam_role_resource
    (tenant_id,role_id,resource_id,status,created_at,created_by,updated_at,updated_by)
SELECT tenant_id,role_id,@canonical_sales,status,created_at,created_by,updated_at,updated_by
FROM iam_role_resource WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
INSERT IGNORE INTO iam_tenant_menu_config
    (tenant_id,resource_id,display_name_override,icon_key_override,sort_order_override,parent_group_id,
     visible,version,created_at,created_by,updated_at,updated_by)
SELECT tenant_id,@canonical_sales,display_name_override,icon_key_override,sort_order_override,parent_group_id,
       visible,version,created_at,created_by,updated_at,updated_by
FROM iam_tenant_menu_config WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;

DELETE FROM iam_role_resource WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_package_resource WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_tenant_menu_config WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_resource_ui WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
UPDATE iam_resource SET parent_id=@canonical_sales
WHERE parent_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_resource WHERE id=@duplicate_sales AND @needs_sales_restore=1;
UPDATE iam_resource SET resource_code='SUPPLY_CHAIN.PAGE.BI_SALES'
WHERE id=@canonical_sales AND @needs_sales_restore=1;

-- Source: V76__iam_restore_supply_bi_operating_dashboard_children.sql
-- IAM V76：补齐经营化 BI 菜单子看板，保持 V75 历史迁移不可变。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_sales_collection_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000370');
SET @bi_product_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000371');
SET @bi_city_cost_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000372');
SET @bi_inventory_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000373');
SET @bi_gross_profit_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000374');
SET @bi_payment_risk_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000375');
SET @bi_duplicate_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000376');
SET @bi_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000240');
SET @bi_activity_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000377');
SET @bi_product_inventory_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000378');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');
SET @analytics_refresh_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000361');
SET @analytics_city_cost_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000362');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_sales_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES', 'PAGE', NULL, '销售看板', 20, 'ACTIVE', @changed_at, @changed_at),
    (@bi_activity_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_ACTIVITY', 'PAGE', NULL, '活动看板', 30, 'ACTIVE', @changed_at, @changed_at),
    (@bi_product_inventory_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_PRODUCT_INVENTORY', 'PAGE', NULL, '商品/库存看板', 50, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET display_name = CASE id
       WHEN @bi_menu THEN '数据看板'
       WHEN @bi_overview_page THEN '供应链经营总览'
       WHEN @bi_product_sales_page THEN '商品销售统计'
       WHEN @bi_gross_profit_page THEN '销售毛利分析'
       WHEN @bi_payment_risk_page THEN '回款风险看板'
       WHEN @bi_city_cost_page THEN '城市成本看板'
       WHEN @bi_inventory_risk_page THEN '库存风险看板'
       ELSE display_name
   END,
       parent_id = CASE
       WHEN id IN (
           @bi_overview_page,
           @bi_product_sales_page,
           @bi_gross_profit_page,
           @bi_payment_risk_page,
           @bi_city_cost_page,
           @bi_inventory_risk_page
       ) THEN @bi_menu ELSE parent_id END,
       sort_order = CASE id
       WHEN @bi_menu THEN 60
       WHEN @bi_overview_page THEN 10
       WHEN @bi_product_sales_page THEN 40
       WHEN @bi_gross_profit_page THEN 60
       WHEN @bi_payment_risk_page THEN 70
       WHEN @bi_city_cost_page THEN 80
       WHEN @bi_inventory_risk_page THEN 90
       ELSE sort_order
   END,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (
    @bi_menu,
    @bi_overview_page,
    @bi_product_sales_page,
    @bi_gross_profit_page,
    @bi_payment_risk_page,
    @bi_city_cost_page,
    @bi_inventory_risk_page
);

UPDATE iam_resource
   SET status = 'DISABLED',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @bi_duplicate_sales_page
   AND resource_code = 'SUPPLY_CHAIN.PAGE.BI_SALES';

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_menu, 'supply.bi.menu', NULL, 'TrendCharts', 1, 0, @changed_at, @changed_at),
    (@bi_overview_page, 'supply.bi.index', '/supply-chain/bi', 'DataLine', 1, 0, @changed_at, @changed_at),
    (@bi_sales_page, 'supply.bi.sales', '/supply-chain/bi/sales', 'DataAnalysis', 1, 0, @changed_at, @changed_at),
    (@bi_activity_page, 'supply.bi.activity', '/supply-chain/bi/activity', 'Tickets', 1, 0, @changed_at, @changed_at),
    (@bi_product_sales_page, 'supply.bi.product-sales', '/supply-chain/bi/product-sales', 'Goods', 1, 0, @changed_at, @changed_at),
    (@bi_product_inventory_page, 'supply.bi.product-inventory', '/supply-chain/bi/product-inventory', 'Goods', 1, 0, @changed_at, @changed_at),
    (@bi_gross_profit_page, 'supply.bi.gross-profit', '/supply-chain/bi/gross-profit', 'Histogram', 1, 0, @changed_at, @changed_at),
    (@bi_payment_risk_page, 'supply.bi.payment-risk', '/supply-chain/bi/payment-risk', 'Warning', 1, 0, @changed_at, @changed_at),
    (@bi_city_cost_page, 'supply.bi.city-cost', '/supply-chain/bi/city-cost', 'Coin', 1, 0, @changed_at, @changed_at),
    (@bi_inventory_risk_page, 'supply.bi.inventory-risk', '/supply-chain/bi/inventory-risk', 'Warning', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

UPDATE iam_resource_ui resource_ui
JOIN iam_resource resource_record ON resource_record.id = resource_ui.resource_id
   SET resource_ui.visible = CASE
       WHEN resource_ui.route_key IN (
           'supply.bi.menu',
           'supply.bi.index',
           'supply.bi.sales',
           'supply.bi.activity',
           'supply.bi.product-sales',
           'supply.bi.product-inventory',
           'supply.bi.gross-profit',
           'supply.bi.payment-risk',
           'supply.bi.city-cost',
           'supply.bi.inventory-risk'
       ) THEN 1 ELSE 0 END,
       resource_ui.version = resource_ui.version + 1,
       resource_ui.updated_at = @changed_at,
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at
 WHERE resource_record.application_id = @app_supply_chain
   AND resource_ui.route_key LIKE 'supply.bi.%';

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_page
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @bi_gross_profit_page
    UNION ALL SELECT @bi_payment_risk_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_page
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @bi_gross_profit_page
    UNION ALL SELECT @bi_payment_risk_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = menu_config.resource_id
   SET menu_config.visible = CASE
       WHEN resource_ui.route_key IN (
           'supply.bi.menu',
           'supply.bi.index',
           'supply.bi.sales',
           'supply.bi.activity',
           'supply.bi.product-sales',
           'supply.bi.product-inventory',
           'supply.bi.gross-profit',
           'supply.bi.payment-risk',
           'supply.bi.city-cost',
           'supply.bi.inventory-risk'
       ) THEN 1 ELSE 0 END,
       menu_config.version = menu_config.version + 1,
       menu_config.updated_at = @changed_at
 WHERE resource_ui.route_key LIKE 'supply.bi.%';

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_page
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @bi_gross_profit_page
    UNION ALL SELECT @bi_payment_risk_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_menu AS resource_id
    UNION ALL SELECT @bi_overview_page
    UNION ALL SELECT @bi_sales_page
    UNION ALL SELECT @bi_activity_page
    UNION ALL SELECT @bi_product_sales_page
    UNION ALL SELECT @bi_product_inventory_page
    UNION ALL SELECT @bi_gross_profit_page
    UNION ALL SELECT @bi_payment_risk_page
    UNION ALL SELECT @bi_city_cost_page
    UNION ALL SELECT @bi_inventory_risk_page
    UNION ALL SELECT @analytics_dashboard_read
    UNION ALL SELECT @analytics_refresh_write
    UNION ALL SELECT @analytics_city_cost_write
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V77__iam_supply_bi_city_operating_navigation.sql
-- IAM V77：新增供应链 BI 城市经营看板入口。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000240');
SET @bi_city_operating_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000379');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_city_operating_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_CITY_OPERATING', 'PAGE', NULL, '城市经营看板', 30, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET sort_order = CASE id
       WHEN @bi_overview_page THEN 10
       WHEN @bi_sales_page THEN 20
       ELSE sort_order
   END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@bi_overview_page, @bi_sales_page);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_city_operating_page, 'supply.bi.city-operating',
     '/supply-chain/bi/city-operating', 'Location', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_city_operating_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @bi_city_operating_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_city_operating_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_city_operating_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V78__iam_feishu_import_navigation.sql
-- IAM V78：新增飞书导入中心入口。
--
-- 飞书导入作为 Integration 统一入口，不拆到 CRM/ERP/Order/IAM 各业务页面。
-- 订货宝同步中心仍保留在同一个“外部同步 / 同步控制”分组下。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @integration_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000083');
SET @integration_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000084');
SET @integration_sync_control_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000300');
SET @feishu_import_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000380');

UPDATE iam_resource
   SET display_name = '外部同步',
       sort_order = 90,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_menu;

UPDATE iam_resource
   SET parent_id = @integration_menu,
       display_name = '同步控制',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_sync_control_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.integration.sync-control.menu',
       route_path = NULL,
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @integration_sync_control_menu;

UPDATE iam_resource
   SET parent_id = @integration_sync_control_menu,
       display_name = '订货宝同步中心',
       sort_order = 10,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @integration_overview_page
   AND application_id = @app_supply_chain;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @feishu_import_page, @app_supply_chain, @integration_sync_control_menu,
    'SUPPLY_CHAIN.PAGE.INTEGRATION_FEISHU_IMPORT', 'PAGE', NULL,
    '飞书导入中心', 20, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @feishu_import_page, 'supply.integration.feishu-import',
    '/supply-chain/integration/feishu-import', 'Connection', 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
    UNION ALL SELECT @feishu_import_page
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @integration_overview_page
    UNION ALL SELECT @feishu_import_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @feishu_import_page
 ) resources
 WHERE existing_grant.resource_id = @integration_overview_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @integration_sync_control_menu AS resource_id
    UNION ALL SELECT @feishu_import_page
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V79__iam_feishu_import_api_permissions.sql
-- IAM V79：补齐飞书导入中心 API 权限。
--
-- V78 只新增菜单和页面。导入中心的接口必须单独入 IAM 资源目录，
-- 否则页面可见但 /integration/feishu/import-bundles 会被权限上下文拒绝。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @feishu_import_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000380');
SET @feishu_import_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000381');
SET @feishu_write_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000382');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@feishu_import_api, @app_supply_chain, @feishu_import_page,
     'SUPPLY_CHAIN.API.INTEGRATION_FEISHU_IMPORT', 'API',
     'integration:feishu:import', '预检飞书导出数据', 10, 'ACTIVE', @changed_at, @changed_at),
    (@feishu_write_api, @app_supply_chain, @feishu_import_page,
     'SUPPLY_CHAIN.API.INTEGRATION_FEISHU_WRITE', 'API',
     'integration:feishu:write', '写入飞书导入数据', 20, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @feishu_import_api AS resource_id
    UNION ALL SELECT @feishu_write_api
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT page_grant.tenant_id, page_grant.role_id, @feishu_import_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource page_grant
 WHERE page_grant.resource_id = @feishu_import_page
   AND page_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @feishu_import_api AS resource_id
    UNION ALL SELECT @feishu_write_api
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V80__iam_external_staff_feishu_source.sql
-- IAM V80：人员中心外部来源允许飞书导入，保留订货宝同步来源不变。

ALTER TABLE iam_staff_profile
    DROP CHECK ck_iam_staff_profile_origin,
    ADD CONSTRAINT ck_iam_staff_profile_origin
        CHECK (record_origin IN ('MANUAL', 'DINGHUOBAO', 'FEISHU', 'IMPORT'));

ALTER TABLE iam_external_staff_binding
    DROP CHECK ck_iam_external_staff_binding_system,
    ADD CONSTRAINT ck_iam_external_staff_binding_system
        CHECK (source_system IN ('DINGHUOBAO', 'FEISHU'));

-- Source: V81__iam_feishu_import_write_permission_backfill.sql
-- IAM V81：补齐飞书导入中心写入权限。
--
-- V79 已经为飞书导入中心创建 import/write 两个 API 资源，其中页面已有角色只自动继承 import。
-- 前端目前不区分“只预检”和“可正式导入”两个入口，因此给已拥有飞书导入页面的角色补齐 write 权限，
-- 避免出现页面可见、预检通过、正式导入被权限拦截的状态。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @feishu_import_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000380');
SET @feishu_write_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000382');

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT page_grant.tenant_id, page_grant.role_id, @feishu_write_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource page_grant
 WHERE page_grant.resource_id = @feishu_import_page
   AND page_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V82__iam_hr_employee_navigation.sql
-- IAM V82：开放 HR 员工主档导航和读写权限。
--
-- 人员主档归 HR 管理；IAM 只负责登录账号、角色和权限。这里仅补菜单和 API 权限目录。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @hr_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @hr_index_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @hr_employee_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000383');
SET @hr_employee_read_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000384');
SET @hr_employee_sync_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000387');
SET @hr_position_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000385');
SET @hr_position_read_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000386');

UPDATE iam_resource
   SET display_name = '人事与绩效',
       sort_order = 75,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @hr_menu
   AND application_id = @app_supply_chain;

UPDATE iam_resource_ui
   SET route_key = 'supply.hr.menu',
       route_path = NULL,
       icon_key = 'Avatar',
       visible = 1,
       updated_at = @changed_at
 WHERE resource_id = @hr_menu;

UPDATE iam_resource_ui
   SET visible = 0,
       updated_at = @changed_at
 WHERE resource_id = @hr_index_page;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_employee_page, @app_supply_chain, @hr_menu,
    'SUPPLY_CHAIN.PAGE.HR_EMPLOYEE', 'PAGE', NULL,
    '员工主档', 10, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @hr_employee_page, 'supply.hr.employees',
    '/supply-chain/hr/employees', 'UserFilled', 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_employee_sync_api, @app_supply_chain, @hr_employee_page,
    'SUPPLY_CHAIN.API.HR_EMPLOYEE_SYNC', 'API',
    'hr:employee:sync', '同步HR员工主档', 20, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_employee_read_api, @app_supply_chain, @hr_employee_page,
    'SUPPLY_CHAIN.API.HR_EMPLOYEE_READ', 'API',
    'hr:employee:read', '读取HR员工主档', 10, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_position_page, @app_supply_chain, @hr_menu,
    'SUPPLY_CHAIN.PAGE.HR_POSITION', 'PAGE', NULL,
    '岗位职位', 20, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @hr_position_page, 'supply.hr.positions',
    '/supply-chain/hr/positions', 'PriceTag', 1, 0, @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_position_read_api, @app_supply_chain, @hr_position_page,
    'SUPPLY_CHAIN.API.HR_POSITION_READ', 'API',
    'hr:position:read', '读取HR岗位职位', 10, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_employee_read_api
    UNION ALL SELECT @hr_employee_sync_api
    UNION ALL SELECT @hr_position_page
    UNION ALL SELECT @hr_position_read_api
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_position_page
 ) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_employee_read_api
    UNION ALL SELECT @hr_employee_sync_api
    UNION ALL SELECT @hr_position_page
    UNION ALL SELECT @hr_position_read_api
 ) resources
 WHERE existing_grant.resource_id IN (@hr_menu, @hr_index_page)
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @hr_menu AS resource_id
    UNION ALL SELECT @hr_employee_page
    UNION ALL SELECT @hr_employee_read_api
    UNION ALL SELECT @hr_employee_sync_api
    UNION ALL SELECT @hr_position_page
    UNION ALL SELECT @hr_position_read_api
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V83__iam_hr_position_write_permission.sql
-- IAM V83：补齐 HR 岗位职位维护权限。
--
-- V82 已在共享 DEV 执行；后续新增权限必须独立迁移，避免修改已执行迁移导致 checksum 不一致。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @hr_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000061');
SET @hr_index_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000062');
SET @hr_position_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000385');
SET @hr_position_write_api = UUID_TO_BIN('019facf2-0000-7000-8000-000000000388');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @hr_position_write_api, @app_supply_chain, @hr_position_page,
    'SUPPLY_CHAIN.API.HR_POSITION_WRITE', 'API',
    'hr:position:write', '维护HR岗位职位', 20, 'ACTIVE', @changed_at, @changed_at
)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @hr_position_write_api, @changed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @hr_position_write_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id IN (@hr_menu, @hr_index_page, @hr_position_page)
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, @hr_position_write_api,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V84__iam_supply_bi_customer_dashboard_navigation.sql
-- IAM V84：新增供应链 BI 客户看板入口。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_overview_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @bi_customer_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000389');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_customer_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_CUSTOMER', 'PAGE', NULL, '客户看板', 40, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

UPDATE iam_resource
   SET sort_order = CASE id
       WHEN @bi_overview_page THEN 10
       ELSE sort_order
   END,
       version = version + 1,
       updated_at = @changed_at
 WHERE id IN (@bi_overview_page);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_customer_page, 'supply.bi.customer',
     '/supply-chain/bi/customer', 'User', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_customer_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @bi_customer_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_customer_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id IN (@bi_overview_page, @analytics_dashboard_read)
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @bi_customer_page AS resource_id
    UNION ALL SELECT @analytics_dashboard_read
 ) resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V85__iam_bi_operating_workspace_permissions.sql
-- Register explicit BI management capabilities; assigning them remains an IAM administration action.
SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000066');
SET @targets_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000396');
SET @operations_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000397');
SET @reconciliation_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000398');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@targets_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.BI_TARGETS_WRITE', 'API',
     'analytics:targets:write', '维护经营目标', 40, 'ACTIVE', @changed_at, @changed_at),
    (@operations_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.BI_OPERATIONS_WRITE', 'API',
     'analytics:operations:write', '维护运营跟进', 50, 'ACTIVE', @changed_at, @changed_at),
    (@reconciliation_write, @app_supply_chain, @bi_page,
     'SUPPLY_CHAIN.API.BI_RECONCILIATION_WRITE', 'API',
     'analytics:reconciliation:write', '生成来源数据复核', 60, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL FROM (
    SELECT @targets_write AS resource_id
    UNION ALL SELECT @operations_write
    UNION ALL SELECT @reconciliation_write
) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

-- Source: V86__iam_tenant_admin_effective_resources.sql
-- Restore the active first-party order write contract removed with the obsolete DHB sync UI in V16.
-- Restore the standard package contract without silently widening custom package entitlements.
SET @changed_at = UTC_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @sales_order_page = (SELECT id FROM iam_resource WHERE resource_code = 'SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

INSERT INTO iam_resource
    (id, application_id, parent_id, resource_code, resource_type, permission_code,
     display_name, sort_order, status, created_at, updated_at)
VALUES (@order_write, @app_supply_chain, @sales_order_page, 'SUPPLY_CHAIN.API.ORDER_WRITE',
        'API', 'order:write', '维护销售订单、发货与收退款', 20, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE parent_id = VALUES(parent_id), display_name = VALUES(display_name),
    updated_at = @changed_at;

UPDATE iam_resource SET parent_id = @sales_order_page, updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ORDER_READ' AND parent_id IS NULL;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT entitled.package_version_id, resource_record.id, @changed_at, NULL
  FROM iam_package_resource entitled
  JOIN iam_resource resource_record ON resource_record.resource_code = 'SUPPLY_CHAIN.API.ORDER_WRITE'
 WHERE entitled.resource_id = @sales_order_page
   AND entitled.package_version_id = @standard_package_version
ON DUPLICATE KEY UPDATE created_at = iam_package_resource.created_at;

-- V36 incorrectly attached ERP supply permissions to a hidden sales-publishing API.
SET @erp_menu = (SELECT id FROM iam_resource WHERE resource_code = 'SUPPLY_CHAIN.MENU.ERP');
UPDATE iam_resource SET parent_id = @erp_menu, updated_at = @changed_at, version = version + 1
 WHERE resource_code IN ('SUPPLY_CHAIN.API.ERP_SUPPLY_READ', 'SUPPLY_CHAIN.API.ERP_SUPPLY_WRITE')
   AND @erp_menu IS NOT NULL AND (parent_id IS NULL OR parent_id <> @erp_menu);

-- Tenant administrator permissions are computed from live entitled resources, never a wildcard.
-- Existing custom/system roles retain explicit grants. No user or role assignments are inserted.
-- BEGIN EFFECTIVE ROLE VIEW
CREATE VIEW iam_effective_tenant_role_resource AS
SELECT DISTINCT role_record.tenant_id,
       role_record.id AS role_id,
       resource_record.id AS resource_id,
       'ACTIVE' COLLATE utf8mb4_general_ci AS status
  FROM iam_role role_record
  JOIN iam_tenant tenant_record
    ON tenant_record.id = role_record.tenant_id
   AND tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription
    ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
  JOIN iam_application application_record
    ON application_record.id = resource_record.application_id
   AND application_record.app_scope = 'TENANT'
   AND application_record.status = 'ACTIVE' AND application_record.deleted_at IS NULL
  LEFT JOIN iam_role_resource explicit_grant
    ON explicit_grant.tenant_id = role_record.tenant_id
   AND explicit_grant.role_id = role_record.id
   AND explicit_grant.resource_id = resource_record.id
   AND explicit_grant.status = 'ACTIVE'
 WHERE role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
   AND ((role_record.role_type = 'SYSTEM' AND role_record.role_code = 'TENANT_SUPER_ADMIN')
        OR explicit_grant.resource_id IS NOT NULL);
-- END EFFECTIVE ROLE VIEW

-- BEGIN ADMIN GRANT BACKFILL
-- Persist the current administrator baseline without touching ordinary roles or user assignments.
INSERT INTO iam_role_resource
    (tenant_id, role_id, resource_id, status, created_at, created_by, updated_at, updated_by)
SELECT DISTINCT role_record.tenant_id, role_record.id, resource_record.id,
       'ACTIVE', UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL
  FROM iam_role role_record
  JOIN iam_tenant tenant_record ON tenant_record.id = role_record.tenant_id
   AND tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record ON resource_record.id = package_resource.resource_id
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
  JOIN iam_application application_record ON application_record.id = resource_record.application_id
   AND application_record.app_scope = 'TENANT'
   AND application_record.status = 'ACTIVE' AND application_record.deleted_at IS NULL
 WHERE role_record.role_type = 'SYSTEM' AND role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = UTC_TIMESTAMP(6);
-- END ADMIN GRANT BACKFILL

-- BEGIN MISSING MENU CONFIG
-- Preserve every existing tenant visibility/label/group override, including explicit hidden menus.
INSERT INTO iam_tenant_menu_config
    (tenant_id, resource_id, visible, version, created_at, created_by, updated_at, updated_by)
SELECT DISTINCT tenant_record.id, resource_record.id, resource_ui.visible, 0,
       UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL
  FROM iam_tenant tenant_record
  JOIN iam_tenant_subscription subscription ON subscription.tenant_id = tenant_record.id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED') AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record ON resource_record.id = package_resource.resource_id
   AND resource_record.resource_type IN ('MENU', 'PAGE')
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
  JOIN iam_application application_record ON application_record.id = resource_record.application_id
   AND application_record.app_scope = 'TENANT'
   AND application_record.status = 'ACTIVE' AND application_record.deleted_at IS NULL
  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = resource_record.id
 WHERE tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE resource_id = iam_tenant_menu_config.resource_id;
-- END MISSING MENU CONFIG

-- Source: V87__iam_dhb_sync_permissions_supply_application.sql
-- The live supply-chain sync center calls these two human-facing DHB APIs.
-- Keep resource IDs and existing V9 package/ordinary-role grants; do not revive the retired app/UI.
SET @changed_at = UTC_TIMESTAMP(6);
SET @supply_application = (
    SELECT id FROM iam_application
     WHERE app_code = 'SUPPLY_CHAIN' AND app_scope = 'TENANT'
       AND status = 'ACTIVE' AND deleted_at IS NULL
);
SET @sync_center_page = (
    SELECT id FROM iam_resource
     WHERE resource_code = 'SUPPLY_CHAIN.PAGE.DINGHUOBAO_OVERVIEW'
       AND application_id = @supply_application AND resource_type = 'PAGE'
       AND status = 'ACTIVE' AND deleted_at IS NULL
);

UPDATE iam_resource
   SET application_id = @supply_application, parent_id = @sync_center_page,
       updated_at = @changed_at, version = version + 1
 WHERE ((resource_code = 'DHB_INTEGRATION.API.READ' AND permission_code = 'integration:dhb:read')
        OR (resource_code = 'DHB_INTEGRATION.API.WRITE' AND permission_code = 'integration:dhb:write'))
   AND resource_type = 'API' AND status = 'ACTIVE' AND deleted_at IS NULL
   AND @supply_application IS NOT NULL AND @sync_center_page IS NOT NULL
   AND (application_id <> @supply_application OR parent_id IS NULL OR parent_id <> @sync_center_page);

-- V9 already persisted both resources in the standard package. Moving the same IDs preserves it.
-- Do not grant either API to additional packages or add any SERVICE-only permission.
INSERT INTO iam_role_resource
    (tenant_id, role_id, resource_id, status, created_at, created_by, updated_at, updated_by)
SELECT DISTINCT role_record.tenant_id, role_record.id, resource_record.id,
       'ACTIVE', @changed_at, NULL, @changed_at, NULL
  FROM iam_role role_record
  JOIN iam_tenant tenant_record ON tenant_record.id = role_record.tenant_id
   AND tenant_record.status = 'ACTIVE' AND tenant_record.deleted_at IS NULL
  JOIN iam_tenant_subscription subscription ON subscription.tenant_id = role_record.tenant_id
   AND subscription.status IN ('ACTIVE', 'SCHEDULED') AND subscription.deleted_at IS NULL
   AND subscription.effective_from <= @changed_at AND subscription.effective_to > @changed_at
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record ON resource_record.id = package_resource.resource_id
   AND resource_record.application_id = @supply_application
   AND resource_record.parent_id = @sync_center_page
   AND resource_record.resource_type = 'API'
   AND resource_record.status = 'ACTIVE' AND resource_record.deleted_at IS NULL
 WHERE role_record.role_type = 'SYSTEM' AND role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.status = 'ACTIVE' AND role_record.deleted_at IS NULL
   AND ((resource_record.resource_code = 'DHB_INTEGRATION.API.READ'
         AND resource_record.permission_code = 'integration:dhb:read')
        OR (resource_record.resource_code = 'DHB_INTEGRATION.API.WRITE'
            AND resource_record.permission_code = 'integration:dhb:write'))
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

-- Correct four existing human-facing labels; permission codes, scopes and grants do not change.
UPDATE iam_resource SET display_name = '查询订单、发货与收退款', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ORDER_READ' AND permission_code = 'order:read'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '查询订单、发货与收退款';
UPDATE iam_resource SET display_name = '维护商品、分类、品牌与规格', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ERP_PRODUCT_WRITE' AND permission_code = 'erp:product:write'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '维护商品、分类、品牌与规格';
UPDATE iam_resource SET display_name = '维护采购、库存与供应商', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.ERP_SUPPLY_WRITE' AND permission_code = 'erp:supply:write'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '维护采购、库存与供应商';
UPDATE iam_resource SET display_name = '维护客户与客户基础数据', updated_at = @changed_at, version = version + 1
 WHERE resource_code = 'SUPPLY_CHAIN.API.CRM_CUSTOMER_WRITE' AND permission_code = 'crm:customer:write'
   AND application_id = @supply_application AND status = 'ACTIVE' AND deleted_at IS NULL
   AND display_name <> '维护客户与客户基础数据';

-- Source: V88__iam_hr_and_sales_visit_dashboards.sql
-- IAM V88：HR 与销售拜访独立看板；仅复制现有 BI 读取角色的页面访问，不新增数据权限。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @bi_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000065');
SET @bi_hr_page = UUID_TO_BIN('692aa39e-7724-46e6-a7d2-cbb6cda405f2');
SET @bi_visit_page = UUID_TO_BIN('eac18763-ac5f-4927-b12a-234240fd7aac');
SET @analytics_dashboard_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000360');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@bi_hr_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_HR', 'PAGE', NULL, 'HR 人事看板', 42, 'ACTIVE', @changed_at, @changed_at),
    (@bi_visit_page, @app_supply_chain, @bi_menu,
     'SUPPLY_CHAIN.PAGE.BI_SALES_VISITS', 'PAGE', NULL, '销售拜访看板', 44, 'ACTIVE', @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@bi_hr_page, 'supply.bi.hr',
     '/supply-chain/bi/hr', 'User', 1, 0, @changed_at, @changed_at),
    (@bi_visit_page, 'supply.bi.sales-visits',
     '/supply-chain/bi/sales-visits', 'Location', 1, 0, @changed_at, @changed_at)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    keep_alive = VALUES(keep_alive),
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resources.resource_id, @changed_at, NULL
  FROM (
    SELECT @bi_hr_page AS resource_id
    UNION ALL SELECT @bi_visit_page
  ) resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, resources.resource_id, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 CROSS JOIN (SELECT @bi_hr_page AS resource_id UNION ALL SELECT @bi_visit_page) resources
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    version = iam_tenant_menu_config.version + 1,
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 CROSS JOIN (
    SELECT @bi_hr_page AS resource_id
    UNION ALL SELECT @bi_visit_page
 ) resources
 WHERE existing_grant.status = 'ACTIVE'
   AND existing_grant.resource_id = @analytics_dashboard_read
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V89__iam_tenant_menu_hierarchy.sql
-- 展示层级可覆盖为根节点、系统目录或租户目录；保留既有租户名称和层级。
ALTER TABLE iam_tenant_menu_config
    ADD COLUMN parent_resource_id BINARY(16) NULL COMMENT '租户指定的系统目录父节点',
    ADD COLUMN parent_overridden TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1使用租户父级，父级为空表示根节点',
    ADD CONSTRAINT fk_iam_menu_parent_resource FOREIGN KEY (parent_resource_id) REFERENCES iam_resource(id),
    ADD CONSTRAINT ck_iam_menu_parent_override CHECK (parent_overridden IN (0,1)),
    ADD CONSTRAINT ck_iam_menu_single_parent CHECK (parent_resource_id IS NULL OR parent_group_id IS NULL);

ALTER TABLE iam_tenant_menu_group
    ADD COLUMN parent_resource_id BINARY(16) NULL COMMENT '系统目录父节点，与parent_id互斥',
    ADD CONSTRAINT fk_iam_group_parent_resource FOREIGN KEY (parent_resource_id) REFERENCES iam_resource(id),
    ADD CONSTRAINT ck_iam_group_single_parent CHECK (parent_resource_id IS NULL OR parent_id IS NULL);

-- Source: V90__iam_supply_application_settings.sql
-- 供应链应用内部设置。仅建模，不隐式开通用户或切换旧授权。
CREATE TABLE iam_app_settings (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    authorization_mode VARCHAR(16) NOT NULL DEFAULT 'PREPARING',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, application_id),
    CONSTRAINT fk_app_settings_tenant FOREIGN KEY (tenant_id) REFERENCES iam_tenant(id),
    CONSTRAINT fk_app_settings_app FOREIGN KEY (application_id) REFERENCES iam_application(id),
    CONSTRAINT ck_app_settings_mode CHECK (authorization_mode IN ('PREPARING','ACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_menu_node (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL, parent_id BINARY(16) NULL, resource_id BINARY(16) NULL,
    node_type VARCHAR(16) NOT NULL, display_name VARCHAR(128) NOT NULL,
    icon_key VARCHAR(64) NULL, sort_order INT NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    protected_node BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
    active_resource_id BINARY(16) GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN resource_id ELSE NULL END) STORED,
    PRIMARY KEY (tenant_id, application_id, id),
    UNIQUE KEY uk_app_menu_resource (tenant_id, application_id, active_resource_id),
    KEY ix_app_menu_parent (tenant_id, application_id, parent_id, sort_order),
    CONSTRAINT fk_app_menu_settings FOREIGN KEY (tenant_id, application_id) REFERENCES iam_app_settings(tenant_id, application_id),
    CONSTRAINT fk_app_menu_parent FOREIGN KEY (tenant_id, application_id, parent_id) REFERENCES iam_app_menu_node(tenant_id, application_id, id),
    CONSTRAINT fk_app_menu_resource FOREIGN KEY (resource_id) REFERENCES iam_resource(id),
    CONSTRAINT ck_app_menu_kind CHECK (node_type IN ('MENU','PAGE','BUTTON')),
    CONSTRAINT ck_app_menu_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT ck_app_menu_feature CHECK (node_type='MENU' OR resource_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_member (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    member_kind VARCHAR(16) NOT NULL DEFAULT 'BUSINESS',
    status VARCHAR(16) NOT NULL DEFAULT 'DISABLED', remark VARCHAR(500) NULL,
    version BIGINT NOT NULL DEFAULT 0, security_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
    PRIMARY KEY (tenant_id, application_id, user_id),
    CONSTRAINT fk_app_member_settings FOREIGN KEY (tenant_id, application_id) REFERENCES iam_app_settings(tenant_id, application_id),
    CONSTRAINT fk_app_member_user FOREIGN KEY (tenant_id, user_id) REFERENCES iam_user(tenant_id, id),
    CONSTRAINT ck_app_member_kind CHECK (member_kind IN ('BUSINESS','PROTECTED')),
    CONSTRAINT ck_app_member_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_employee_binding (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    employee_code VARCHAR(50) NOT NULL, hr_revision BIGINT NOT NULL, hr_access_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, application_id, user_id),
    UNIQUE KEY uk_app_employee_identity (tenant_id, application_id, employee_code),
    CONSTRAINT fk_app_binding_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_employee_binding_history (
    id BINARY(16) NOT NULL, tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL, employee_code VARCHAR(50) NOT NULL,
    effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6) NULL,
    reason VARCHAR(500) NOT NULL, actor_id BINARY(16) NOT NULL,
    PRIMARY KEY(id), KEY ix_app_binding_history (tenant_id, application_id, user_id, effective_from),
    CONSTRAINT fk_app_binding_history_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_role (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL,
    role_code VARCHAR(64) NOT NULL, role_name VARCHAR(128) NOT NULL, description VARCHAR(500) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', protected_role BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted_at DATETIME(6) NULL,
    PRIMARY KEY(tenant_id, application_id, id),
    UNIQUE KEY uk_app_role_code(tenant_id, application_id, role_code),
    CONSTRAINT fk_app_role_settings FOREIGN KEY (tenant_id, application_id) REFERENCES iam_app_settings(tenant_id, application_id),
    CONSTRAINT ck_app_role_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_member_role (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL, role_id BINARY(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(tenant_id, application_id, user_id, role_id),
    KEY ix_app_role_members(tenant_id, application_id, role_id, user_id),
    CONSTRAINT fk_app_assignment_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id),
    CONSTRAINT fk_app_assignment_role FOREIGN KEY (tenant_id, application_id, role_id) REFERENCES iam_app_role(tenant_id, application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_role_grant (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL, menu_node_id BINARY(16) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, role_id, menu_node_id),
    CONSTRAINT fk_app_grant_role FOREIGN KEY (tenant_id, application_id, role_id) REFERENCES iam_app_role(tenant_id, application_id, id),
    CONSTRAINT fk_app_grant_node FOREIGN KEY (tenant_id, application_id, menu_node_id) REFERENCES iam_app_menu_node(tenant_id, application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_scope_rule (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL, action_code VARCHAR(128) NOT NULL,
    object_type VARCHAR(32) NOT NULL, scope_mode VARCHAR(32) NOT NULL,
    department_mode VARCHAR(32) NOT NULL DEFAULT 'NONE', region_mode VARCHAR(16) NOT NULL DEFAULT 'NONE',
    warehouse_mode VARCHAR(16) NOT NULL DEFAULT 'NONE', include_descendants BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(tenant_id, application_id, id),
    UNIQUE KEY uk_app_scope_action(tenant_id, application_id, role_id, action_code),
    CONSTRAINT fk_app_scope_role FOREIGN KEY (tenant_id, application_id, role_id) REFERENCES iam_app_role(tenant_id, application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_scope_reference (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    scope_rule_id BINARY(16) NOT NULL, dimension VARCHAR(16) NOT NULL, reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, scope_rule_id, dimension, reference_key),
    CONSTRAINT fk_app_scope_ref_rule FOREIGN KEY (tenant_id, application_id, scope_rule_id) REFERENCES iam_app_scope_rule(tenant_id, application_id, id),
    CONSTRAINT ck_app_scope_ref_dimension CHECK (dimension IN ('DEPARTMENT','REGION','WAREHOUSE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_member_role_scope (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL, role_id BINARY(16) NOT NULL, scope_rule_id BINARY(16) NOT NULL,
    dimension VARCHAR(16) NOT NULL, reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, user_id, role_id, scope_rule_id, dimension, reference_key),
    CONSTRAINT fk_app_member_scope_assignment FOREIGN KEY (tenant_id, application_id, user_id, role_id) REFERENCES iam_app_member_role(tenant_id, application_id, user_id, role_id),
    CONSTRAINT fk_app_member_scope_rule FOREIGN KEY (tenant_id, application_id, scope_rule_id) REFERENCES iam_app_scope_rule(tenant_id, application_id, id),
    CONSTRAINT ck_app_member_scope_dimension CHECK (dimension IN ('DEPARTMENT','REGION','WAREHOUSE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_member_scope_limit (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    dimension VARCHAR(16) NOT NULL, scope_mode VARCHAR(16) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, user_id, dimension),
    CONSTRAINT fk_app_limit_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id),
    CONSTRAINT ck_app_limit_dimension CHECK (dimension IN ('REGION','WAREHOUSE')),
    CONSTRAINT ck_app_limit_mode CHECK (scope_mode IN ('NONE','SPECIFIED','ALL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_member_limit_reference (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    dimension VARCHAR(16) NOT NULL, reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, user_id, dimension, reference_key),
    CONSTRAINT fk_app_limit_reference FOREIGN KEY (tenant_id, application_id, user_id, dimension) REFERENCES iam_app_member_scope_limit(tenant_id, application_id, user_id, dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE iam_app_audit (
    id BINARY(16) NOT NULL, tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NOT NULL, action_code VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NOT NULL, result VARCHAR(16) NOT NULL, summary VARCHAR(1000) NOT NULL,
    batch_id BINARY(16) NULL, occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id), KEY ix_app_audit_query(tenant_id, application_id, occurred_at, id),
    KEY ix_app_audit_actor(tenant_id, application_id, actor_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V91__iam_supply_settings_capabilities.sql
-- 注册供应链内部设置能力；保留门户资源及用户角色，未自动初始化或切换授权。

SET @app_supply=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');

SET @settings=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.SETTINGS');

UPDATE iam_resource SET display_name='系统设置',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@settings;

UPDATE iam_resource SET display_name='系统设置',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE resource_code='SUPPLY_CHAIN.PAGE.SETTINGS_INDEX';

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.USERS','PAGE','supply:user:read','用户管理',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'supply.settings.users','/supply-chain/settings/users','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('0d8e5750-e87c-5c0d-b147-965e858146da'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.CREATE','BUTTON','supply:user:create','新增用户',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('7ebb0f15-6442-5cfe-a4ab-a314c48877d8'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.UPDATE','BUTTON','supply:user:update','编辑用户',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('e0de4d6c-9091-5168-bea7-0b0d10e2515a'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.DISABLE','BUTTON','supply:user:disable','禁用用户',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('fd1503ab-8fcd-5c0c-9f7c-946e2216a471'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.DELETE','BUTTON','supply:user:delete','删除用户',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('3ec60312-ead9-5f15-a9f9-35fc452adf1d'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.ASSIGN_ROLE','BUTTON','supply:user:assign-role','分配角色',50,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('936cb616-b308-5cf5-8627-fd9a4c6a89d4'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.RESET_PASSWORD','BUTTON','supply:user:reset-password','重置密码',60,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('c68bb602-3dd8-5f27-9881-5f1e9ab0d898'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.REBIND','BUTTON','supply:user:rebind','更正员工关联',70,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.ROLES','PAGE','supply:role:read','角色管理',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'supply.settings.roles','/supply-chain/settings/roles','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('5beb5535-af83-5760-bd27-38211209cab4'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.CREATE','BUTTON','supply:role:create','新增角色',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('f520c698-2e8b-54f4-bd6b-d2b33c86fb06'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.UPDATE','BUTTON','supply:role:update','编辑角色',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('ae17de1d-958d-5c81-ba15-29d33dfbb7d8'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.DELETE','BUTTON','supply:role:delete','删除角色',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('a9930278-a152-5bfb-9506-d0d05af07b7f'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.GRANT','BUTTON','supply:role:grant','配置授权',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.MENUS','PAGE','supply:menu:read','菜单管理',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'supply.settings.menus','/supply-chain/settings/menus','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('77810b19-ab90-53ee-b7ff-2cc5c239c3ec'),@app_supply,UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'SUPPLY_CHAIN.SETTINGS.MENUS.CREATE','BUTTON','supply:menu:create','新增菜单',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('f180be07-334e-5431-95fd-fefe861e13f3'),@app_supply,UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'SUPPLY_CHAIN.SETTINGS.MENUS.UPDATE','BUTTON','supply:menu:update','编辑菜单',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('560c16d0-fcb1-5e84-bbee-2054c2531ca6'),@app_supply,UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'SUPPLY_CHAIN.SETTINGS.MENUS.DELETE','BUTTON','supply:menu:delete','删除菜单',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('6623498e-555e-54a9-a58e-92a3244f0bc3'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.PARAMETERS','PAGE','supply:parameter:read','业务参数',50,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('6623498e-555e-54a9-a58e-92a3244f0bc3'),'supply.settings.parameters','/supply-chain/settings/parameters','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('df268e1c-31e4-50bd-8159-9bd1b0741885'),@app_supply,UUID_TO_BIN('6623498e-555e-54a9-a58e-92a3244f0bc3'),'SUPPLY_CHAIN.SETTINGS.PARAMETERS.UPDATE','BUTTON','supply:parameter:update','编辑参数',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('a7c03a8c-7730-5f1e-9602-4476884892f6'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.AUDITS','PAGE','supply:audit:read','操作日志',60,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('a7c03a8c-7730-5f1e-9602-4476884892f6'),'supply.settings.audits','/supply-chain/settings/audits','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at,created_by)
SELECT existing.package_version_id,r.id,UTC_TIMESTAMP(6),NULL FROM iam_package_resource existing
JOIN iam_resource r ON r.application_id=@app_supply AND r.resource_code LIKE 'SUPPLY_CHAIN.SETTINGS.%'
WHERE existing.resource_id=@settings;
INSERT INTO iam_tenant_menu_config(tenant_id,resource_id,visible,version,created_at,created_by,updated_at,updated_by)
SELECT DISTINCT s.tenant_id,r.id,1,0,UTC_TIMESTAMP(6),NULL,UTC_TIMESTAMP(6),NULL
FROM iam_tenant_subscription s JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id
JOIN iam_resource r ON r.id=pr.resource_id
WHERE r.application_id=@app_supply AND r.resource_code LIKE 'SUPPLY_CHAIN.SETTINGS.%' AND r.resource_type='PAGE'
ON DUPLICATE KEY UPDATE resource_id=iam_tenant_menu_config.resource_id;

-- Source: V92__iam_hr_organization_capabilities.sql
-- HR 部门与员工维护功能目录，具体组织数据仍由 HR 单库持有。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');

SET @root=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.HR');

SET @employees=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_EMPLOYEE');

UPDATE iam_resource SET display_name='员工档案',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@employees;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),@app,@root,'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS','PAGE','hr:department:read','部门管理',5,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'supply.hr.departments','/supply-chain/hr/departments','OfficeBuilding',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('bc113c0e-38e2-5a2e-9a22-774a20dcf9e3'),@app,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS.CREATE','BUTTON','hr:department:create','新增部门',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('d45552b8-4d56-51b0-ba04-98f1a89d8a02'),@app,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS.UPDATE','BUTTON','hr:department:update','编辑部门',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0db1c838-0479-5fed-91d2-17e8414fea7c'),@app,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS.DELETE','BUTTON','hr:department:delete','删除部门',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('17c21487-8147-539e-af0a-63250e3b70e5'),@app,@employees,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.CREATE','BUTTON','hr:employee:create','新增员工',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('934ad4e9-36e6-5cf2-9b1d-cf3dd42fc137'),@app,@employees,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.UPDATE','BUTTON','hr:employee:update','编辑员工',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('1a851d93-f970-52f1-81f4-1b6bf8b9bef4'),@app,@employees,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.STATUS','BUTTON','hr:employee:status','变更员工状态',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT existing.package_version_id,r.id,UTC_TIMESTAMP(6) FROM iam_package_resource existing JOIN iam_resource r ON r.application_id=@app AND r.resource_code LIKE 'SUPPLY_CHAIN.HR.SETTINGS.%' WHERE existing.resource_id=@root;

INSERT INTO iam_tenant_menu_config(tenant_id,resource_id,visible,version,created_at,updated_at) SELECT DISTINCT s.tenant_id,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_tenant_subscription s JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id WHERE pr.resource_id=UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521') ON DUPLICATE KEY UPDATE resource_id=iam_tenant_menu_config.resource_id;

-- Source: V93__iam_customer_responsibility_capability.sql
-- 客户主责变更独立授权，不随普通客户资料编辑自动开放。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.crm.customers.profiles');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('a884412c-c549-5d1f-ac3b-63afc7f74084'),@app,@page,'SUPPLY_CHAIN.CRM.CUSTOMER.ASSIGN_OWNER','BUTTON','crm:customer:assign-owner','分配或移交客户主责',80,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,UUID_TO_BIN('a884412c-c549-5d1f-ac3b-63afc7f74084'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V94__iam_customer_order_action_capabilities.sql
-- 普通角色逐动作授权；新增按钮不自动赋给现有自定义角色。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.crm.customers.profiles');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0c8d19c1-e74a-5829-958b-68711fc20470'),@app,@page,'SUPPLY_CHAIN.ACTION.CRM.CUSTOMER.CREATE','BUTTON','crm:customer:create','新增客户',100,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('0c8d19c1-e74a-5829-958b-68711fc20470'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.crm.customers.profiles');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('9cf9110b-3f3a-52b6-ae69-1d322169cc3c'),@app,@page,'SUPPLY_CHAIN.ACTION.CRM.CUSTOMER.UPDATE','BUTTON','crm:customer:update','编辑客户资料',101,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('9cf9110b-3f3a-52b6-ae69-1d322169cc3c'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.crm.customers.profiles');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('c6bf4606-bd7f-5c86-b269-cbff0888018b'),@app,@page,'SUPPLY_CHAIN.ACTION.CRM.CUSTOMER.DELETE','BUTTON','crm:customer:delete','删除客户',102,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('c6bf4606-bd7f-5c86-b269-cbff0888018b'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('c54f5b1f-3f40-5ef7-bbda-97325a4eeb5f'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.CREATE','BUTTON','order:create','新增订单',103,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('c54f5b1f-3f40-5ef7-bbda-97325a4eeb5f'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('e6004110-062a-5341-ac15-a5a9ffda9370'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.UPDATE','BUTTON','order:update','编辑草稿',104,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('e6004110-062a-5341-ac15-a5a9ffda9370'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a563f544-bb1a-5d3b-bd1d-e65455954b39'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.SUBMIT','BUTTON','order:submit','提交订单',105,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('a563f544-bb1a-5d3b-bd1d-e65455954b39'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('e759d1f0-6844-527e-b9b9-350dd39af52c'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.CANCEL','BUTTON','order:cancel','取消订单',106,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('e759d1f0-6844-527e-b9b9-350dd39af52c'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('bdf1ac70-70fe-5a48-8e70-5b33d36c065e'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.DELETE','BUTTON','order:delete','删除草稿',107,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('bdf1ac70-70fe-5a48-8e70-5b33d36c065e'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('c8c72a57-fb54-51df-bd71-39bb7585e068'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.WAREHOUSE.SELECT','BUTTON','order:warehouse:select','选择订单仓库',108,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('c8c72a57-fb54-51df-bd71-39bb7585e068'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('5791bcf5-21ba-5e21-9f2b-7e6ed4918c09'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.OUTBOUND.CONFIRM','BUTTON','order:outbound:confirm','确认订单出库',109,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('5791bcf5-21ba-5e21-9f2b-7e6ed4918c09'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a3011cd7-ec20-57ee-826c-5c39f0a2a7ec'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.OUTBOUND.READ','BUTTON','order:outbound:read','查看履约状态',110,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('a3011cd7-ec20-57ee-826c-5c39f0a2a7ec'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V95__iam_warehouse_inventory_action_capabilities.sql
-- 仓库与库存动作独立于选仓、销售出库权限。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.warehouses');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('ca0a7697-9152-58c3-bd2c-b37ecb110c96'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.WAREHOUSE.READ','BUTTON','erp:warehouse:read','查看仓库',120,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('ca0a7697-9152-58c3-bd2c-b37ecb110c96'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.warehouses');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('951d39ef-1938-590d-aa9f-cba3c08c6536'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.WAREHOUSE.CREATE','BUTTON','erp:warehouse:create','新增仓库',121,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('951d39ef-1938-590d-aa9f-cba3c08c6536'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.warehouses');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('ed947d06-43ba-54cf-9e8f-e3f737c53dc4'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.WAREHOUSE.UPDATE','BUTTON','erp:warehouse:update','编辑仓库',122,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('ed947d06-43ba-54cf-9e8f-e3f737c53dc4'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.warehouses');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a958e125-9e68-5b52-ab68-1399d3a3b7bf'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.WAREHOUSE.DELETE','BUTTON','erp:warehouse:delete','删除仓库',123,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('a958e125-9e68-5b52-ab68-1399d3a3b7bf'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.inventory');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('9d2a1aa3-58e5-5efd-a16b-35a5ca16ddab'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.INVENTORY.READ','BUTTON','erp:inventory:read','查看库存余额',124,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('9d2a1aa3-58e5-5efd-a16b-35a5ca16ddab'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.inbound');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('e2e8dfa3-41bf-541b-98e6-83182c98405c'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.STOCK-IN.READ','BUTTON','erp:stock-in:read','查看入库单',125,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('e2e8dfa3-41bf-541b-98e6-83182c98405c'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.inbound');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('627e5c29-a46e-5fbe-bf92-6e61ebf464e9'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.STOCK-IN.CONFIRM','BUTTON','erp:stock-in:confirm','确认采购入库',126,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('627e5c29-a46e-5fbe-bf92-6e61ebf464e9'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.outbound');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('6a27b266-05a8-53cd-a7e8-ca2d478363ce'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.STOCK-OUT.READ','BUTTON','erp:stock-out:read','查看出库单',127,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('6a27b266-05a8-53cd-a7e8-ca2d478363ce'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.transfers');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('cf3bfb78-ceb3-54ba-a8c1-5d87d7afa2ba'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.TRANSFER.READ','BUTTON','erp:transfer:read','查看调拨单',128,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('cf3bfb78-ceb3-54ba-a8c1-5d87d7afa2ba'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.transfers');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('6e2f0f20-7a22-5318-b9b5-f35341eb088c'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.TRANSFER.CREATE','BUTTON','erp:transfer:create','新增调拨单',129,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('6e2f0f20-7a22-5318-b9b5-f35341eb088c'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.transfers');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('bb14dc0a-01e4-50da-a8c2-690767a24710'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.TRANSFER.UPDATE','BUTTON','erp:transfer:update','编辑调拨单',130,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('bb14dc0a-01e4-50da-a8c2-690767a24710'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.transfers');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('55984c0f-299e-52f7-8a63-d7d932330975'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.TRANSFER.DELETE','BUTTON','erp:transfer:delete','删除调拨单',131,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('55984c0f-299e-52f7-8a63-d7d932330975'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.transfers');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('084b0888-f65f-552d-8baa-df45e3384ad1'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.TRANSFER.STOCK-OUT','BUTTON','erp:transfer:stock-out','确认调拨出库',132,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('084b0888-f65f-552d-8baa-df45e3384ad1'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.inventory.transfers');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('cb1894c6-7c77-5d01-921e-d8083ec984f9'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.TRANSFER.STOCK-IN','BUTTON','erp:transfer:stock-in','确认调拨入库',133,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('cb1894c6-7c77-5d01-921e-d8083ec984f9'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V96__iam_order_related_document_actions.sql
-- 订单关联单据逐动作授权；不按当前客户主责重算原单归属。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a10b2e2c-a8ea-5407-9852-cc4c570c6192'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.PAYMENT.READ','BUTTON','order:payment:read','查看销售回款',140,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('a10b2e2c-a8ea-5407-9852-cc4c570c6192'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('f9c34227-7088-553f-9fe4-452627cdcfea'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.PAYMENT.CREATE','BUTTON','order:payment:create','新增销售回款',141,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('f9c34227-7088-553f-9fe4-452627cdcfea'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('1975538a-4efc-5216-9a3d-a56efd43935e'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.PAYMENT.UPDATE','BUTTON','order:payment:update','编辑销售回款',142,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('1975538a-4efc-5216-9a3d-a56efd43935e'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('550de253-7f6f-5741-b954-1dc4214919e8'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.PAYMENT.DELETE','BUTTON','order:payment:delete','删除销售回款',143,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('550de253-7f6f-5741-b954-1dc4214919e8'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-refunds');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0984a1b1-0cbf-5dac-8b93-bf507d6b8e47'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.REFUND.READ','BUTTON','order:refund:read','查看销售退款',140,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('0984a1b1-0cbf-5dac-8b93-bf507d6b8e47'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-refunds');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('e6c69358-70cb-5721-aa45-eb327fd83459'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.REFUND.CREATE','BUTTON','order:refund:create','新增销售退款',141,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('e6c69358-70cb-5721-aa45-eb327fd83459'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-refunds');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('8db49498-3cbb-5cba-b93d-c2576e24e5f4'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.REFUND.UPDATE','BUTTON','order:refund:update','编辑销售退款',142,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('8db49498-3cbb-5cba-b93d-c2576e24e5f4'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-refunds');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('f86b3526-ad04-51ee-9743-27e5113ff3fd'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.REFUND.DELETE','BUTTON','order:refund:delete','删除销售退款',143,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('f86b3526-ad04-51ee-9743-27e5113ff3fd'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.shipments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('6214afe0-6d56-5094-8c13-608bb8a39a8d'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.SHIPMENT.READ','BUTTON','order:shipment:read','查看销售发货单',140,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('6214afe0-6d56-5094-8c13-608bb8a39a8d'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.shipments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('c4c11307-5600-5ed4-a87a-4c94118e9747'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.SHIPMENT.CREATE','BUTTON','order:shipment:create','新增销售发货单',141,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('c4c11307-5600-5ed4-a87a-4c94118e9747'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.shipments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('86ac55d9-ae40-546e-aa94-3cbbbd6a441c'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.SHIPMENT.UPDATE','BUTTON','order:shipment:update','编辑销售发货单',142,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('86ac55d9-ae40-546e-aa94-3cbbbd6a441c'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.shipments');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('127ab51a-ccae-5f02-af46-d25b486884de'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.SHIPMENT.DELETE','BUTTON','order:shipment:delete','删除销售发货单',143,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('127ab51a-ccae-5f02-af46-d25b486884de'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.fund-documents');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('2433e60c-7e10-5e19-86f7-03e24d7de473'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.FUND.READ','BUTTON','order:fund:read','查看资金单据',140,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('2433e60c-7e10-5e19-86f7-03e24d7de473'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.fund-documents');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('2d99e3ff-6bf6-59af-9ab4-9ad6fed2146b'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.FUND.CREATE','BUTTON','order:fund:create','新增资金单据',141,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('2d99e3ff-6bf6-59af-9ab4-9ad6fed2146b'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.fund-documents');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('e4b85797-09af-5d3a-96bf-c2a00ec7367e'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.FUND.UPDATE','BUTTON','order:fund:update','编辑资金单据',142,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('e4b85797-09af-5d3a-96bf-c2a00ec7367e'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.fund-documents');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('9505eb65-5f11-5ca4-812c-2d6dcc694c4c'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.FUND.DELETE','BUTTON','order:fund:delete','删除资金单据',143,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('9505eb65-5f11-5ca4-812c-2d6dcc694c4c'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V97__iam_order_fulfillment_page.sql
-- 将已注册的履约读取能力提供为独立页面，保持权限编码及已有授权引用。
SET @node=(SELECT id FROM iam_resource WHERE permission_code='order:outbound:read');
SET @parent=(SELECT r.parent_id FROM iam_resource r JOIN iam_resource_ui u ON u.resource_id=r.id WHERE u.route_key='supply.order.sales-orders');
UPDATE iam_resource SET resource_type='PAGE',parent_id=@parent,display_name='订单出库',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@node;
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(@node,'supply.order.fulfillments','/supply-chain/order/fulfillments','Box',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
UPDATE iam_app_menu_node n JOIN iam_app_menu_node p ON p.tenant_id=n.tenant_id AND p.application_id=n.application_id AND p.resource_id=@parent SET n.node_type='PAGE',n.parent_id=p.id,n.display_name='订单出库',n.version=n.version+1 WHERE n.resource_id=@node;

-- Source: V98__iam_order_history_repair_action.sql
-- 历史商品核对使用独立动作；仍需由角色明确配置订单范围。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @repair=UUID_TO_BIN('213cce14-158a-439e-a1c3-b25f0cedb936');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@repair,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.HISTORY.REPAIR','BUTTON','order:history:repair','核对历史商品',120,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@repair,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V99__iam_procurement_action_permissions.sql
-- 采购订单按入库仓库及独立动作授权。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.procurement.orders');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('8ac4b57e-7cf9-521e-a7b3-1243777eccc7'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.PROCUREMENT.READ','BUTTON','erp:procurement:read','查看采购订单',140,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('8ac4b57e-7cf9-521e-a7b3-1243777eccc7'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('871a48c3-b560-53dc-8ae3-160a1bbf4296'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.PROCUREMENT.CREATE','BUTTON','erp:procurement:create','新增采购订单',141,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('871a48c3-b560-53dc-8ae3-160a1bbf4296'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('bbfcc3e4-f9c2-5263-8e95-ba86f295eb09'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.PROCUREMENT.UPDATE','BUTTON','erp:procurement:update','编辑采购订单',142,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('bbfcc3e4-f9c2-5263-8e95-ba86f295eb09'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('c3c25bfa-8497-5f8a-9f86-8f3b75de85b5'),@app,@page,'SUPPLY_CHAIN.ACTION.ERP.PROCUREMENT.DELETE','BUTTON','erp:procurement:delete','删除采购订单',143,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('c3c25bfa-8497-5f8a-9f86-8f3b75de85b5'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V100__iam_supply_authorization_observations.sql
-- 准备阶段的真实请求功能决定对比；不会参与放行。范围快照供审查，不冒充业务行过滤结果。
CREATE TABLE iam_app_authorization_observation (
 tenant_id BINARY(16) NOT NULL,application_id BINARY(16) NOT NULL,user_id BINARY(16) NOT NULL,
 application_version BIGINT NOT NULL,action_code VARCHAR(128) NOT NULL,legacy_action_code VARCHAR(128) NOT NULL,
 legacy_allowed BOOLEAN NOT NULL,proposed_allowed BOOLEAN NOT NULL,policy_json JSON NOT NULL,
 sample_count BIGINT NOT NULL DEFAULT 1,observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,application_id,application_version,user_id,action_code,legacy_action_code),
 KEY ix_app_observation_time(tenant_id,application_id,observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V101__iam_supply_legacy_role_mappings.sql
-- 只保存迁入供应链角色的来源证据；原角色及其跨应用授权不改写。
CREATE TABLE iam_app_legacy_role_mapping (
 tenant_id BINARY(16) NOT NULL,application_id BINARY(16) NOT NULL,source_role_id BINARY(16) NOT NULL,target_role_id BINARY(16) NOT NULL,
 source_fingerprint VARCHAR(64) NOT NULL,source_evidence JSON NOT NULL,actor_id BINARY(16) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,application_id,source_role_id),UNIQUE KEY uk_app_import_target(tenant_id,application_id,target_role_id),
 CONSTRAINT fk_app_import_target FOREIGN KEY(tenant_id,application_id,target_role_id) REFERENCES iam_app_role(tenant_id,application_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V102__iam_order_attribution_review_actions.sql
-- 历史归属申请与审批分开授权；每个动作仍配置订单数据范围。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @action=UUID_TO_BIN('56ed7b60-3c6d-4f50-a5bb-00ad20193371');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@action,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.ATTRIBUTION.READ','BUTTON','order:attribution:read','查看归属复核',130,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@action,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @action=UUID_TO_BIN('23bba268-2b60-48c3-992c-53d3e6b32b92');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@action,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.ATTRIBUTION.PROPOSE','BUTTON','order:attribution:propose','申请归属更正',131,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@action,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;
SET @action=UUID_TO_BIN('cb775166-14ab-435c-8acf-e23e91de765c');
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(@action,@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.ATTRIBUTION.APPROVE','BUTTON','order:attribution:approve','审核归属更正',132,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,@action,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@page;

-- Source: V103__iam_supply_data_observations.sql
-- 数据决定与功能决定分别留存；同请求的域内 SQL 只旁路求值，不扩大准备阶段权限。
CREATE TABLE iam_app_data_observation (
 tenant_id BINARY(16) NOT NULL,application_id BINARY(16) NOT NULL,user_id BINARY(16) NOT NULL,
 application_version BIGINT NOT NULL,action_code VARCHAR(160) NOT NULL,domain_code VARCHAR(16) NOT NULL,record_key VARCHAR(160) NOT NULL,
 legacy_allowed BOOLEAN NOT NULL,proposed_allowed BOOLEAN NOT NULL,policy_json JSON NOT NULL,
 sample_count BIGINT NOT NULL DEFAULT 1,observed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,application_id,application_version,user_id,action_code,domain_code,record_key),
 KEY ix_data_observation_time(tenant_id,application_id,observed_at),
 CONSTRAINT fk_data_observation_user FOREIGN KEY(tenant_id,user_id) REFERENCES iam_user(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V104__iam_retire_collaboration_capabilities.sql
-- 暂停内部协同：只停用能力目录，保留角色引用、消息数据和已执行迁移历史。
SET @changed_at = UTC_TIMESTAMP(6);
UPDATE iam_resource
   SET status='DISABLED', version=version+1, updated_at=@changed_at
 WHERE resource_code IN ('WORKBENCH.PAGE.CHAT','WORKBENCH.API.COLLABORATION_IM','WORKBENCH.API.COLLABORATION_MEETING')
   AND status <> 'DISABLED';
UPDATE iam_tenant
   SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V105__iam_retire_city_and_channel_navigation.sql
-- 城市运营、渠道代理微服务退出当前产品；保留资源与历史角色引用。
-- 不涉及 CRM 客户地区、员工部门或 BI 城市报表。
SET @changed_at = UTC_TIMESTAMP(6);

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id = resource_record.id
   SET resource_record.status = 'DISABLED',
       resource_record.version = resource_record.version + 1,
       resource_record.updated_at = @changed_at,
       ui_record.visible = 0,
       ui_record.updated_at = @changed_at
 WHERE ui_record.route_key LIKE 'supply.city.%'
    OR ui_record.route_key LIKE 'supply.channel.%';

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE' AND deleted_at IS NULL;

-- Source: V106__iam_scdp_single_product_entry.sql
-- 单一 SCDP 产品入口；保留历史表及审计，不改写已执行迁移。
SET @changed_at = UTC_TIMESTAMP(6);
SET @scdp = (SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @sales_page = (SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.SALES_DASHBOARD');

UPDATE iam_application SET app_name='瑞盖供应链数字化平台', target_uri='/supply-chain',
    version=version+1, updated_at=@changed_at WHERE id=@scdp;

-- 移动端销售能力仍属于供应链；保留资源 ID 和既有授权关系。
-- 它们是接口权限，不将移动端页面加入 Web 菜单。
UPDATE iam_resource r JOIN iam_application a ON a.id=r.application_id
SET r.application_id=@scdp, r.parent_id=@sales_page, r.version=r.version+1, r.updated_at=@changed_at
WHERE a.app_code='FEISHU_SALES' AND r.resource_type IN ('API','BUTTON')
  AND r.permission_code LIKE 'sales:%';

-- 已初始化租户补齐可配置的移动端按钮能力；不授予任何角色，不恢复已删除节点。
INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,
    display_name,sort_order,visible,status,protected_node,version,created_at,updated_at)
SELECT DISTINCT parent.tenant_id,@scdp,r.id,parent.id,r.id,'BUTTON',
    r.display_name,r.sort_order,FALSE,'ACTIVE',FALSE,0,@changed_at,@changed_at
FROM iam_app_menu_node parent
JOIN iam_resource r ON r.application_id=@scdp AND r.parent_id=@sales_page
    AND r.resource_type IN ('API','BUTTON') AND r.permission_code LIKE 'sales:%' AND r.status='ACTIVE' AND r.deleted_at IS NULL
JOIN iam_tenant_subscription sub ON sub.tenant_id=parent.tenant_id AND sub.status IN ('ACTIVE','SCHEDULED')
    AND sub.deleted_at IS NULL AND sub.effective_from<=@changed_at AND sub.effective_to>@changed_at
JOIN iam_package_resource pr ON pr.package_version_id=sub.package_version_id AND pr.resource_id=r.id
WHERE parent.application_id=@scdp AND parent.resource_id=@sales_page
    AND parent.node_type='PAGE' AND parent.status='ACTIVE' AND parent.deleted_at IS NULL
    AND NOT EXISTS(SELECT 1 FROM iam_app_menu_node existing
        WHERE existing.tenant_id=parent.tenant_id AND existing.application_id=@scdp
            AND (existing.id=r.id OR existing.resource_id=r.id));

UPDATE iam_resource_ui ui JOIN iam_resource r ON r.id=ui.resource_id
JOIN iam_application a ON a.id=r.application_id
SET ui.visible=0, ui.version=ui.version+1, ui.updated_at=@changed_at
WHERE a.app_code <> 'SUPPLY_CHAIN';
UPDATE iam_resource r JOIN iam_application a ON a.id=r.application_id
SET r.status='DISABLED', r.version=r.version+1, r.updated_at=@changed_at
WHERE a.app_code <> 'SUPPLY_CHAIN' AND r.status='ACTIVE';
UPDATE iam_application SET status='DISABLED', version=version+1, updated_at=@changed_at
WHERE app_code <> 'SUPPLY_CHAIN' AND status='ACTIVE';

UPDATE iam_auth_session SET status='REVOKED', revoked_at=@changed_at,
    revoke_reason='SCDP_SINGLE_PRODUCT', version=version+1
WHERE principal_scope='PLATFORM' AND status='ACTIVE';
UPDATE iam_platform_user SET status='DISABLED', security_version=security_version+1,
    version=version+1, updated_at=@changed_at WHERE status <> 'DISABLED';
UPDATE iam_platform_user_credential SET status='DISABLED', version=version+1,
    updated_at=@changed_at WHERE status <> 'DISABLED';

-- 只更换 Web 客户端公开名称；内部 ID、重定向白名单、PKCE 与签名策略不变。
UPDATE iam_oauth_client old_client LEFT JOIN iam_oauth_client current_client
    ON current_client.client_id=CONCAT('rigour-scdp-', SUBSTRING(old_client.client_id, 15))
SET old_client.client_id=IF(current_client.id IS NULL, CONCAT('rigour-scdp-', SUBSTRING(old_client.client_id, 15)), old_client.client_id),
    old_client.status=IF(current_client.id IS NULL, old_client.status, 'DISABLED'),
    old_client.client_name='瑞盖供应链数字化平台', old_client.version=old_client.version+1, old_client.updated_at=@changed_at
WHERE old_client.client_id LIKE 'rigour-portal-%';
UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
WHERE deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1, updated_at=@changed_at WHERE application_id=@scdp;

-- Source: V107__iam_retire_hr_placeholder_navigation.sql
-- HR 员工档案已有正式页面。旧占位页曾仅在资源 UI 中隐藏，
-- 租户已保存的 visible=1 会将其再次展示；从资源及租户菜单同时停用。
-- 保留历史角色引用，不修改正式员工档案的自定义名称、层级与授权。
SET @changed_at = UTC_TIMESTAMP(6);
SET @old_hr_page = (
    SELECT r.id FROM iam_resource r
    JOIN iam_resource_ui ui ON ui.resource_id=r.id
    JOIN iam_application app ON app.id=r.application_id
    WHERE app.app_code='SUPPLY_CHAIN' AND ui.route_key='supply.hr.index'
);

UPDATE iam_app_settings settings_record
JOIN iam_app_menu_node node_record
  ON node_record.tenant_id=settings_record.tenant_id
 AND node_record.application_id=settings_record.application_id
SET settings_record.version=settings_record.version+1,
    settings_record.updated_at=@changed_at
WHERE node_record.resource_id=@old_hr_page AND node_record.deleted_at IS NULL;

UPDATE iam_resource
SET status='DISABLED',version=version+1,updated_at=@changed_at
WHERE id=@old_hr_page;

UPDATE iam_resource_ui
SET visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@old_hr_page;

UPDATE iam_app_menu_node
SET status='DISABLED',visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@old_hr_page AND deleted_at IS NULL;

UPDATE iam_tenant_menu_config
SET visible=0,version=version+1,updated_at=@changed_at
WHERE resource_id=@old_hr_page;

UPDATE iam_tenant
SET policy_version=policy_version+1,version=version+1,updated_at=@changed_at
WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V108__iam_hr_position_label.sql
-- 岗位采用单一目录，更新默认菜单文案；保留租户自行修改的名称与授权。
SET @changed_at=UTC_TIMESTAMP(6);
SET @position_page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.hr.positions');
UPDATE iam_app_settings s JOIN iam_app_menu_node n ON n.tenant_id=s.tenant_id AND n.application_id=s.application_id
SET s.version=s.version+1,s.updated_at=@changed_at WHERE n.resource_id=@position_page AND n.deleted_at IS NULL;
UPDATE iam_resource SET display_name='岗位管理',version=version+1,updated_at=@changed_at
WHERE id=@position_page AND display_name IN ('岗位职位','岗位/职位','岗位');
UPDATE iam_app_menu_node SET display_name='岗位管理',version=version+1,updated_at=@changed_at
WHERE resource_id=@position_page AND deleted_at IS NULL AND display_name IN ('岗位职位','岗位/职位','岗位');
UPDATE iam_tenant_menu_config SET display_name_override='岗位管理',version=version+1,updated_at=@changed_at
WHERE resource_id=@position_page AND display_name_override IN ('岗位职位','岗位/职位','岗位');
UPDATE iam_resource SET display_name=REPLACE(display_name,'岗位职位','岗位'),version=version+1,updated_at=@changed_at
WHERE permission_code IN ('hr:position:read','hr:position:write') AND display_name LIKE '%岗位职位%';
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=@changed_at
WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V109__iam_scdp_rotating_refresh_tokens.sql
-- 接通 SCDP 浏览器的标准 refresh_token 授权；保留 15 分钟访问令牌及现有会话期限。
-- 不修改历史迁移，也不开放其他客户端；旧刷新令牌在轮换后由存储层撤销。
INSERT INTO iam_oauth_client_grant (client_id, grant_type, created_at)
SELECT c.id, 'refresh_token', UTC_TIMESTAMP(6)
FROM iam_oauth_client c
WHERE c.client_id LIKE 'rigour-scdp-%'
  AND c.client_type = 'PUBLIC' AND c.require_pkce = 1 AND c.reuse_refresh_tokens = 0
  AND EXISTS (SELECT 1 FROM iam_oauth_client_grant g WHERE g.client_id = c.id AND g.grant_type = 'authorization_code')
  AND NOT EXISTS (SELECT 1 FROM iam_oauth_client_grant g WHERE g.client_id = c.id AND g.grant_type = 'refresh_token');

-- Source: V110__iam_custom_menu_page.sql
-- 若依式自定义页面：菜单节点可直接携带路由地址、组件路径与权限标识。
-- 只放宽 PAGE，MENU/BUTTON 语义不变；已绑定资源的历史节点保持原行为。
ALTER TABLE iam_app_menu_node
    ADD COLUMN route_key VARCHAR(191) NULL COMMENT '自定义页面路由唯一标识' AFTER active_resource_id,
    ADD COLUMN route_path VARCHAR(255) NULL COMMENT '自定义页面路由地址' AFTER route_key,
    ADD COLUMN component_path VARCHAR(255) NULL COMMENT '自定义页面组件路径' AFTER route_path,
    ADD COLUMN permission_code VARCHAR(128) NULL COMMENT '自定义页面权限标识' AFTER component_path,
    DROP CHECK ck_app_menu_feature,
    ADD CONSTRAINT ck_app_menu_feature CHECK (
        node_type='MENU'
        OR resource_id IS NOT NULL
        OR (node_type='PAGE' AND route_key IS NOT NULL AND route_path IS NOT NULL AND component_path IS NOT NULL)
    );

-- Source: V111__iam_order_sales_lines_page.sql
-- 订单明细页面：订单列表/发货单同源的注册式菜单，页面组件与路由白名单在 Web 端 fail-closed 校验。
-- 资源、套餐、角色授权与租户菜单节点一并补齐，脚本可重复执行。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @order_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.menu');
SET @lines=UUID_TO_BIN('a48ab364-aa49-40c1-97c5-edcb74623530');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(@lines,@app,@order_menu,'SUPPLY_CHAIN.PAGE.ORDER_SALES_LINES','PAGE',NULL,'订单明细',15,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),resource_type='PAGE',display_name=VALUES(display_name),sort_order=VALUES(sort_order),status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(@lines,'supply.order.lines','/supply-chain/order/lines',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE route_path=VALUES(route_path),visible=TRUE,updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@lines,UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@order_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@lines,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource WHERE resource_id=@order_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@lines,n.id,@lines,'PAGE','订单明细',15,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n WHERE n.resource_id=@order_menu AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),display_name=VALUES(display_name),sort_order=VALUES(sort_order),visible=TRUE,status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

-- Source: V112__iam_customer_type_price_page.sql
-- 客户类型等级价页面：本地维护商品规格 × 客户类型的等级价，权限与菜单一并注册。
-- 订货宝接口不提供等级价读取，数据由本地维护和导入建立；脚本可重复执行。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @erp_master_data_menu=UUID_TO_BIN('019facf2-0000-7000-8000-000000000167');
SET @page=UUID_TO_BIN('019facf2-0000-7000-8000-000000000303');
SET @price_read=UUID_TO_BIN('019facf2-0000-7000-8000-000000000304');
SET @price_write=UUID_TO_BIN('019facf2-0000-7000-8000-000000000305');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES
 (@page,@app,@erp_master_data_menu,'SUPPLY_CHAIN.PAGE.ERP_CUSTOMER_TYPE_PRICES','PAGE',NULL,'客户类型等级价',70,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
 (@price_read,@app,@erp_master_data_menu,'SUPPLY_CHAIN.API.ERP_CUSTOMER_TYPE_PRICE_READ','API','erp:product-price:read','查询客户类型等级价',71,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
 (@price_write,@app,@erp_master_data_menu,'SUPPLY_CHAIN.API.ERP_CUSTOMER_TYPE_PRICE_WRITE','API','erp:product-price:write','维护客户类型等级价',72,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),resource_type=VALUES(resource_type),permission_code=VALUES(permission_code),display_name=VALUES(display_name),sort_order=VALUES(sort_order),status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(@page,'supply.erp.master-data.customer-type-prices','/supply-chain/erp/master-data/customer-type-prices',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE route_path=VALUES(route_path),visible=TRUE,updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT DISTINCT p.package_version_id,r.resource_id,UTC_TIMESTAMP(6)
  FROM iam_package_resource p
  CROSS JOIN (SELECT @page AS resource_id UNION ALL SELECT @price_read UNION ALL SELECT @price_write) r
 WHERE p.resource_id=@erp_master_data_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT rr.tenant_id,rr.role_id,r.resource_id,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource rr
  CROSS JOIN (SELECT @page AS resource_id UNION ALL SELECT @price_read UNION ALL SELECT @price_write) r
 WHERE rr.resource_id=@erp_master_data_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@page,n.id,@page,'PAGE','客户类型等级价',70,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@erp_master_data_menu AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE parent_id=VALUES(parent_id),display_name=VALUES(display_name),sort_order=VALUES(sort_order),visible=TRUE,status='ACTIVE',deleted_at=NULL,updated_at=UTC_TIMESTAMP(6);

UPDATE iam_tenant SET policy_version=policy_version+1, version=version+1, updated_at=UTC_TIMESTAMP(6)
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V113__iam_product_price_rename.sql
-- 商品价格页面命名调整：原「客户类型等级价」页面统一改名为「商品价格」，资源编码与路由 key/路径同步更新。
-- V112 已执行且不可改写，本迁移负责重命名；脚本可重复执行。
SET @page=UUID_TO_BIN('019facf2-0000-7000-8000-000000000303');
SET @price_read=UUID_TO_BIN('019facf2-0000-7000-8000-000000000304');
SET @price_write=UUID_TO_BIN('019facf2-0000-7000-8000-000000000305');

UPDATE iam_resource
   SET resource_code='SUPPLY_CHAIN.PAGE.ERP_PRODUCT_PRICES',
       display_name='商品价格',
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@page;

UPDATE iam_resource
   SET display_name='查询商品等级价',
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@price_read;

UPDATE iam_resource
   SET display_name='维护商品等级价',
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@price_write;

UPDATE iam_resource_ui
   SET route_key='supply.erp.master-data.prices',
       route_path='/supply-chain/erp/master-data/prices',
       updated_at=UTC_TIMESTAMP(6)
 WHERE resource_id=@page;

UPDATE iam_app_menu_node
   SET display_name='商品价格',
       updated_at=UTC_TIMESTAMP(6)
 WHERE resource_id=@page
   AND deleted_at IS NULL;

UPDATE iam_tenant
   SET policy_version=policy_version+1,
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE status='ACTIVE'
   AND deleted_at IS NULL;

-- Source: V114__iam_order_statistics_page.sql
-- IAM V114：将订单与回款统计作为数据库驱动的订单菜单页面开放。
-- 前端 catalog 只负责已编译 routeKey 的白名单，不在代码中创建正式菜单。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @order_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.menu');
SET @statistics=UUID_TO_BIN('bd7b6e1c-5cb7-4a9d-8d6d-4b0c6b6e1140');

INSERT INTO iam_resource(
    id,application_id,parent_id,resource_code,resource_type,permission_code,
    display_name,sort_order,status,created_at,updated_at)
VALUES(
    @statistics,@app,@order_menu,'SUPPLY_CHAIN.PAGE.ORDER_STATISTICS','PAGE',NULL,
    '订单与回款统计',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    resource_type='PAGE',
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(
    resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(
    @statistics,'supply.order.statistics','/supply-chain/order/statistics',NULL,TRUE,FALSE,
    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    route_path=VALUES(route_path),
    visible=TRUE,
    updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@statistics,UTC_TIMESTAMP(6)
  FROM iam_package_resource
 WHERE resource_id=@order_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@statistics,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource
 WHERE resource_id=@order_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@statistics,n.id,@statistics,'PAGE','订单与回款统计',20,TRUE,
       'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@order_menu
   AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    visible=TRUE,
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

-- Source: V115__iam_order_three_menu_contract.sql
-- IAM V115：订单域正式侧栏只保留订单列表、订单明细、订单回款三个菜单。
-- 统计页面资源若已由 V114 建立，保留资源但从正式侧栏隐藏，避免改写已执行迁移。
SET @order_list=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @order_lines=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.lines');
SET @order_payments=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
SET @order_statistics=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.statistics');

UPDATE iam_resource resource_record
JOIN iam_resource_ui ui_record ON ui_record.resource_id=resource_record.id
   SET resource_record.display_name=CASE ui_record.route_key
           WHEN 'supply.order.sales-orders' THEN '订单列表'
           WHEN 'supply.order.lines' THEN '订单明细'
           WHEN 'supply.order.sales-payments' THEN '订单回款'
           ELSE resource_record.display_name
       END,
       resource_record.updated_at=UTC_TIMESTAMP(6)
 WHERE ui_record.route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments'
 );

UPDATE iam_resource_ui
   SET visible=1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments'
 );

UPDATE iam_resource_ui
   SET visible=0,
       updated_at=UTC_TIMESTAMP(6)
 WHERE route_key='supply.order.statistics';

UPDATE iam_tenant_menu_config menu_config
JOIN iam_resource_ui ui_record ON ui_record.resource_id=menu_config.resource_id
   SET menu_config.visible=CASE
           WHEN ui_record.route_key IN (
               'supply.order.sales-orders',
               'supply.order.lines',
               'supply.order.sales-payments') THEN 1
           WHEN ui_record.route_key='supply.order.statistics' THEN 0
           ELSE menu_config.visible
       END,
       menu_config.updated_at=UTC_TIMESTAMP(6)
 WHERE ui_record.route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments',
       'supply.order.statistics'
 );

UPDATE iam_app_menu_node menu_node
JOIN iam_resource_ui ui_record ON ui_record.resource_id=menu_node.resource_id
   SET menu_node.display_name=CASE ui_record.route_key
           WHEN 'supply.order.sales-orders' THEN '订单列表'
           WHEN 'supply.order.lines' THEN '订单明细'
           WHEN 'supply.order.sales-payments' THEN '订单回款'
           WHEN 'supply.order.statistics' THEN '订单与回款统计'
           ELSE menu_node.display_name
       END,
       menu_node.visible=CASE
           WHEN ui_record.route_key='supply.order.statistics' THEN 0
           ELSE 1
       END,
       menu_node.updated_at=UTC_TIMESTAMP(6)
 WHERE ui_record.route_key IN (
       'supply.order.sales-orders',
       'supply.order.lines',
       'supply.order.sales-payments',
       'supply.order.statistics'
 );

UPDATE iam_role_resource role_grant
   SET role_grant.status='ACTIVE',
       role_grant.updated_at=UTC_TIMESTAMP(6)
 WHERE role_grant.resource_id IN (@order_list,@order_lines,@order_payments)
   AND role_grant.status <> 'ACTIVE';

UPDATE iam_role_resource role_grant
   SET role_grant.status='INACTIVE',
       role_grant.updated_at=UTC_TIMESTAMP(6)
 WHERE @order_statistics IS NOT NULL
   AND role_grant.resource_id=@order_statistics;

-- Source: V116__iam_order_invoice_write_permission.sql
-- IAM V116：订单开票写权限（申请开票、上传发票附件、完成开票、撤回）。
-- 只登记按钮权限并按"系统超管 + 具备出库确认能力的运营角色"授予；财务角色建立后由管理员在 IAM 单独授予。
-- 仅新增，不修改已执行的迁移。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-orders');
SET @changed_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('6ad67d3e-7f3a-4ba4-9be8-495270cd2bcb'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.INVOICE.WRITE','BUTTON','order:invoice:write','申请与登记发票',111,'ACTIVE',@changed_at,@changed_at);

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,UUID_TO_BIN('6ad67d3e-7f3a-4ba4-9be8-495270cd2bcb'),@changed_at
  FROM iam_package_resource
 WHERE resource_id=@page;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, UUID_TO_BIN('6ad67d3e-7f3a-4ba4-9be8-495270cd2bcb'),
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id,
       UUID_TO_BIN('6ad67d3e-7f3a-4ba4-9be8-495270cd2bcb'), 'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
  JOIN iam_resource existing_resource
    ON existing_resource.id = existing_grant.resource_id
   AND existing_resource.permission_code = 'order:outbound:confirm'
 WHERE existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V117__iam_order_invoice_page.sql
-- IAM V117：新增「发票管理」页面菜单（订单管理下），财务集中处理待开票。
-- 页面可见性跟随订单管理菜单；操作仍由 order:invoice:write 控制。前端 catalog 通过 routeKey 映射到已编译页面。
-- 仅新增，不修改已执行的迁移。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @order_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.menu');
SET @invoices=UUID_TO_BIN('e1582a53-c489-423e-8b58-40a6bbc04b37');

INSERT INTO iam_resource(
    id,application_id,parent_id,resource_code,resource_type,permission_code,
    display_name,sort_order,status,created_at,updated_at)
VALUES(
    @invoices,@app,@order_menu,'SUPPLY_CHAIN.PAGE.ORDER_INVOICES','PAGE',NULL,
    '发票管理',25,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    resource_type='PAGE',
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(
    resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(
    @invoices,'supply.order.invoices','/supply-chain/order/invoices',NULL,TRUE,FALSE,
    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    route_path=VALUES(route_path),
    visible=TRUE,
    updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@invoices,UTC_TIMESTAMP(6)
  FROM iam_package_resource
 WHERE resource_id=@order_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@invoices,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource
 WHERE resource_id=@order_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@invoices,n.id,@invoices,'PAGE','发票管理',25,TRUE,
       'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@order_menu
   AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    visible=TRUE,
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

-- Source: V118__iam_order_payment_check_permission.sql
-- IAM V118：订单回款核对权限（财务对账：填写付款流水号、标记已核对）。
-- 流水号用于付款凭证验重与财务对账；只登记按钮权限，财务角色建立后由管理员在 IAM 授予。
-- 仅新增，不修改已执行的迁移。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
SET @changed_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.PAYMENT.CHECK','BUTTON','order:payment:check','核对回款',201,'ACTIVE',@changed_at,@changed_at);

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'),@changed_at
  FROM iam_package_resource
 WHERE resource_id=@page;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'),
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id,
       UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'), 'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
  JOIN iam_resource existing_resource
    ON existing_resource.id = existing_grant.resource_id
   AND existing_resource.permission_code = 'order:invoice:write'
 WHERE existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;

-- Source: V119__iam_sync_schedule_page.sql
-- 同步中心定时任务菜单。复用 Integration 读写/HR同步及BI刷新权限，不额外扩权。
SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @sync_menu=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.integration.sync-control.menu');
SET @schedule=UUID_TO_BIN('6a3aeefe-4f56-4a96-a56b-60e2e3873d65');

INSERT INTO iam_resource(
    id,application_id,parent_id,resource_code,resource_type,permission_code,
    display_name,sort_order,status,created_at,updated_at)
VALUES(
    @schedule,@app,@sync_menu,'SUPPLY_CHAIN.PAGE.SYNC_SCHEDULE','PAGE',NULL,
    '定时任务',25,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    resource_type='PAGE',
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource_ui(
    resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(
    @schedule,'supply.integration.schedules','/supply-chain/integration/schedules',NULL,TRUE,FALSE,
    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    route_path=VALUES(route_path),
    visible=TRUE,
    updated_at=UTC_TIMESTAMP(6);

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,@schedule,UTC_TIMESTAMP(6)
  FROM iam_package_resource
 WHERE resource_id=@sync_menu;

INSERT INTO iam_role_resource(tenant_id,role_id,resource_id,status,created_at,updated_at)
SELECT tenant_id,role_id,@schedule,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_role_resource
 WHERE resource_id=@sync_menu
ON DUPLICATE KEY UPDATE status='ACTIVE',updated_at=UTC_TIMESTAMP(6);

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT n.tenant_id,n.application_id,@schedule,n.id,@schedule,'PAGE','定时任务',25,TRUE,
       'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
  FROM iam_app_menu_node n
 WHERE n.resource_id=@sync_menu
   AND n.deleted_at IS NULL
ON DUPLICATE KEY UPDATE
    parent_id=VALUES(parent_id),
    display_name=VALUES(display_name),
    sort_order=VALUES(sort_order),
    visible=TRUE,
    status='ACTIVE',
    deleted_at=NULL,
    updated_at=UTC_TIMESTAMP(6);

UPDATE iam_tenant SET policy_version=policy_version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE status='ACTIVE' AND deleted_at IS NULL;

-- Source: V120__iam_complete_supply_permission_nodes.sql
-- 补齐角色授权树遗漏的已注册权限。仅登记，不给普通角色自动授权，不修改授权模式。
-- API 共用权限仍保持原有粒度：CRM 客户基础数据、ERP 商品基础数据及 ERP 供采库存。
-- 将其归入对应页面，避免初始化时因 BUTTON 的父级不是 PAGE 而被过滤。
CREATE TEMPORARY TABLE supply_permission_pages (
    permission_code VARCHAR(128) PRIMARY KEY,
    route_key VARCHAR(128) NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO supply_permission_pages VALUES
 ('order:invoice:write','supply.order.sales-orders'),
 ('order:payment:check','supply.order.sales-payments'),
 ('crm:customer:read','supply.crm.customers.profiles'),
 ('crm:customer:write','supply.crm.customers.profiles'),
 ('erp:product-price:read','supply.erp.master-data.prices'),
 ('erp:product-price:write','supply.erp.master-data.prices'),
 ('erp:product:read','supply.erp.master-data.products'),
 ('erp:product:write','supply.erp.master-data.products'),
 ('erp:supply:read','supply.erp.index'),
 ('erp:supply:write','supply.erp.index');

UPDATE iam_resource r
 JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN'
 JOIN supply_permission_pages m ON m.permission_code=r.permission_code
 JOIN iam_resource_ui ui ON ui.route_key=m.route_key
 JOIN iam_resource page ON page.id=ui.resource_id AND page.application_id=r.application_id
 SET r.parent_id=page.id,r.updated_at=UTC_TIMESTAMP(6)
 WHERE r.deleted_at IS NULL AND page.deleted_at IS NULL AND page.resource_type='PAGE'
   AND NOT (r.parent_id <=> page.id);

-- 只对已初始化且当前套餐包含该资源的企业补节点；保留企业调整过的名称、排序和父级。
-- 已软删除或已有同码自定义节点也算已配置，不自动恢复或重复登记。
CREATE TEMPORARY TABLE supply_permission_missing AS
SELECT DISTINCT parent.tenant_id,parent.application_id,parent.id AS parent_id,
       r.id AS resource_id,r.display_name,r.sort_order
 FROM iam_app_menu_node parent
 JOIN iam_resource r ON r.parent_id=parent.resource_id AND r.application_id=parent.application_id
 JOIN supply_permission_pages m ON m.permission_code=r.permission_code
 JOIN iam_tenant_subscription s ON s.tenant_id=parent.tenant_id
 JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id AND pr.resource_id=r.id
 WHERE parent.node_type='PAGE' AND parent.deleted_at IS NULL
   AND r.status='ACTIVE' AND r.deleted_at IS NULL
   AND s.status IN ('ACTIVE','SCHEDULED') AND s.deleted_at IS NULL
   AND s.effective_from<=UTC_TIMESTAMP(6) AND s.effective_to>UTC_TIMESTAMP(6)
   AND NOT EXISTS (
       SELECT 1 FROM iam_app_menu_node existing
        WHERE existing.tenant_id=parent.tenant_id AND existing.application_id=parent.application_id
          AND (existing.resource_id=r.id OR existing.permission_code=r.permission_code)
   );

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),parent_id,resource_id,'BUTTON',display_name,
       sort_order,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM supply_permission_missing;

UPDATE iam_app_settings settings
 JOIN (SELECT DISTINCT tenant_id,application_id FROM supply_permission_missing) changed
   ON changed.tenant_id=settings.tenant_id AND changed.application_id=settings.application_id
 SET settings.version=settings.version+1,settings.updated_at=UTC_TIMESTAMP(6);

DROP TEMPORARY TABLE supply_permission_missing;
DROP TEMPORARY TABLE supply_permission_pages;

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
