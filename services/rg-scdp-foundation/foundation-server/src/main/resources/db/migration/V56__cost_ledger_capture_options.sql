-- V49 已发布，继续追加演进，不改写历史迁移。未知付款状态不等于未付款。
UPDATE data_dictionary SET allow_new_items=TRUE WHERE dictionary_code IN ('COST_CARRIER','COST_LOGISTICS_FEE_TYPE','COST_WAREHOUSE_FEE_TYPE') AND deleted=0;
INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items)
VALUES('COST_TRANSPORT_KIND','运输方式','COST','与承运方式及物流公司分开',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('COST_TRANSPORT_KIND',1,NULL,'ROAD','公路',10,1,'SYSTEM','SYSTEM',0),
('COST_TRANSPORT_KIND',1,NULL,'AIR','航空',20,1,'SYSTEM','SYSTEM',0),
('COST_TRANSPORT_KIND',1,NULL,'EXPRESS','快递',30,1,'SYSTEM','SYSTEM',0),
('COST_TRANSPORT_KIND',1,NULL,'RAIL','铁路',40,1,'SYSTEM','SYSTEM',0),
('COST_TRANSPORT_KIND',1,NULL,'WATER','水运',50,1,'SYSTEM','SYSTEM',0),
('COST_TRANSPORT_KIND',1,NULL,'OTHER','其他',60,1,'SYSTEM','SYSTEM',0),
('COST_PAYMENT_STATUS',1,NULL,'UNKNOWN','待核实',90,1,'SYSTEM','SYSTEM',0);
