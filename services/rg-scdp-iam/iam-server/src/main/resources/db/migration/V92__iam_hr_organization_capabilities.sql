-- HR 部门与员工维护功能目录，具体组织数据仍由 HR 单库持有。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');

SET @root=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.MENU.HR');

SET @employees=(SELECT id FROM iam_resource WHERE resource_code='SUPPLY_CHAIN.PAGE.HR_EMPLOYEE');

UPDATE iam_resource SET display_name='员工档案',version=version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=@employees;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),@app,@root,'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS','PAGE','hr:department:read','部门管理',5,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'supply.hr.departments','/supply-chain/hr/departments','OfficeBuilding',1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('bc113c0e-38e2-5a2e-9a22-774a20dcf9e3'),@app,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS.CREATE','BUTTON','hr:department:create','新增部门',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('d45552b8-4d56-51b0-ba04-98f1a89d8a02'),@app,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS.UPDATE','BUTTON','hr:department:update','编辑部门',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0db1c838-0479-5fed-91d2-17e8414fea7c'),@app,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),'SUPPLY_CHAIN.HR.SETTINGS.DEPARTMENTS.DELETE','BUTTON','hr:department:delete','删除部门',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('17c21487-8147-539e-af0a-63250e3b70e5'),@app,@employees,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.CREATE','BUTTON','hr:employee:create','新增员工',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('934ad4e9-36e6-5cf2-9b1d-cf3dd42fc137'),@app,@employees,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.UPDATE','BUTTON','hr:employee:update','编辑员工',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('1a851d93-f970-52f1-81f4-1b6bf8b9bef4'),@app,@employees,'SUPPLY_CHAIN.HR.SETTINGS.EMPLOYEES.STATUS','BUTTON','hr:employee:status','变更员工状态',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT existing.package_version_id,r.id,UTC_TIMESTAMP(6) FROM iam_package_resource existing JOIN iam_resource r ON r.application_id=@app AND r.resource_code LIKE 'SUPPLY_CHAIN.HR.SETTINGS.%' WHERE existing.resource_id=@root;

INSERT INTO iam_tenant_menu_config(tenant_id,resource_id,visible,version,created_at,updated_at) SELECT DISTINCT s.tenant_id,UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521'),1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_tenant_subscription s JOIN iam_package_resource pr ON pr.package_version_id=s.package_version_id WHERE pr.resource_id=UUID_TO_BIN('a21eb984-dc3f-5088-aa5c-cd957f36e521') ON DUPLICATE KEY UPDATE resource_id=iam_tenant_menu_config.resource_id;
