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
