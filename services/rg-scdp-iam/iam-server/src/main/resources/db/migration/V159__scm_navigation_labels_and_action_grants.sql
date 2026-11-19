-- 窄侧栏和授权树共用资源名。保留租户已经自定义的名称。
SET @scm=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
SET @presentation_batch=UUID_TO_BIN('84f3bf85-89a8-5bb2-b095-7d0985481466');

CREATE TEMPORARY TABLE scm_root_label (
    resource_code VARCHAR(128) PRIMARY KEY, old_name VARCHAR(128) NOT NULL, new_name VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO scm_root_label VALUES
 ('SUPPLY_CHAIN.PAGE.DASHBOARD','供应链首页','首页'),
 ('SUPPLY_CHAIN.MENU.BI','数据看板','BI'),
 ('SUPPLY_CHAIN.MENU.ORDER','订单管理','订单'),
 ('SUPPLY_CHAIN.MENU.HR','人事管理','HR'),
 ('SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MENU','成本管理','成本'),
 ('SUPPLY_CHAIN.MENU.SALES','销售管理','销售'),
 ('SUPPLY_CHAIN.MENU.DINGHUOBAO','外部同步','同步'),
 ('SUPPLY_CHAIN.MENU.SETTINGS','系统设置','设置');

CREATE TEMPORARY TABLE scm_presentation_tenant (tenant_id BINARY(16) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO scm_presentation_tenant
SELECT s.tenant_id FROM iam_app_settings s
WHERE s.application_id=@scm AND NOT EXISTS (
    SELECT 1 FROM iam_app_audit a WHERE a.tenant_id=s.tenant_id AND a.application_id=@scm
      AND a.action_code='SCM_MENU_PRESENTATION' AND a.batch_id=@presentation_batch
);

UPDATE iam_resource r JOIN scm_root_label l ON BINARY r.resource_code=BINARY l.resource_code
SET r.display_name=l.new_name,r.version=r.version+1,r.updated_at=UTC_TIMESTAMP(6)
WHERE r.application_id=@scm AND BINARY r.display_name=BINARY l.old_name;

UPDATE iam_app_menu_node n JOIN scm_presentation_tenant t ON t.tenant_id=n.tenant_id
JOIN iam_resource r ON r.id=n.resource_id JOIN scm_root_label l ON BINARY r.resource_code=BINARY l.resource_code
SET n.display_name=l.new_name,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.application_id=@scm AND n.deleted_at IS NULL AND BINARY n.display_name=BINARY l.old_name;

UPDATE iam_app_menu_node n JOIN scm_presentation_tenant t ON t.tenant_id=n.tenant_id
SET n.visible=0,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE n.application_id=@scm AND n.node_type='BUTTON' AND n.visible=1;

-- 已经通过页面获得经营看板读取权的角色，显式授予同一操作。只承接原已生效的读取权。
CREATE TEMPORARY TABLE scm_entitled_node (tenant_id BINARY(16),id BINARY(16),PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB;
INSERT INTO scm_entitled_node
WITH RECURSIVE enabled AS (
    SELECT n.tenant_id,n.id,n.parent_id,n.resource_id FROM iam_app_menu_node n
    JOIN scm_presentation_tenant t ON n.tenant_id=t.tenant_id
    WHERE n.application_id=@scm AND n.parent_id IS NULL AND n.deleted_at IS NULL AND n.status='ACTIVE'
    UNION ALL
    SELECT n.tenant_id,n.id,n.parent_id,n.resource_id FROM enabled p
    JOIN iam_app_menu_node n ON n.parent_id=p.id AND n.tenant_id=p.tenant_id
    WHERE n.application_id=@scm AND n.deleted_at IS NULL AND n.status='ACTIVE'
)
SELECT DISTINCT n.tenant_id,n.id FROM enabled n
JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=@scm AND r.status='ACTIVE' AND r.deleted_at IS NULL
JOIN iam_package_resource pr ON pr.resource_id=r.id
JOIN iam_tenant_subscription s ON s.package_version_id=pr.package_version_id AND s.tenant_id=n.tenant_id
WHERE s.status IN ('ACTIVE','SCHEDULED') AND s.deleted_at IS NULL AND s.effective_from<=UTC_TIMESTAMP(6) AND s.effective_to>UTC_TIMESTAMP(6)
  AND r.permission_code='analytics:dashboard:read';

CREATE TEMPORARY TABLE scm_dashboard_action (tenant_id BINARY(16) PRIMARY KEY,id BINARY(16) NOT NULL) ENGINE=InnoDB;
INSERT INTO scm_dashboard_action
SELECT e.tenant_id,e.id FROM scm_entitled_node e
JOIN iam_app_menu_node n ON n.id=e.id AND n.tenant_id=e.tenant_id AND n.application_id=@scm AND n.node_type='BUTTON'
JOIN iam_resource r ON r.id=n.resource_id AND r.resource_code='SUPPLY_CHAIN.API.ANALYTICS_SUPPLY_DASHBOARD_READ';

INSERT INTO iam_app_role_grant (tenant_id,application_id,role_id,menu_node_id)
SELECT DISTINCT g.tenant_id,@scm,g.role_id,action.id
FROM scm_entitled_node e
JOIN iam_app_menu_node page ON page.id=e.id AND page.tenant_id=e.tenant_id AND page.application_id=@scm AND page.node_type='PAGE'
JOIN iam_app_role_grant g ON g.menu_node_id=page.id AND g.tenant_id=page.tenant_id AND g.application_id=@scm
JOIN iam_app_role role ON role.id=g.role_id AND role.tenant_id=g.tenant_id AND role.application_id=@scm
JOIN scm_dashboard_action action ON action.tenant_id=g.tenant_id
LEFT JOIN iam_app_role_grant existing ON existing.menu_node_id=action.id AND existing.role_id=g.role_id AND existing.tenant_id=g.tenant_id AND existing.application_id=@scm
WHERE existing.role_id IS NULL AND role.protected_role=0 AND role.deleted_at IS NULL AND role.status='ACTIVE';
UPDATE iam_app_settings s JOIN scm_presentation_tenant t ON t.tenant_id=s.tenant_id
SET s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6) WHERE s.application_id=@scm;
UPDATE iam_tenant t JOIN scm_presentation_tenant p ON p.tenant_id=t.id
SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=UTC_TIMESTAMP(6);
INSERT INTO iam_app_audit(id,tenant_id,application_id,actor_id,action_code,target_id,result,summary,batch_id,occurred_at)
SELECT UUID_TO_BIN(UUID()),t.tenant_id,@scm,UUID_TO_BIN('626d19b1-30d7-5e9e-a793-916def4cc0f7'),'SCM_MENU_PRESENTATION',
       BIN_TO_UUID(@presentation_batch),'SUCCESS','统一一级菜单短名、操作可见性和已有看板读取授权',@presentation_batch,UTC_TIMESTAMP(6)
FROM scm_presentation_tenant t;
DROP TEMPORARY TABLE scm_dashboard_action,scm_entitled_node,scm_presentation_tenant,scm_root_label;
