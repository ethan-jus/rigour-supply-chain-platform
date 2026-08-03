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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台/租户数据字典类型';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据字典条目';

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
