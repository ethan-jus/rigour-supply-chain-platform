-- 原有数据保持全局发布基线；租户覆盖从此迁移起独立维护，不猜测历史数据所属租户。
-- 历史别名按本租户有效视图解析，跨字典目标可来自 GLOBAL；原全局外键不能表达覆盖关系。
-- 标准目标存在性、启停、循环由锁内合并用例校验；远程已执行的 V30 保持原样。
ALTER TABLE data_dictionary_item DROP FOREIGN KEY fk_dictionary_canonical_item,
 DROP FOREIGN KEY fk_data_dictionary_item_dictionary,
 DROP INDEX uk_dictionary_standard_name;
ALTER TABLE data_dictionary ADD tenant_key VARCHAR(36) NOT NULL DEFAULT 'GLOBAL',
 ADD allow_new_items BOOLEAN NOT NULL DEFAULT FALSE,
 DROP INDEX uk_data_dictionary_code,
 ADD UNIQUE KEY uk_data_dictionary_tenant_code(tenant_key,dictionary_code);
ALTER TABLE data_dictionary_item ADD tenant_key VARCHAR(36) NOT NULL DEFAULT 'GLOBAL',
 ADD enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD locally_managed BOOLEAN NOT NULL DEFAULT FALSE,
 DROP INDEX uk_data_dictionary_item_code,
 ADD UNIQUE KEY uk_data_dictionary_item_tenant_code(tenant_key,dictionary_code,dictionary_item_code),
 ADD UNIQUE KEY uk_dictionary_tenant_standard_name(tenant_key,dictionary_code,standard_parent_key,standard_name_key),
 ADD CONSTRAINT fk_data_dictionary_item_tenant FOREIGN KEY(tenant_key,dictionary_code) REFERENCES data_dictionary(tenant_key,dictionary_code);
UPDATE data_dictionary SET allow_new_items=TRUE WHERE dictionary_code IN (
 'PRODUCT_UNIT','CUSTOMER_SOURCE','CUSTOMER_CATEGORY','STORE_ATTRIBUTE','STORE_BUSINESS_TYPE',
 'STORE_SCALE','STORE_TAG','CUSTOMER_RISK_LEVEL','CUSTOMER_COOP_LEVEL','CUSTOMER_INTENTION_LEVEL',
 'LEAD_SUBJECT_IDENTITY','ACTIVITY_FORM');
CREATE TABLE settings_operation_audit(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,
 actor_id VARCHAR(50) NOT NULL,module_code VARCHAR(32) NOT NULL,action_code VARCHAR(64) NOT NULL,
 object_code VARCHAR(100) NOT NULL,before_json JSON NULL,after_json JSON NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_settings_audit_tenant(tenant_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
