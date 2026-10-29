-- Store types remain unclassified until business-maintained dictionary items exist.
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted)
VALUES('CRM_STORE_TYPE','门店类型','CRM','门店经营类型；不能从门店名称自动猜测',1,'SYSTEM','SYSTEM',0),
('GEO_REGION','七大地理区域','COMMON','按已确认七大区域划分省级行政节点',1,'SYSTEM','SYSTEM',0),
('ADMIN_LEVEL','行政等级','COMMON','省级、地级、县级行政节点',1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('GLOBAL','GEO_REGION',1,NULL,'NORTH','华北',10,1,'SYSTEM','SYSTEM',0),
('GLOBAL','GEO_REGION',1,NULL,'NORTHEAST','东北',20,1,'SYSTEM','SYSTEM',0),
('GLOBAL','GEO_REGION',1,NULL,'EAST','华东',30,1,'SYSTEM','SYSTEM',0),
('GLOBAL','GEO_REGION',1,NULL,'CENTRAL','华中',40,1,'SYSTEM','SYSTEM',0),
('GLOBAL','GEO_REGION',1,NULL,'SOUTH','华南',50,1,'SYSTEM','SYSTEM',0),
('GLOBAL','GEO_REGION',1,NULL,'SOUTHWEST','西南',60,1,'SYSTEM','SYSTEM',0),
('GLOBAL','GEO_REGION',1,NULL,'NORTHWEST','西北',70,1,'SYSTEM','SYSTEM',0);
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('GLOBAL','ADMIN_LEVEL',1,NULL,'PROVINCE','省级',10,1,'SYSTEM','SYSTEM',0),
('GLOBAL','ADMIN_LEVEL',1,NULL,'PREFECTURE','地级',20,1,'SYSTEM','SYSTEM',0),
('GLOBAL','ADMIN_LEVEL',1,NULL,'COUNTY','县级',30,1,'SYSTEM','SYSTEM',0);
