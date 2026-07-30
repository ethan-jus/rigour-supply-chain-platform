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
