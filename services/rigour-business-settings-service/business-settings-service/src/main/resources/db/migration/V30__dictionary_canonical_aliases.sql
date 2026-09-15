-- 标准选项与历史编码兼容分离：保留旧编码供历史解析，禁止来源观察自动扩充标准项。
ALTER TABLE data_dictionary_item
    ADD COLUMN canonical_dictionary_code VARCHAR(50) NULL COMMENT '历史兼容项的标准字典',
    ADD COLUMN canonical_item_code VARCHAR(50) NULL COMMENT '历史兼容项的标准编码',
    ADD CONSTRAINT ck_dictionary_canonical_pair CHECK ((canonical_dictionary_code IS NULL) = (canonical_item_code IS NULL)),
    ADD CONSTRAINT fk_dictionary_canonical_item FOREIGN KEY (canonical_dictionary_code, canonical_item_code)
        REFERENCES data_dictionary_item(dictionary_code, dictionary_item_code);

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND standard.dictionary_item_code='DAYS_1_3' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND old_item.dictionary_item_code='AUTO_4E5A27B003E626C701225727C58FBBF7F288960C257F5' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND standard.dictionary_item_code='DAYS_3_5' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ACTIVITY_PREHEAT_PERIOD' AND old_item.dictionary_item_code='AUTO_325287A8AEC72CE0ADA3385B1A48D2D83F6A4827D480E' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='HIGH' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_5AE6F0508F0B2EAB60FC98D71D25FA859F5956652FD6E' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='MEDIUM' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_165DE6EF8BCAD65646B3B2ED890F426B6C8CA216F401D' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='LOW' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_0E4B8B0D392F2910F10FC7B8FE1F2BC13E3FF7401C30C' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND standard.dictionary_item_code='NONE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='CUSTOMER_INTENTION_LEVEL' AND old_item.dictionary_item_code='AUTO_AF028E13AB57FF1AB28F63A586DE9BAFB168CE6500056' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_ORDER_STATUS' AND standard.dictionary_item_code='STOCK_UP' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_ORDER_STATUS' AND old_item.dictionary_item_code='STOCKUP' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BOX' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_B83271F3825DC5A5668D3278D3C8898F7588CDFEFD98B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BUCKET' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_75DD5950072ED4B4CCA74D29ADB9A3326D0889C2EFCC4' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='PORTION' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_B4367149220FCE5B74104E35067603C289729CDE6DAD2' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='SET' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_976F7764129A59B95D400093493D5F235C5D66680A5FE' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_32F3D97C94848D9271E290C7AFA35EE01B2534EDA87FF' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='PAIR' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_A5C7BB87103D948DCA2638D83A57E3E15FA70583340DD' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='BOTTLE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_31E93AC913625A0885AF0E98AC20881C3BED383759627' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='DHB_UNIT' AND standard.dictionary_item_code='STRIP' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='DHB_UNIT' AND old_item.dictionary_item_code='AUTO_A05C43FEC5E24249DE4E607AF30A529455753E1437D29' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ORDER_TYPE' AND standard.dictionary_item_code='NEW' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ORDER_TYPE' AND old_item.dictionary_item_code='AUTO_4CD1395609352B6CF19A926E968CD1C88B51EB44139BD' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='ORDER_TYPE' AND standard.dictionary_item_code='REPEAT' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='ORDER_TYPE' AND old_item.dictionary_item_code='AUTO_8C160D8458977FA0B1F6DDA5AF7ACA63AD0D278C9430B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_METHOD' AND standard.dictionary_item_code='FULL' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_METHOD' AND old_item.dictionary_item_code='AUTO_DDEBEB90FCA558EE6C14705A6066DE05A5E751D1B8F78' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_METHOD' AND standard.dictionary_item_code='MONTHLY' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_METHOD' AND old_item.dictionary_item_code='AUTO_7C90931DCA87B14E5CFDE3483BDCBD1DB0DEEE945A462' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_STATUS' AND standard.dictionary_item_code='UNPAID' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_STATUS' AND old_item.dictionary_item_code='AUTO_9DFF390AA68194B77397EDFA4AEAD542923E594527C99' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PAYMENT_STATUS' AND standard.dictionary_item_code='PAID' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PAYMENT_STATUS' AND old_item.dictionary_item_code='AUTO_F961C8F802CE895879E4181BAB5303703FEDCF739750B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PRODUCT_SOURCE_STATUS' AND standard.dictionary_item_code='ON_SALE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PRODUCT_SOURCE_STATUS' AND old_item.dictionary_item_code='AUTO_8ADBB5C5886BE5B22D0AEDF60FD04A95CA84E5F7D626B' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='PRODUCT_SOURCE_STATUS' AND standard.dictionary_item_code='STOP_SALE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='PRODUCT_SOURCE_STATUS' AND old_item.dictionary_item_code='AUTO_B99E922F2F6CA016C25D8578FE4AD950A8D57EECB171A' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='SALES_SHIPMENT_STATUS' AND standard.dictionary_item_code='SHIPPED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='SALES_SHIPMENT_STATUS' AND old_item.dictionary_item_code='AUTO_49EEB099699F4AB013C900A6F428AF8F56A722A3F9DC7' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='UNDER_100' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_4B8B01F1FE3D0D25A831BA9278E7845891C35D1BD17C1' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='BETWEEN_100_300' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_0A0644A2DAE8ECC8A47335302A7D24972277C566874BF' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='BETWEEN_300_600' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_F583530F3E9A5B6029646FFB9B7290EB26703ED0BF6C2' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_SCALE' AND standard.dictionary_item_code='OVER_600' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_SCALE' AND old_item.dictionary_item_code='AUTO_89A9EE0AF4577794AB0B2093EA553A056D6B3FF881CBF' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='ACTIVE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_342EC393E5D21B3011604639906D593D86E70B5F15B8F' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='PAUSED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_0ACD89A98826F6403F931E3818CC8CA3657075B40D5F1' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='CLOSED' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_D232FA16B4683B4D3CB4143F0A466C02AF42B06389E08' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='RENOVATING' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_2F8F04DF41EEE4BFDEA0940C7BF3C20931B5DEE1E0886' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

