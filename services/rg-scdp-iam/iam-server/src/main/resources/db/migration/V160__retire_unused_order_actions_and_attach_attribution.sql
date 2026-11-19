-- 订单详情复用原有查看、申请、审核权限；移除没有执行端的商品修复和旧回款删除。
SET @scm = (SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @orders = (SELECT id FROM iam_resource WHERE application_id=@scm AND resource_code='SUPPLY_CHAIN.PAGE.ORDER_SALES_ORDERS');
SET @batch = UUID_TO_BIN('2c41ced5-7eef-49a8-ac3b-9efd34d0470c');
CREATE TEMPORARY TABLE scm_removed_order_action (id BINARY(16) PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE scm_changed_order_tenant (id BINARY(16) PRIMARY KEY) ENGINE=InnoDB;
CREATE TEMPORARY TABLE scm_nav_defaults (
    resource_code VARCHAR(128) PRIMARY KEY, old_sort INT NOT NULL, new_sort INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TEMPORARY TABLE scm_attribution_action (
    permission_code VARCHAR(128) PRIMARY KEY, old_name VARCHAR(128) NOT NULL, new_name VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

START TRANSACTION;
-- 新初始化企业沿用与生产同一套一级菜单。已自定义的名称和排序继续使用原值。
INSERT INTO scm_nav_defaults VALUES ('SUPPLY_CHAIN.MENU.BI',60,12),('SUPPLY_CHAIN.MENU.ORDER',40,15),('SUPPLY_CHAIN.MENU.HR',75,18);
UPDATE iam_resource r JOIN scm_nav_defaults d ON BINARY r.resource_code=BINARY d.resource_code
SET r.sort_order=IF(r.sort_order=d.old_sort,d.new_sort,r.sort_order),
    r.display_name=IF(d.resource_code='SUPPLY_CHAIN.MENU.HR' AND BINARY r.display_name=BINARY '人事与绩效','HR',r.display_name),
    r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE r.application_id=@scm AND (r.sort_order=d.old_sort OR (d.resource_code='SUPPLY_CHAIN.MENU.HR' AND BINARY r.display_name=BINARY '人事与绩效'));
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id
JOIN scm_nav_defaults d ON BINARY r.resource_code=BINARY d.resource_code
SET n.sort_order=IF(n.sort_order=d.old_sort,d.new_sort,n.sort_order),
    n.display_name=IF(d.resource_code='SUPPLY_CHAIN.MENU.HR' AND BINARY n.display_name=BINARY '人事与绩效','HR',n.display_name),
    n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.application_id=@scm AND n.deleted_at IS NULL AND n.parent_id IS NULL AND
    (n.sort_order=d.old_sort OR (d.resource_code='SUPPLY_CHAIN.MENU.HR' AND BINARY n.display_name=BINARY '人事与绩效'));
INSERT INTO scm_removed_order_action
SELECT id FROM iam_resource WHERE application_id=@scm AND resource_type='BUTTON'
  AND permission_code IN ('order:history:repair','order:payment:delete');
INSERT INTO scm_attribution_action VALUES
    ('order:attribution:read','归属复核','归属更正'),
    ('order:attribution:propose','提交复核申请','申请更正'),
    ('order:attribution:approve','审核更正','审核更正');
INSERT INTO scm_changed_order_tenant
SELECT s.tenant_id FROM iam_app_settings s WHERE s.application_id=@scm AND NOT EXISTS (
    SELECT 1 FROM iam_app_audit a WHERE a.tenant_id=s.tenant_id AND a.application_id=@scm
      AND a.action_code='SCM_ORDER_ACTION_RETIREMENT' AND a.batch_id=@batch
);

DELETE g FROM iam_app_role_grant g JOIN iam_app_menu_node n
  ON n.id=g.menu_node_id AND n.tenant_id=g.tenant_id AND n.application_id=g.application_id
WHERE n.application_id=@scm AND n.node_type='BUTTON'
  AND (n.resource_id IN (SELECT id FROM scm_removed_order_action) OR n.permission_code IN ('order:history:repair','order:payment:delete'));
DELETE n FROM iam_app_menu_node n WHERE n.application_id=@scm AND n.node_type='BUTTON'
  AND (n.resource_id IN (SELECT id FROM scm_removed_order_action) OR n.permission_code IN ('order:history:repair','order:payment:delete'));
DELETE g FROM iam_role_resource g JOIN scm_removed_order_action r ON r.id=g.resource_id;
DELETE c FROM iam_tenant_menu_config c JOIN scm_removed_order_action r ON r.id=c.resource_id;
DELETE p FROM iam_package_resource p JOIN scm_removed_order_action r ON r.id=p.resource_id;
DELETE u FROM iam_resource_ui u JOIN scm_removed_order_action r ON r.id=u.resource_id;
DELETE r FROM iam_resource r JOIN scm_removed_order_action a ON a.id=r.id;

UPDATE iam_resource r JOIN scm_attribution_action a ON BINARY a.permission_code=BINARY r.permission_code
SET r.parent_id=@orders, r.display_name=IF(BINARY r.display_name=BINARY a.old_name,a.new_name,r.display_name),
    r.updated_at=UTC_TIMESTAMP(6), r.version=r.version+1
WHERE r.application_id=@scm AND r.resource_type='BUTTON' AND @orders IS NOT NULL
  AND (NOT(r.parent_id<=>@orders) OR (BINARY r.display_name=BINARY a.old_name AND BINARY r.display_name<>BINARY a.new_name));
UPDATE iam_app_menu_node n LEFT JOIN iam_resource r ON r.application_id=n.application_id AND r.id=n.resource_id
JOIN scm_attribution_action a ON BINARY a.permission_code=BINARY COALESCE(n.permission_code,r.permission_code)
JOIN iam_app_menu_node p ON p.tenant_id=n.tenant_id AND p.application_id=n.application_id
    AND p.resource_id=@orders AND p.node_type='PAGE' AND p.deleted_at IS NULL
SET n.parent_id=p.id,n.display_name=IF(BINARY n.display_name=BINARY a.old_name,a.new_name,n.display_name),
    n.updated_at=UTC_TIMESTAMP(6),n.version=n.version+1
WHERE n.application_id=@scm AND n.node_type='BUTTON' AND n.deleted_at IS NULL
  AND (NOT(n.parent_id<=>p.id) OR (BINARY n.display_name=BINARY a.old_name AND BINARY n.display_name<>BINARY a.new_name));

UPDATE iam_app_settings s JOIN scm_changed_order_tenant t ON t.id=s.tenant_id
SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6) WHERE s.application_id=@scm;
UPDATE iam_tenant t JOIN scm_changed_order_tenant c ON c.id=t.id
SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=UTC_TIMESTAMP(6);
INSERT INTO iam_app_audit(id,tenant_id,application_id,actor_id,action_code,target_id,result,summary,batch_id,occurred_at)
SELECT UUID_TO_BIN(UUID()),t.id,@scm,UUID_TO_BIN('626d19b1-30d7-5e9e-a793-916def4cc0f7'),'SCM_ORDER_ACTION_RETIREMENT',
       BIN_TO_UUID(@batch),'SUCCESS','清理退役订单操作，将归属更正挂到订单列表',@batch,UTC_TIMESTAMP(6)
FROM scm_changed_order_tenant t;
COMMIT;
DROP TEMPORARY TABLE scm_attribution_action,scm_changed_order_tenant,scm_removed_order_action,scm_nav_defaults;
