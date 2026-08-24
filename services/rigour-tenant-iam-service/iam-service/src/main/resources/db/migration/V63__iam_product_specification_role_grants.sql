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
