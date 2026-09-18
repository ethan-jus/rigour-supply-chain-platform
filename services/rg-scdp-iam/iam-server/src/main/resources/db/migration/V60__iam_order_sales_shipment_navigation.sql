-- IAM V60：启用 Order 新方案销售发货单页面。
-- 只恢复发货单业务入口，不恢复旧订货宝发货/物流页面实现。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');
SET @order_root_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000055');
SET @order_sales_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000101');
SET @order_shipment_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000103');

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES (
    @order_shipment_page,
    @app_supply_chain,
    @order_root_menu,
    'SUPPLY_CHAIN.PAGE.ORDER_SALES_SHIPMENTS',
    'PAGE',
    NULL,
    '发货单',
    20,
    'ACTIVE',
    @changed_at,
    @changed_at
) ON DUPLICATE KEY UPDATE
    application_id = VALUES(application_id),
    parent_id = VALUES(parent_id),
    resource_code = VALUES(resource_code),
    resource_type = VALUES(resource_type),
    permission_code = VALUES(permission_code),
    display_name = VALUES(display_name),
    sort_order = VALUES(sort_order),
    status = 'ACTIVE',
    version = version + 1,
    updated_at = @changed_at;

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES (
    @order_shipment_page,
    'supply.order.shipments',
    '/supply-chain/order/shipments',
    'Truck',
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

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
VALUES (@standard_package_version, @order_shipment_page, @changed_at, NULL)
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_tenant_menu_config (tenant_id, resource_id, visible, created_at, updated_at)
SELECT DISTINCT subscription.tenant_id, @order_shipment_page, 1, @changed_at, @changed_at
  FROM iam_tenant_subscription subscription
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
ON DUPLICATE KEY UPDATE
    visible = 1,
    updated_at = @changed_at;

UPDATE iam_role_resource rr
   SET rr.status = 'ACTIVE',
       rr.updated_at = @changed_at
 WHERE rr.resource_id = @order_shipment_page;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT DISTINCT existing_grant.tenant_id, existing_grant.role_id, @order_shipment_page,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role_resource existing_grant
 WHERE existing_grant.resource_id = @order_sales_page
   AND existing_grant.status = 'ACTIVE'
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    updated_at = VALUES(updated_at);

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_role_resource shipment_grant
        WHERE shipment_grant.tenant_id = tenant_record.id
          AND shipment_grant.resource_id = @order_shipment_page
          AND shipment_grant.status = 'ACTIVE'
   );
