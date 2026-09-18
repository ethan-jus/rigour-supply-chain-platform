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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单页面受控导航元数据';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户IAM与门户非敏感设置';

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
