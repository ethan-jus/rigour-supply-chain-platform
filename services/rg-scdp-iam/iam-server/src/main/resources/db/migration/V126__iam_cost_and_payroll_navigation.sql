-- 新增功能目录和套餐资源；普通角色需管理员按需授权，不扩大既有角色的数据范围。

SET @app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN');

SET @hr=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.hr.menu');

SET @erp=(SELECT resource_id FROM iam_resource_ui WHERE route_key='supply.erp.menu');

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),@app,NULL,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MENU','MENU',NULL,'成本管理',45,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'supply.cost.menu',NULL,'DataAnalysis',TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),n.parent_id,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'MENU','成本管理',45,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=@erp AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),@app,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS','PAGE',NULL,'物流成本信息',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'supply.cost.logistics','/supply-chain/cost/logistics',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),n.id,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'PAGE','物流成本信息',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('274dbedd-2d3f-5ab0-92ab-68f7a7f2458d'),@app,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS.READ','BUTTON','order:logistics-cost:read','查看物流成本信息',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('274dbedd-2d3f-5ab0-92ab-68f7a7f2458d'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('274dbedd-2d3f-5ab0-92ab-68f7a7f2458d'),n.id,UUID_TO_BIN('274dbedd-2d3f-5ab0-92ab-68f7a7f2458d'),'BUTTON','查看物流成本信息',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('02d2a756-7be6-5ff1-a64f-6c7546affec7'),@app,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS.WRITE','BUTTON','order:logistics-cost:write','维护物流成本信息',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('02d2a756-7be6-5ff1-a64f-6c7546affec7'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('02d2a756-7be6-5ff1-a64f-6c7546affec7'),n.id,UUID_TO_BIN('02d2a756-7be6-5ff1-a64f-6c7546affec7'),'BUTTON','维护物流成本信息',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('608191eb-e47c-5601-86dc-c7e32b92737e'),@app,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS.CONFIRM','BUTTON','order:logistics-cost:confirm','确认物流成本信息',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('608191eb-e47c-5601-86dc-c7e32b92737e'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('608191eb-e47c-5601-86dc-c7e32b92737e'),n.id,UUID_TO_BIN('608191eb-e47c-5601-86dc-c7e32b92737e'),'BUTTON','确认物流成本信息',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('8803ef56-e75d-568a-8053-4ec7cce9812e'),@app,UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.LOGISTICS.VOID','BUTTON','order:logistics-cost:void','作废物流成本信息',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('8803ef56-e75d-568a-8053-4ec7cce9812e'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('8803ef56-e75d-568a-8053-4ec7cce9812e'),n.id,UUID_TO_BIN('8803ef56-e75d-568a-8053-4ec7cce9812e'),'BUTTON','作废物流成本信息',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('1607b240-7770-50cb-84e4-876bab33f59a') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),@app,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING','PAGE',NULL,'营销费用成本',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'supply.cost.marketing','/supply-chain/cost/marketing',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),n.id,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'PAGE','营销费用成本',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('182eb672-1ee4-58eb-9050-9b108b7e5336'),@app,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING.READ','BUTTON','erp:cost:marketing:read','查看营销费用成本',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('182eb672-1ee4-58eb-9050-9b108b7e5336'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('182eb672-1ee4-58eb-9050-9b108b7e5336'),n.id,UUID_TO_BIN('182eb672-1ee4-58eb-9050-9b108b7e5336'),'BUTTON','查看营销费用成本',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('433fc760-cc9f-5655-8c7e-c5eb1ad1e534'),@app,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING.WRITE','BUTTON','erp:cost:marketing:write','维护营销费用成本',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('433fc760-cc9f-5655-8c7e-c5eb1ad1e534'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('433fc760-cc9f-5655-8c7e-c5eb1ad1e534'),n.id,UUID_TO_BIN('433fc760-cc9f-5655-8c7e-c5eb1ad1e534'),'BUTTON','维护营销费用成本',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('cc64f0ac-be1d-552d-a5e5-f31e94829578'),@app,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING.CONFIRM','BUTTON','erp:cost:marketing:confirm','确认营销费用成本',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('cc64f0ac-be1d-552d-a5e5-f31e94829578'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('cc64f0ac-be1d-552d-a5e5-f31e94829578'),n.id,UUID_TO_BIN('cc64f0ac-be1d-552d-a5e5-f31e94829578'),'BUTTON','确认营销费用成本',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('7ecd3358-e521-5939-952b-37ce3f16a319'),@app,UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.MARKETING.VOID','BUTTON','erp:cost:marketing:void','作废营销费用成本',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('7ecd3358-e521-5939-952b-37ce3f16a319'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('7ecd3358-e521-5939-952b-37ce3f16a319'),n.id,UUID_TO_BIN('7ecd3358-e521-5939-952b-37ce3f16a319'),'BUTTON','作废营销费用成本',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('fa511e0e-bce6-565e-b112-7dfe8a4218f9') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),@app,UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS','PAGE',NULL,'仓库租赁成本',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'supply.cost.warehouse-rentals','/supply-chain/cost/warehouse-rentals',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),n.id,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'PAGE','仓库租赁成本',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('0878b9aa-44fa-5fef-99f6-3493d0b08aa4') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('ac8ba67a-8ab4-5390-87b6-ae51148c3b02'),@app,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS.READ','BUTTON','erp:cost:rental:read','查看仓库租赁成本',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('ac8ba67a-8ab4-5390-87b6-ae51148c3b02'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('ac8ba67a-8ab4-5390-87b6-ae51148c3b02'),n.id,UUID_TO_BIN('ac8ba67a-8ab4-5390-87b6-ae51148c3b02'),'BUTTON','查看仓库租赁成本',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('3f36e599-d71c-5c8e-af8d-a927fb82937b'),@app,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS.WRITE','BUTTON','erp:cost:rental:write','维护仓库租赁成本',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('3f36e599-d71c-5c8e-af8d-a927fb82937b'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('3f36e599-d71c-5c8e-af8d-a927fb82937b'),n.id,UUID_TO_BIN('3f36e599-d71c-5c8e-af8d-a927fb82937b'),'BUTTON','维护仓库租赁成本',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('d62532ef-1cb7-59b4-8513-7ad6823326a8'),@app,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS.CONFIRM','BUTTON','erp:cost:rental:confirm','确认仓库租赁成本',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('d62532ef-1cb7-59b4-8513-7ad6823326a8'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('d62532ef-1cb7-59b4-8513-7ad6823326a8'),n.id,UUID_TO_BIN('d62532ef-1cb7-59b4-8513-7ad6823326a8'),'BUTTON','确认仓库租赁成本',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('5a63e4a0-3be4-5fac-b169-bf3b992fe1f6'),@app,UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.COST.WAREHOUSE-RENTALS.VOID','BUTTON','erp:cost:rental:void','作废仓库租赁成本',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('5a63e4a0-3be4-5fac-b169-bf3b992fe1f6'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@erp;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('5a63e4a0-3be4-5fac-b169-bf3b992fe1f6'),n.id,UUID_TO_BIN('5a63e4a0-3be4-5fac-b169-bf3b992fe1f6'),'BUTTON','作废仓库租赁成本',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('4de9ac56-602e-52b7-b4dd-db2545559efc') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),@app,@hr,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL-SETTINGS.MENU','MENU',NULL,'薪酬配置',35,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),'supply.hr.payroll-settings.menu',NULL,NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),n.id,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),'MENU','薪酬配置',35,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=@hr AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),@app,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.SALARY-STANDARDS','PAGE',NULL,'薪资标准',17,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),'supply.hr.salary-standards','/supply-chain/hr/salary-standards',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),n.id,UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),'PAGE','薪资标准',17,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('cb2f4ad1-2372-5f31-911a-4f504d79a617'),@app,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.COMMISSION-RULES','PAGE',NULL,'商品提成设置',18,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('cb2f4ad1-2372-5f31-911a-4f504d79a617'),'supply.hr.commission-rules','/supply-chain/hr/commission-rules',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('cb2f4ad1-2372-5f31-911a-4f504d79a617'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('cb2f4ad1-2372-5f31-911a-4f504d79a617'),n.id,UUID_TO_BIN('cb2f4ad1-2372-5f31-911a-4f504d79a617'),'PAGE','商品提成设置',18,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('63269125-8f7f-50f7-85a2-9a6e7aea6c73'),@app,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PERFORMANCE-RULES','PAGE',NULL,'绩效规则设置',19,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('63269125-8f7f-50f7-85a2-9a6e7aea6c73'),'supply.hr.performance-rules','/supply-chain/hr/performance-rules',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('63269125-8f7f-50f7-85a2-9a6e7aea6c73'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('63269125-8f7f-50f7-85a2-9a6e7aea6c73'),n.id,UUID_TO_BIN('63269125-8f7f-50f7-85a2-9a6e7aea6c73'),'PAGE','绩效规则设置',19,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('786f4b7b-23ce-50fc-842e-a562dc9a4d80'),@app,UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.ALLOWANCE-RULES','PAGE',NULL,'其他薪资设置',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('786f4b7b-23ce-50fc-842e-a562dc9a4d80'),'supply.hr.allowance-rules','/supply-chain/hr/allowance-rules',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('786f4b7b-23ce-50fc-842e-a562dc9a4d80'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('786f4b7b-23ce-50fc-842e-a562dc9a4d80'),n.id,UUID_TO_BIN('786f4b7b-23ce-50fc-842e-a562dc9a4d80'),'PAGE','其他薪资设置',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('b1d3f8ee-eebb-5639-b2d0-105e6f8201c6') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('d20e385a-f794-51d1-9f6d-890df4fad97e'),@app,UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.SALARY-STANDARDS.READ','BUTTON','hr:payroll-rule:read','查看薪酬规则',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('d20e385a-f794-51d1-9f6d-890df4fad97e'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('d20e385a-f794-51d1-9f6d-890df4fad97e'),n.id,UUID_TO_BIN('d20e385a-f794-51d1-9f6d-890df4fad97e'),'BUTTON','查看薪酬规则',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('d4a2ab89-3e49-552d-b0ed-10c4b3a7d479'),@app,UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.SALARY-STANDARDS.WRITE','BUTTON','hr:payroll-rule:write','维护薪酬规则',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('d4a2ab89-3e49-552d-b0ed-10c4b3a7d479'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('d4a2ab89-3e49-552d-b0ed-10c4b3a7d479'),n.id,UUID_TO_BIN('d4a2ab89-3e49-552d-b0ed-10c4b3a7d479'),'BUTTON','维护薪酬规则',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('41da483f-9164-54be-80c9-093c75bf7752') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),@app,@hr,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL','PAGE',NULL,'薪酬列表',40,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),'supply.hr.payroll','/supply-chain/hr/payroll',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),n.id,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),'PAGE','薪酬列表',40,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=@hr AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('f359ce2e-91c2-519e-8ead-1adaba84dd2f'),@app,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL.READ','BUTTON','hr:payroll:read','查看薪酬',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('f359ce2e-91c2-519e-8ead-1adaba84dd2f'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('f359ce2e-91c2-519e-8ead-1adaba84dd2f'),n.id,UUID_TO_BIN('f359ce2e-91c2-519e-8ead-1adaba84dd2f'),'BUTTON','查看薪酬',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('a86eb5ac-2eac-5ac2-a24d-c95adc82e8cb'),@app,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL.CALCULATE','BUTTON','hr:payroll:calculate','核算和调整',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('a86eb5ac-2eac-5ac2-a24d-c95adc82e8cb'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('a86eb5ac-2eac-5ac2-a24d-c95adc82e8cb'),n.id,UUID_TO_BIN('a86eb5ac-2eac-5ac2-a24d-c95adc82e8cb'),'BUTTON','核算和调整',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('370271c6-498a-5716-9386-6e6374545870'),@app,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL.CONFIRM','BUTTON','hr:payroll:confirm','确认薪酬',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('370271c6-498a-5716-9386-6e6374545870'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('370271c6-498a-5716-9386-6e6374545870'),n.id,UUID_TO_BIN('370271c6-498a-5716-9386-6e6374545870'),'BUTTON','确认薪酬',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('144ccf9d-1622-55a9-a336-a65cde70ff9a'),@app,UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.PAYROLL.PAID','BUTTON','hr:payroll:paid','登记薪酬发放',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('144ccf9d-1622-55a9-a336-a65cde70ff9a'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('144ccf9d-1622-55a9-a336-a65cde70ff9a'),n.id,UUID_TO_BIN('144ccf9d-1622-55a9-a336-a65cde70ff9a'),'BUTTON','登记薪酬发放',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('2f362360-e660-51f0-8a82-7a25b52c2660') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),@app,@hr,'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.EXPENSES','PAGE',NULL,'报销费用',45,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT INTO iam_resource_ui(resource_id,route_key,route_path,icon_key,visible,keep_alive,created_at,updated_at) VALUES(UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),'supply.hr.expenses','/supply-chain/hr/expenses',NULL,TRUE,FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),n.id,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),'PAGE','报销费用',45,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=@hr AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('56499e58-8171-55d4-8df1-3bbd8f7ed783'),@app,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.EXPENSES.READ','BUTTON','hr:expense:read','查看报销',0,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('56499e58-8171-55d4-8df1-3bbd8f7ed783'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('56499e58-8171-55d4-8df1-3bbd8f7ed783'),n.id,UUID_TO_BIN('56499e58-8171-55d4-8df1-3bbd8f7ed783'),'BUTTON','查看报销',0,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('0170c923-977e-561c-9e6e-be1f66384963'),@app,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.EXPENSES.WRITE','BUTTON','hr:expense:write','维护报销',10,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('0170c923-977e-561c-9e6e-be1f66384963'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('0170c923-977e-561c-9e6e-be1f66384963'),n.id,UUID_TO_BIN('0170c923-977e-561c-9e6e-be1f66384963'),'BUTTON','维护报销',10,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('627b24d8-55ed-54cd-a0e2-d2260a7c76a5'),@app,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.EXPENSES.CONFIRM','BUTTON','hr:expense:confirm','确认报销',20,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('627b24d8-55ed-54cd-a0e2-d2260a7c76a5'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('627b24d8-55ed-54cd-a0e2-d2260a7c76a5'),n.id,UUID_TO_BIN('627b24d8-55ed-54cd-a0e2-d2260a7c76a5'),'BUTTON','确认报销',20,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5') AND n.deleted_at IS NULL;

