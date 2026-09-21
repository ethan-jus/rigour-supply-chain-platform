-- IAM V118：订单回款核对权限（财务对账：填写付款流水号、标记已核对）。
-- 流水号用于付款凭证验重与财务对账；只登记按钮权限，财务角色建立后由管理员在 IAM 授予。
-- 仅新增，不修改已执行的迁移。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');
SET @page=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.order.sales-payments');
SET @changed_at=UTC_TIMESTAMP(6);

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'),@app,@page,'SUPPLY_CHAIN.ACTION.ORDER.PAYMENT.CHECK','BUTTON','order:payment:check','核对回款',201,'ACTIVE',@changed_at,@changed_at);

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at)
SELECT package_version_id,UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'),@changed_at
  FROM iam_package_resource
 WHERE resource_id=@page;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'),
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
       UUID_TO_BIN('ec2b8cd8-2769-404e-96c1-7e942b3fd580'), 'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
  JOIN iam_resource existing_resource
    ON existing_resource.id = existing_grant.resource_id
   AND existing_resource.permission_code = 'order:invoice:write'
 WHERE existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @changed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL;
