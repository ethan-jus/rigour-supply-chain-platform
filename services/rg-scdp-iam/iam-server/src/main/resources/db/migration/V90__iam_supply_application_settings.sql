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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_employee_binding (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    employee_code VARCHAR(50) NOT NULL, hr_revision BIGINT NOT NULL, hr_access_version BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, application_id, user_id),
    UNIQUE KEY uk_app_employee_identity (tenant_id, application_id, employee_code),
    CONSTRAINT fk_app_binding_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_employee_binding_history (
    id BINARY(16) NOT NULL, tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL, employee_code VARCHAR(50) NOT NULL,
    effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6) NULL,
    reason VARCHAR(500) NOT NULL, actor_id BINARY(16) NOT NULL,
    PRIMARY KEY(id), KEY ix_app_binding_history (tenant_id, application_id, user_id, effective_from),
    CONSTRAINT fk_app_binding_history_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_member_role (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL, role_id BINARY(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(tenant_id, application_id, user_id, role_id),
    KEY ix_app_role_members(tenant_id, application_id, role_id, user_id),
    CONSTRAINT fk_app_assignment_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id),
    CONSTRAINT fk_app_assignment_role FOREIGN KEY (tenant_id, application_id, role_id) REFERENCES iam_app_role(tenant_id, application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_role_grant (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    role_id BINARY(16) NOT NULL, menu_node_id BINARY(16) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, role_id, menu_node_id),
    CONSTRAINT fk_app_grant_role FOREIGN KEY (tenant_id, application_id, role_id) REFERENCES iam_app_role(tenant_id, application_id, id),
    CONSTRAINT fk_app_grant_node FOREIGN KEY (tenant_id, application_id, menu_node_id) REFERENCES iam_app_menu_node(tenant_id, application_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_scope_reference (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    scope_rule_id BINARY(16) NOT NULL, dimension VARCHAR(16) NOT NULL, reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, scope_rule_id, dimension, reference_key),
    CONSTRAINT fk_app_scope_ref_rule FOREIGN KEY (tenant_id, application_id, scope_rule_id) REFERENCES iam_app_scope_rule(tenant_id, application_id, id),
    CONSTRAINT ck_app_scope_ref_dimension CHECK (dimension IN ('DEPARTMENT','REGION','WAREHOUSE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_member_role_scope (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL, role_id BINARY(16) NOT NULL, scope_rule_id BINARY(16) NOT NULL,
    dimension VARCHAR(16) NOT NULL, reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, user_id, role_id, scope_rule_id, dimension, reference_key),
    CONSTRAINT fk_app_member_scope_assignment FOREIGN KEY (tenant_id, application_id, user_id, role_id) REFERENCES iam_app_member_role(tenant_id, application_id, user_id, role_id),
    CONSTRAINT fk_app_member_scope_rule FOREIGN KEY (tenant_id, application_id, scope_rule_id) REFERENCES iam_app_scope_rule(tenant_id, application_id, id),
    CONSTRAINT ck_app_member_scope_dimension CHECK (dimension IN ('DEPARTMENT','REGION','WAREHOUSE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_member_scope_limit (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    dimension VARCHAR(16) NOT NULL, scope_mode VARCHAR(16) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, user_id, dimension),
    CONSTRAINT fk_app_limit_member FOREIGN KEY (tenant_id, application_id, user_id) REFERENCES iam_app_member(tenant_id, application_id, user_id),
    CONSTRAINT ck_app_limit_dimension CHECK (dimension IN ('REGION','WAREHOUSE')),
    CONSTRAINT ck_app_limit_mode CHECK (scope_mode IN ('NONE','SPECIFIED','ALL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_member_limit_reference (
    tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL, user_id BINARY(16) NOT NULL,
    dimension VARCHAR(16) NOT NULL, reference_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id, application_id, user_id, dimension, reference_key),
    CONSTRAINT fk_app_limit_reference FOREIGN KEY (tenant_id, application_id, user_id, dimension) REFERENCES iam_app_member_scope_limit(tenant_id, application_id, user_id, dimension)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE iam_app_audit (
    id BINARY(16) NOT NULL, tenant_id BINARY(16) NOT NULL, application_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NOT NULL, action_code VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NOT NULL, result VARCHAR(16) NOT NULL, summary VARCHAR(1000) NOT NULL,
    batch_id BINARY(16) NULL, occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id), KEY ix_app_audit_query(tenant_id, application_id, occurred_at, id),
    KEY ix_app_audit_actor(tenant_id, application_id, actor_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