INSERT INTO iam_resource(id,application_id,parent_id,resource_code,resource_type,permission_code,display_name,sort_order,status,created_at,updated_at) VALUES(UUID_TO_BIN('28d66080-0a57-535e-a272-0d891aee07ca'),@app,UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5'),'SUPPLY_CHAIN.COST_PAYROLL.SUPPLY.HR.EXPENSES.VOID','BUTTON','hr:expense:void','作废报销',30,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));

INSERT IGNORE INTO iam_package_resource(package_version_id,resource_id,created_at) SELECT package_version_id,UUID_TO_BIN('28d66080-0a57-535e-a272-0d891aee07ca'),UTC_TIMESTAMP(6) FROM iam_package_resource WHERE resource_id=@hr;

INSERT INTO iam_app_menu_node(tenant_id,application_id,id,parent_id,resource_id,node_type,display_name,sort_order,visible,status,created_at,updated_at) SELECT n.tenant_id,n.application_id,UUID_TO_BIN('28d66080-0a57-535e-a272-0d891aee07ca'),n.id,UUID_TO_BIN('28d66080-0a57-535e-a272-0d891aee07ca'),'BUTTON','作废报销',30,TRUE,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM iam_app_menu_node n WHERE n.resource_id=UUID_TO_BIN('d9f3ed95-2a1f-5d09-b159-62635ea3b1f5') AND n.deleted_at IS NULL;
