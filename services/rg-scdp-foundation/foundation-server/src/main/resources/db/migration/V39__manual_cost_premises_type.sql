INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items) VALUES('COST_PREMISES_TYPE','场地类型','COST','手工场地费用登记',1,'SYSTEM','SYSTEM',0,FALSE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('COST_PREMISES_TYPE',1,NULL,'WAREHOUSE','仓储',10,1,'SYSTEM','SYSTEM',0),
('COST_PREMISES_TYPE',1,NULL,'OFFICE','办公室',20,1,'SYSTEM','SYSTEM',0);
