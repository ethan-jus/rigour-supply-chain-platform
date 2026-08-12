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
