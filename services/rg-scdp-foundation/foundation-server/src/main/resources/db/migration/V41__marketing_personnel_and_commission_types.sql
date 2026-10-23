-- 扩展营销费用类型；租户仍按稳定编码覆盖全局字典，既有费用分类不变。
INSERT INTO data_dictionary_item(
    tenant_key,dictionary_code,dictionary_item_level,parent_dictionary_item_code,
    dictionary_item_code,dictionary_item_name,ordinal,revision,created_by,updated_by,deleted
) VALUES
('GLOBAL','MARKETING_EXPENSE_TYPE',1,NULL,'PERSONNEL_COST','人员成本',50,1,'SYSTEM','SYSTEM',0),
('GLOBAL','MARKETING_EXPENSE_TYPE',1,NULL,'COMMISSION','佣金提成',60,1,'SYSTEM','SYSTEM',0);
