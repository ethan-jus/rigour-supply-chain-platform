INSERT INTO data_dictionary(dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted,allow_new_items)
VALUES('PAYROLL_ALLOWANCE_TYPE','其他薪资类型','HR','销售人员的固定补贴与其他月度补贴',1,'SYSTEM','SYSTEM',0,TRUE);
INSERT INTO data_dictionary_item(dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('PAYROLL_ALLOWANCE_TYPE',1,NULL,'FIXED_ALLOWANCE','固定补贴',10,1,'SYSTEM','SYSTEM',0),
('PAYROLL_ALLOWANCE_TYPE',1,NULL,'OTHER_ALLOWANCE','其他补贴',20,1,'SYSTEM','SYSTEM',0);
