-- 面积和连锁门店数量是两个独立维度；保持旧编码可解析。
INSERT INTO data_dictionary (dictionary_code,dictionary_name,dictionary_type,remark,created_by,updated_by)
VALUES ('STORE_CHAIN_SIZE','门店连锁规模','CRM','连锁门店数量分层，与单店面积分开','SYSTEM','SYSTEM');
INSERT INTO data_dictionary_item (dictionary_code,dictionary_item_code,dictionary_item_name,ordinal,created_by,updated_by)
VALUES ('STORE_CHAIN_SIZE','SMALL','小型(1-3家)',10,'SYSTEM','SYSTEM'),
       ('STORE_CHAIN_SIZE','MEDIUM','中型(4-10家)',20,'SYSTEM','SYSTEM');
UPDATE data_dictionary_item SET canonical_dictionary_code='STORE_CHAIN_SIZE',canonical_item_code='SMALL',
revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP()
WHERE dictionary_code='STORE_SCALE' AND dictionary_item_code='AUTO_94B411579F15535B6E3511489C48BB363E7D786369FBF' AND deleted=0;
UPDATE data_dictionary_item SET canonical_dictionary_code='STORE_CHAIN_SIZE',canonical_item_code='MEDIUM',
revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP()
WHERE dictionary_code='STORE_SCALE' AND dictionary_item_code='AUTO_1997EE9CA3E365A94E0139DD8384BC21082E19C291C15' AND deleted=0;
UPDATE data_dictionary SET dictionary_name='门店面积',remark='单店面积分层；连锁门店数量使用 STORE_CHAIN_SIZE',
revision=revision+1,updated_by='SYSTEM',updated_time=UTC_TIMESTAMP() WHERE dictionary_code='STORE_SCALE';
