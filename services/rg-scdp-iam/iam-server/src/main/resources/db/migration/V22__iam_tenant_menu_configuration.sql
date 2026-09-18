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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户自定义无路由菜单分组';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户对套餐内平台菜单的展示覆盖配置';

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
