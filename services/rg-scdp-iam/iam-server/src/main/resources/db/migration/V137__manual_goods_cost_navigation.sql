-- 新增功能目录和套餐资源；普通角色需管理员按需授权，不扩大既有角色的数据范围。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');

SET @hr=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.hr.menu');

SET @erp=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.menu');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),@app,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES','PAGE',NULL,'进货记录',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'supply.cost.purchases','/supply-chain/cost/purchases',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),n.id,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'PAGE','进货记录',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('ed76bfc9-c7a9-5e74-920d-f5501102f694'),@app,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES.READ','BUTTON','erp:cost:purchase:read','查看进货记录',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('ed76bfc9-c7a9-5e74-920d-f5501102f694'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('ed76bfc9-c7a9-5e74-920d-f5501102f694'),n.id,UUID_TO_BIN('ed76bfc9-c7a9-5e74-920d-f5501102f694'),'BUTTON','查看进货记录',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('58083c55-e027-5480-93d6-e8aab7472742'),@app,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES.WRITE','BUTTON','erp:cost:purchase:write','维护进货记录',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('58083c55-e027-5480-93d6-e8aab7472742'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('58083c55-e027-5480-93d6-e8aab7472742'),n.id,UUID_TO_BIN('58083c55-e027-5480-93d6-e8aab7472742'),'BUTTON','维护进货记录',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('5b4fca74-ab9b-5393-8e0a-fe15ade0c7c7'),@app,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES.CONFIRM','BUTTON','erp:cost:purchase:confirm','确认进货记录',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('5b4fca74-ab9b-5393-8e0a-fe15ade0c7c7'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('5b4fca74-ab9b-5393-8e0a-fe15ade0c7c7'),n.id,UUID_TO_BIN('5b4fca74-ab9b-5393-8e0a-fe15ade0c7c7'),'BUTTON','确认进货记录',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0e2c6997-26f3-5c43-b8db-2aced1529987'),@app,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES.VOID','BUTTON','erp:cost:purchase:void','作废进货记录',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('0e2c6997-26f3-5c43-b8db-2aced1529987'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('0e2c6997-26f3-5c43-b8db-2aced1529987'),n.id,UUID_TO_BIN('0e2c6997-26f3-5c43-b8db-2aced1529987'),'BUTTON','作废进货记录',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8') AND n.deleted_at IS NULL;


INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),@app,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES','PAGE',NULL,'损耗记录',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'supply.cost.losses','/supply-chain/cost/losses',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),n.id,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'PAGE','损耗记录',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('6071de23-128b-5915-a814-a7dd6dd87e81'),@app,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES.READ','BUTTON','erp:cost:loss:read','查看损耗记录',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('6071de23-128b-5915-a814-a7dd6dd87e81'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('6071de23-128b-5915-a814-a7dd6dd87e81'),n.id,UUID_TO_BIN('6071de23-128b-5915-a814-a7dd6dd87e81'),'BUTTON','查看损耗记录',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('b331fce8-94b8-5a95-b4b1-870f7d9ea052'),@app,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES.WRITE','BUTTON','erp:cost:loss:write','维护损耗记录',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('b331fce8-94b8-5a95-b4b1-870f7d9ea052'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('b331fce8-94b8-5a95-b4b1-870f7d9ea052'),n.id,UUID_TO_BIN('b331fce8-94b8-5a95-b4b1-870f7d9ea052'),'BUTTON','维护损耗记录',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0c6541ce-c4d8-5e0e-9c2e-e88e2eb653b7'),@app,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES.CONFIRM','BUTTON','erp:cost:loss:confirm','确认损耗记录',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('0c6541ce-c4d8-5e0e-9c2e-e88e2eb653b7'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('0c6541ce-c4d8-5e0e-9c2e-e88e2eb653b7'),n.id,UUID_TO_BIN('0c6541ce-c4d8-5e0e-9c2e-e88e2eb653b7'),'BUTTON','确认损耗记录',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('4dc6d4b9-e3ef-54d1-aac5-ff69382f3c56'),@app,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES.VOID','BUTTON','erp:cost:loss:void','作废损耗记录',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('4dc6d4b9-e3ef-54d1-aac5-ff69382f3c56'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('4dc6d4b9-e3ef-54d1-aac5-ff69382f3c56'),n.id,UUID_TO_BIN('4dc6d4b9-e3ef-54d1-aac5-ff69382f3c56'),'BUTTON','作废损耗记录',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d') AND n.deleted_at IS NULL;


UPDATE iam_resource SET display_name=REPLACE(display_name,'仓库租赁成本','场地费用') WHERE resource_code LIKE 'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS%';
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id SET n.display_name=r.display_name WHERE r.resource_code LIKE 'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS%';

-- 导出单独授权，执行与对应动作一致的数据范围。
INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a9aa67f1-c5b7-5f57-bf4b-d794de75b6ac'),@app,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS.EXPORT','BUTTON','order:logistics-cost:export','导出物流成本信息',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('a9aa67f1-c5b7-5f57-bf4b-d794de75b6ac'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('a9aa67f1-c5b7-5f57-bf4b-d794de75b6ac'),n.id,UUID_TO_BIN('a9aa67f1-c5b7-5f57-bf4b-d794de75b6ac'),'BUTTON','导出物流成本信息',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('414d844b-19ef-5464-af41-9804bfb9931a'),@app,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING.EXPORT','BUTTON','erp:cost:marketing:export','导出营销费用成本',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('414d844b-19ef-5464-af41-9804bfb9931a'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('414d844b-19ef-5464-af41-9804bfb9931a'),n.id,UUID_TO_BIN('414d844b-19ef-5464-af41-9804bfb9931a'),'BUTTON','导出营销费用成本',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('8adc66b8-39b9-516a-aa6f-28f0f907d524'),@app,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS.EXPORT','BUTTON','erp:cost:rental:export','导出仓库租赁成本',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('8adc66b8-39b9-516a-aa6f-28f0f907d524'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('8adc66b8-39b9-516a-aa6f-28f0f907d524'),n.id,UUID_TO_BIN('8adc66b8-39b9-516a-aa6f-28f0f907d524'),'BUTTON','导出仓库租赁成本',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('50b682bb-b401-54db-aecf-ed3899576be8'),@app,UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.PURCHASES.EXPORT','BUTTON','erp:cost:purchase:export','导出进货记录',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('50b682bb-b401-54db-aecf-ed3899576be8'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('50b682bb-b401-54db-aecf-ed3899576be8'),n.id,UUID_TO_BIN('50b682bb-b401-54db-aecf-ed3899576be8'),'BUTTON','导出进货记录',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4043d659-24c5-554b-bc01-3b0366ec15a8') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('7220d74a-eead-5cca-9c6d-39a7791023eb'),@app,UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOSSES.EXPORT','BUTTON','erp:cost:loss:export','导出损耗记录',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('7220d74a-eead-5cca-9c6d-39a7791023eb'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('7220d74a-eead-5cca-9c6d-39a7791023eb'),n.id,UUID_TO_BIN('7220d74a-eead-5cca-9c6d-39a7791023eb'),'BUTTON','导出损耗记录',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('ca0ee83c-b2db-5faf-bbc0-e7c6a148004d') AND n.deleted_at IS NULL;
