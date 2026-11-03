-- 采购仅保存承担编码，页面沿用已有字典服务解析中文名称。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES ('PURCHASE_FREIGHT_BEARER','采购运费承担方','ERP','采购约定或预估运费，不作为实际物流成本',1,'SYSTEM','SYSTEM',0)
ON DUPLICATE KEY UPDATE dictionary_name=VALUES(dictionary_name),remark=VALUES(remark),updated_by='SYSTEM',deleted=0;

INSERT INTO data_dictionary_item
    (dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,
     dictionary_item_name,remark,ordinal,revision,created_by,updated_by,deleted)
VALUES
    ('PURCHASE_FREIGHT_BEARER',1,NULL,'BUYER','本公司承担','预计支出加单列运费',10,1,'SYSTEM','SYSTEM',0),
    ('PURCHASE_FREIGHT_BEARER',1,NULL,'SUPPLIER','供应商承担','不重复加入本公司预计支出',20,1,'SYSTEM','SYSTEM',0),
    ('PURCHASE_FREIGHT_BEARER',1,NULL,'INCLUDED','货价已含运费','不重复加入单列运费',30,1,'SYSTEM','SYSTEM',0)
ON DUPLICATE KEY UPDATE dictionary_item_name=VALUES(dictionary_item_name),remark=VALUES(remark),ordinal=VALUES(ordinal),updated_by='SYSTEM',deleted=0;

INSERT INTO data_dictionary
    (dictionary_code,dictionary_name,dictionary_type,remark,revision,created_by,updated_by,deleted)
VALUES ('PURCHASE_CONFIRMATION_STATUS','采购确认状态','ERP','确认后采购单只可查看，入库进度独立维护',1,'SYSTEM','SYSTEM',0)
ON DUPLICATE KEY UPDATE dictionary_name=VALUES(dictionary_name),remark=VALUES(remark),updated_by='SYSTEM',deleted=0;
INSERT INTO data_dictionary_item
    (dictionary_code,dictionary_item_level,parent_dictionary_item_code,dictionary_item_code,
     dictionary_item_name,remark,ordinal,revision,created_by,updated_by,deleted)
VALUES
    ('PURCHASE_CONFIRMATION_STATUS',1,NULL,'UNCONFIRMED','未确认','允许查看、编辑、删除和确认',10,1,'SYSTEM','SYSTEM',0),
    ('PURCHASE_CONFIRMATION_STATUS',1,NULL,'CONFIRMED','已确认','采购内容仅可查看',20,1,'SYSTEM','SYSTEM',0)
ON DUPLICATE KEY UPDATE dictionary_item_name=VALUES(dictionary_item_name),remark=VALUES(remark),ordinal=VALUES(ordinal),updated_by='SYSTEM',deleted=0;