UPDATE data_dictionary_item old_item JOIN data_dictionary_item standard ON standard.dictionary_code='STORE_STATUS' AND standard.dictionary_item_code='ACTIVE' AND standard.deleted=0 AND standard.canonical_item_code IS NULL
SET old_item.canonical_dictionary_code=standard.dictionary_code, old_item.canonical_item_code=standard.dictionary_item_code, old_item.revision=old_item.revision+1, old_item.updated_by='SYSTEM', old_item.updated_time=UTC_TIMESTAMP()
WHERE old_item.dictionary_code='STORE_STATUS' AND old_item.dictionary_item_code='AUTO_8BB6580D61E60378EE02CD185B02D3363BECCD7991497' AND old_item.deleted=0 AND old_item.canonical_item_code IS NULL;

-- 同名限制只针对标准项、同父级；历史兼容项仍保留原名。
ALTER TABLE data_dictionary_item
    ADD COLUMN standard_parent_key VARCHAR(50) GENERATED ALWAYS AS (COALESCE(parent_dictionary_item_code, '')) STORED,
    ADD COLUMN standard_name_key VARCHAR(100) GENERATED ALWAYS AS
        (CASE WHEN deleted=0 AND canonical_item_code IS NULL THEN TRIM(dictionary_item_name) ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_dictionary_standard_name (dictionary_code, standard_parent_key, standard_name_key);

UPDATE data_dictionary d SET revision=revision+1, updated_by='SYSTEM', updated_time=UTC_TIMESTAMP()
WHERE EXISTS (SELECT 1 FROM data_dictionary_item i WHERE i.dictionary_code=d.dictionary_code AND i.canonical_item_code IS NOT NULL);

CREATE TABLE data_dictionary_merge_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL COMMENT '操作租户审计上下文',
    dictionary_code VARCHAR(50) NOT NULL,
    source_item_code VARCHAR(50) NOT NULL,
    target_item_code VARCHAR(50) NOT NULL,
    source_revision INT NOT NULL,
    target_revision INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_by VARCHAR(50) NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_dictionary_merge_tenant (tenant_id, dictionary_code, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典合并审计';
