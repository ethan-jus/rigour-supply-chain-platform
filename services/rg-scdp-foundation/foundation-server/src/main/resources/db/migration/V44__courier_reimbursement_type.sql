-- Employee-paid non-sales postage remains reimbursement; freight keeps its existing type.
INSERT INTO data_dictionary_item
    (tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,
     dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted)
VALUES ('GLOBAL','REIMBURSEMENT_EXPENSE_TYPE',1,NULL,'COURIER','快递／邮寄费',110,1,'SYSTEM','SYSTEM',0);
