-- Preserve existing expense types and add nature-based office/freight subtypes.
INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted) VALUES
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'FIXED_ASSET','办公固定资产',60,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'CONSUMABLE','办公耗材',70,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'NETWORK','网络费用',80,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'OFFICE_MOVING','办公搬运',90,1,'SYSTEM','SYSTEM',0),
('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'FREIGHT','货物运输',100,1,'SYSTEM','SYSTEM',0);
