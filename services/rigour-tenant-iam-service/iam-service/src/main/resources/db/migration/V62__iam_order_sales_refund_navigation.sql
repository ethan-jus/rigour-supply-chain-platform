-- Order 新增销售退款页面；订货宝 getPaymentList 只作为来源数据，不暴露旧付款单镜像页。

SET @seed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @sales_order_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @refund_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000341');

INSERT INTO iam_resource (id, application_id, parent_id, resource_code, resource_type, permission_code, display_name, sort_order, status, created_at, updated_at)
VALUES (@refund_page, @app_supply_chain, @order_menu, 'SUPPLY_CHAIN.PAGE.ORDER_SALES_REFUNDS', 'PAGE', NULL, '销售退款', 40, 'ACTIVE', @seed_at, @seed_at)
ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    updated_at = @seed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @refund_page, 'supply.order.sales-refunds',
    '/supply-chain/order/sales-refunds', 'Undo2',
    1, 0, @seed_at, @seed_at
)
ON DUPLICATE KEY UPDATE
    route_key = VALUES(route_key),
    route_path = VALUES(route_path),
    icon_key = VALUES(icon_key),
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @refund_page, @seed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @refund_page, 1, @seed_at, @seed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @seed_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @refund_page,
       'ACTIVE', @seed_at, @seed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @sales_order_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = 'ACTIVE',
    updated_at = @seed_at;

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @seed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource refund_grant
        WHERE refund_grant.tenant_id = tenant_record.id
          AND refund_grant.resource_id = @refund_page
          AND refund_grant.status = 'ACTIVE'
   );
