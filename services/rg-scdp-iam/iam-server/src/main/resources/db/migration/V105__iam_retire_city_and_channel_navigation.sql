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
