-- 拜访表单统一字典；沿用已导入的经营类型编码，不改写历史基线。
INSERT INTO data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,allow_new_items,created_by)
SELECT 'GLOBAL','VISIT_CONTACT_OUTCOME','拜访沟通结果','CRM',FALSE,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_CONTACT_OUTCOME');

INSERT INTO data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,allow_new_items,created_by)
SELECT 'GLOBAL','VISIT_NO_CONTACT_REASON','拜访未接触原因','CRM',TRUE,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NO_CONTACT_REASON');

INSERT INTO data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,allow_new_items,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','拜访后续行动','CRM',TRUE,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION');

INSERT INTO data_dictionary(tenant_key,dictionary_code,dictionary_name,dictionary_type,allow_new_items,created_by)
SELECT 'GLOBAL','STORE_CONTACT_ROLE','门店联系人角色','CRM',TRUE,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_CONTACT_ROLE');

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_CONTACT_OUTCOME','CONTACTED','已沟通',10,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_CONTACT_OUTCOME' AND (dictionary_item_code='CONTACTED' OR (dictionary_item_name='已沟通' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_CONTACT_OUTCOME','OTHER_NO_CONTACT','未接触',20,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_CONTACT_OUTCOME' AND (dictionary_item_code='OTHER_NO_CONTACT' OR (dictionary_item_name='未接触' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NO_CONTACT_REASON','CLOSED','门店未营业',10,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NO_CONTACT_REASON' AND (dictionary_item_code='CLOSED' OR (dictionary_item_name='门店未营业' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NO_CONTACT_REASON','CONTACT_ABSENT','接待人不在',20,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NO_CONTACT_REASON' AND (dictionary_item_code='CONTACT_ABSENT' OR (dictionary_item_name='接待人不在' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NO_CONTACT_REASON','BUSY','暂不方便沟通',30,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NO_CONTACT_REASON' AND (dictionary_item_code='BUSY' OR (dictionary_item_name='暂不方便沟通' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NO_CONTACT_REASON','DECLINED','拒绝接待',40,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NO_CONTACT_REASON' AND (dictionary_item_code='DECLINED' OR (dictionary_item_name='拒绝接待' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NO_CONTACT_REASON','OTHER','其他',50,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NO_CONTACT_REASON' AND (dictionary_item_code='OTHER' OR (dictionary_item_name='其他' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','CONTACT','再次联系',10,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION' AND (dictionary_item_code='CONTACT' OR (dictionary_item_name='再次联系' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','VISIT','再次到店',20,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION' AND (dictionary_item_code='VISIT' OR (dictionary_item_name='再次到店' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','PROPOSAL','发送方案',30,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION' AND (dictionary_item_code='PROPOSAL' OR (dictionary_item_name='发送方案' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','QUOTATION','提供报价',40,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION' AND (dictionary_item_code='QUOTATION' OR (dictionary_item_name='提供报价' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','VISIT_NEXT_ACTION','SERVICE','跟进合作服务',50,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='VISIT_NEXT_ACTION' AND (dictionary_item_code='SERVICE' OR (dictionary_item_name='跟进合作服务' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_CONTACT_ROLE','OWNER','老板',10,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_CONTACT_ROLE' AND (dictionary_item_code='OWNER' OR (dictionary_item_name='老板' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_CONTACT_ROLE','MANAGER','店长',20,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_CONTACT_ROLE' AND (dictionary_item_code='MANAGER' OR (dictionary_item_name='店长' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_CONTACT_ROLE','STAFF','店员',30,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_CONTACT_ROLE' AND (dictionary_item_code='STAFF' OR (dictionary_item_name='店员' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_CONTACT_ROLE','OTHER','其他',40,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_CONTACT_ROLE' AND (dictionary_item_code='OTHER' OR (dictionary_item_name='其他' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_BUSINESS_TYPE','AUTO_7F8D0E9D0FC62D973798AAE13AC92154E4A2A82B2786D','商业娱乐',10,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_BUSINESS_TYPE' AND (dictionary_item_code='AUTO_7F8D0E9D0FC62D973798AAE13AC92154E4A2A82B2786D' OR (dictionary_item_name='商业娱乐' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_BUSINESS_TYPE','AUTO_3B539856994A7676CE7B380ED587412543057A4776AEE','竞技赛事',20,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_BUSINESS_TYPE' AND (dictionary_item_code='AUTO_3B539856994A7676CE7B380ED587412543057A4776AEE' OR (dictionary_item_name='竞技赛事' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_BUSINESS_TYPE','AUTO_141C6023DA1392ECA9BB43F6851C9D55261A5987CD156','培训教育',30,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_BUSINESS_TYPE' AND (dictionary_item_code='AUTO_141C6023DA1392ECA9BB43F6851C9D55261A5987CD156' OR (dictionary_item_name='培训教育' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_BUSINESS_TYPE','AUTO_529C22D66B5374AA3E9EE7A449867BAE197830335C23D','综合经营',40,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_BUSINESS_TYPE' AND (dictionary_item_code='AUTO_529C22D66B5374AA3E9EE7A449867BAE197830335C23D' OR (dictionary_item_name='综合经营' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_BUSINESS_TYPE','OTHER','其他',50,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_BUSINESS_TYPE' AND (dictionary_item_code='OTHER' OR (dictionary_item_name='其他' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','STORE_BUSINESS_TYPE','UNKNOWN','暂未核实',60,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='STORE_BUSINESS_TYPE' AND (dictionary_item_code='UNKNOWN' OR (dictionary_item_name='暂未核实' AND canonical_item_code IS NULL)));

INSERT INTO data_dictionary_item(tenant_key,dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by)
SELECT 'GLOBAL','CUSTOMER_COOP_STATUS','COOPERATED','已合作',10,'SYSTEM'
WHERE NOT EXISTS(SELECT 1 FROM data_dictionary_item WHERE tenant_key='GLOBAL' AND dictionary_code='CUSTOMER_COOP_STATUS' AND (dictionary_item_code='COOPERATED' OR (dictionary_item_name='已合作' AND canonical_item_code IS NULL)));

UPDATE data_dictionary SET revision=revision+1 WHERE tenant_key='GLOBAL' AND dictionary_code IN ('STORE_BUSINESS_TYPE','CUSTOMER_COOP_STATUS');
