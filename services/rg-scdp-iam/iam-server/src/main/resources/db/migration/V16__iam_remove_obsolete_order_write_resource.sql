-- IAM V16：删除已取消的订单中心“订货宝订单同步”权限。
-- V12/V14 已在共享 DEV 执行，不能修改历史迁移；本迁移只清理不再存在的运行时权限和资源。

SET @cleanup_at = TIMESTAMP('2026-08-04 12:00:00.000000');
SET @order_write_resource = UUID_TO_BIN('019facf2-0000-7000-8000-000000000120');

UPDATE iam_tenant tenant_record
JOIN (
    SELECT DISTINCT tenant_id
      FROM iam_role_resource
     WHERE resource_id = @order_write_resource
) affected_tenant
  ON affected_tenant.tenant_id = tenant_record.id
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @cleanup_at;

DELETE FROM iam_role_resource
 WHERE resource_id = @order_write_resource;

DELETE FROM iam_package_resource
 WHERE resource_id = @order_write_resource;

DELETE FROM iam_resource_ui
 WHERE resource_id = @order_write_resource;

DELETE FROM iam_resource
 WHERE id = @order_write_resource
   AND resource_code = 'SUPPLY_CHAIN.API.ORDER_WRITE';
