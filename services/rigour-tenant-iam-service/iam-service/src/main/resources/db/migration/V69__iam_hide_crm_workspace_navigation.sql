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
