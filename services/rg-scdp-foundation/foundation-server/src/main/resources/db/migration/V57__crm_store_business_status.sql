INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items)
VALUES('CRM_STORE_BUSINESS_STATUS','门店经营状态','CRM','独立于公海私海和合作历史；停业门店保留档案并限制新作业。编码固定，名称和排序按字典维护。',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('GLOBAL','CRM_STORE_BUSINESS_STATUS',1,NULL,'UNKNOWN','待核实',10,1,'SYSTEM','SYSTEM',0),
('GLOBAL','CRM_STORE_BUSINESS_STATUS',1,NULL,'OPEN','营业中',20,1,'SYSTEM','SYSTEM',0),
('GLOBAL','CRM_STORE_BUSINESS_STATUS',1,NULL,'TEMP_CLOSED','暂时停业',30,1,'SYSTEM','SYSTEM',0),
('GLOBAL','CRM_STORE_BUSINESS_STATUS',1,NULL,'CLOSED','已停业',40,1,'SYSTEM','SYSTEM',0);
