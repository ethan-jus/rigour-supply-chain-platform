-- 补齐角色授权树遗漏的已注册权限。仅登记，不给普通角色自动授权，不修改授权模式。
-- API 共用权限仍保持原有粒度：CRM 客户基础数据、ERP 商品基础数据及 ERP 供采库存。
-- 将其归入对应页面，避免初始化时因 BUTTON 的父级不是 PAGE 而被过滤。
CREATE TEMPORARY TABLE supply_permission_pages (
    permission_code VARCHAR(128) PRIMARY KEY,
    route_key VARCHAR(128) NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO supply_permission_pages VALUES
 ('order:invoice:write','supply.order.sales-orders'),
 ('order:payment:check','supply.order.sales-payments'),
 ('crm:customer:read','supply.crm.customers.profiles'),
 ('crm:customer:write','supply.crm.customers.profiles'),
 ('erp:product-price:read','supply.erp.master-data.prices'),
 ('erp:product-price:write','supply.erp.master-data.prices'),
 ('erp:product:read','supply.erp.master-data.products'),
 ('erp:product:write','supply.erp.master-data.products'),
 ('erp:supply:read','supply.erp.index'),
 ('erp:supply:write','supply.erp.index');

UPDATE iam_resource r
 JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN'
 JOIN supply_permission_pages m ON m.permission_code=r.permission_code
 JOIN iam_resource_ui ui ON ui.route_key=m.route_key
 JOIN iam_resource page ON page.id=ui.resource_id AND page.application_id=r.application_id
 SET r.parent_id=page.id,r.updated_at=UTC_TIMESTAMP(6)
 WHERE r.deleted_at IS NULL AND page.deleted_at IS NULL AND page.resource_type='PAGE'
   AND NOT (r.parent_id <=> page.id);

-- 只对已初始化且当前套餐包含该资源的企业补节点；保留企业调整过的名称、排序和父级。
-- 已软删除或已有同码自定义节点也算已配置，不自动恢复或重复登记。
CREATE TEMPORARY TABLE supply_permission_missing AS
SELECT DISTINCT parent.tenant_id,parent.application_id,parent.id AS parent_id,
       r.id AS resource_id,r.display_name,r.sort_order
 FROM iam_app_menu_node parent
 JOIN iam_resource r ON r.parent_id=parent.resource_id AND r.application_id=parent.application_id
 JOIN supply_permission_pages m ON m.permission_code=r.permission_code
 JOIN iam_tenant_subscription s ON s.tenant_id=parent.tenant_id
 JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id AND pr.resource_id=r.id
 WHERE parent.node_type='PAGE' AND parent.deleted_at IS NULL
   AND r.status='ACTIVE' AND r.deleted_at IS NULL
   AND s.status IN ('ACTIVE','SCHEDULED') AND s.deleted_at IS NULL
   AND s.effective_from<=UTC_TIMESTAMP(6) AND s.effective_to>UTC_TIMESTAMP(6)
   AND NOT EXISTS (
       SELECT 1 FROM iam_app_menu_node existing
        WHERE existing.tenant_id=parent.tenant_id AND existing.application_id=parent.application_id
          AND (existing.resource_id=r.id OR existing.permission_code=r.permission_code)
   );

INSERT INTO iam_app_menu_node(
    tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,
    sort_order,visible,status,created_at,updated_at)
SELECT tenant_id,application_id,UUID_TO_BIN(UUID()),parent_id,resource_id,'BUTTON',display_name,
       sort_order,FALSE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
 FROM supply_permission_missing;

UPDATE iam_app_settings settings
 JOIN (SELECT DISTINCT tenant_id,application_id FROM supply_permission_missing) changed
   ON changed.tenant_id=settings.tenant_id AND changed.application_id=settings.application_id
 SET settings.version=settings.version+1,settings.updated_at=UTC_TIMESTAMP(6);

DROP TEMPORARY TABLE supply_permission_missing;
DROP TEMPORARY TABLE supply_permission_pages;
