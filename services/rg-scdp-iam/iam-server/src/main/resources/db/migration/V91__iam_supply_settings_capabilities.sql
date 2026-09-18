-- 注册供应链内部设置能力；保留门户资源及用户角色，未自动初始化或切换授权。

SET @app_supply=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');

SET @settings=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.SETTINGS');

UPDATE iam_resource SET display_name='系统设置',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@settings;

UPDATE iam_resource SET display_name='系统设置',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE resource_code='SUPPLY_CHAIN.PAGE.SETTINGS_INDEX';

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.USERS','PAGE','supply:user:read','用户管理',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'supply.settings.users','/supply-chain/settings/users','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('0d8e5750-e87c-5c0d-b147-965e858146da'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.CREATE','BUTTON','supply:user:create','新增用户',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('7ebb0f15-6442-5cfe-a4ab-a314c48877d8'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.UPDATE','BUTTON','supply:user:update','编辑用户',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('e0de4d6c-9091-5168-bea7-0b0d10e2515a'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.DISABLE','BUTTON','supply:user:disable','禁用用户',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('fd1503ab-8fcd-5c0c-9f7c-946e2216a471'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.DELETE','BUTTON','supply:user:delete','删除用户',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('3ec60312-ead9-5f15-a9f9-35fc452adf1d'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.ASSIGN_ROLE','BUTTON','supply:user:assign-role','分配角色',50,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('936cb616-b308-5cf5-8627-fd9a4c6a89d4'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.RESET_PASSWORD','BUTTON','supply:user:reset-password','重置密码',60,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('c68bb602-3dd8-5f27-9881-5f1e9ab0d898'),@app_supply,UUID_TO_BIN('1c32fe38-fa3b-58c7-ae0c-ca7a305f6284'),'SUPPLY_CHAIN.SETTINGS.USERS.REBIND','BUTTON','supply:user:rebind','更正员工关联',70,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.ROLES','PAGE','supply:role:read','角色管理',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'supply.settings.roles','/supply-chain/settings/roles','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('5beb5535-af83-5760-bd27-38211209cab4'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.CREATE','BUTTON','supply:role:create','新增角色',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('f520c698-2e8b-54f4-bd6b-d2b33c86fb06'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.UPDATE','BUTTON','supply:role:update','编辑角色',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('ae17de1d-958d-5c81-ba15-29d33dfbb7d8'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.DELETE','BUTTON','supply:role:delete','删除角色',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('a9930278-a152-5bfb-9506-d0d05af07b7f'),@app_supply,UUID_TO_BIN('445362bb-6c75-5e80-b742-da6e6a06e423'),'SUPPLY_CHAIN.SETTINGS.ROLES.GRANT','BUTTON','supply:role:grant','配置授权',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.MENUS','PAGE','supply:menu:read','菜单管理',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'supply.settings.menus','/supply-chain/settings/menus','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('77810b19-ab90-53ee-b7ff-2cc5c239c3ec'),@app_supply,UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'SUPPLY_CHAIN.SETTINGS.MENUS.CREATE','BUTTON','supply:menu:create','新增菜单',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('f180be07-334e-5431-95fd-fefe861e13f3'),@app_supply,UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'SUPPLY_CHAIN.SETTINGS.MENUS.UPDATE','BUTTON','supply:menu:update','编辑菜单',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('560c16d0-fcb1-5e84-bbee-2054c2531ca6'),@app_supply,UUID_TO_BIN('dc90f2e2-6c27-5269-9fa0-3920c7f2d246'),'SUPPLY_CHAIN.SETTINGS.MENUS.DELETE','BUTTON','supply:menu:delete','删除菜单',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('6623498e-555e-54a9-a58e-92a3244f0bc3'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.PARAMETERS','PAGE','supply:parameter:read','业务参数',50,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('6623498e-555e-54a9-a58e-92a3244f0bc3'),'supply.settings.parameters','/supply-chain/settings/parameters','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('df268e1c-31e4-50bd-8159-9bd1b0741885'),@app_supply,UUID_TO_BIN('6623498e-555e-54a9-a58e-92a3244f0bc3'),'SUPPLY_CHAIN.SETTINGS.PARAMETERS.UPDATE','BUTTON','supply:parameter:update','编辑参数',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at)
VALUES(UUID_TO_BIN('a7c03a8c-7730-5f1e-9602-4476884892f6'),@app_supply,@settings,'SUPPLY_CHAIN.SETTINGS.AUDITS','PAGE','supply:audit:read','操作日志',60,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at)
VALUES(UUID_TO_BIN('a7c03a8c-7730-5f1e-9602-4476884892f6'),'supply.settings.audits','/supply-chain/settings/audits','Setting',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at,created_by)
SELECT existing.package_version_id,r.id,UTC_TIMESTAMP(6),NULL FROM iam_package_resource existing
JOIN iam_resource r ON r.application_id=@app_supply AND r.resource_code LIKE 'SUPPLY_CHAIN.SETTINGS.%'
WHERE existing.resource_id=@settings;
INSERT INTO iam_tenant_menu_config(tenant_id,resource_id,visible,version,created_at,created_by,updated_at,updated_by)
SELECT DISTINCT s.tenant_id,r.id,1,0,UTC_TIMESTAMP(6),NULL,UTC_TIMESTAMP(6),NULL
FROM iam_tenant_subscription s JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id
JOIN iam_resource r ON r.id=pr.resource_id
WHERE r.application_id=@app_supply AND r.resource_code LIKE 'SUPPLY_CHAIN.SETTINGS.%' AND r.resource_type='PAGE'
ON DUPLICATE KEY UPDATE resource_id=iam_tenant_menu_config.resource_id;
