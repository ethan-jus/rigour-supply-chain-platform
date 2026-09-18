-- CRM V12：统一仍在使用的 CRM 业务/同步表本地审计字段命名和值类型。
-- source_created_at/source_updated_at 是订货宝来源证据字段，保留原名。

ALTER TABLE crm_customer_type RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_type RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_type RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_type MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_customer_type MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_customer_type ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_customer_type SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_customer_type DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_customer_area RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_area RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_area RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_area MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_customer_area MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_customer_area ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_customer_area SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_customer_area DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_party RENAME COLUMN version TO revision;
ALTER TABLE crm_party RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_party RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_party MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_party MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_party ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_party SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_party DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_party_role RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_party_role RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_party_role ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER effective_to;
ALTER TABLE crm_party_role ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_party_role ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_party_role ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_customer_profile RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_profile RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_profile RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_profile ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_customer_profile ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_customer_profile ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_customer_policy RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_policy RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_policy RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_policy MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_customer_policy MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_customer_policy ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_store RENAME COLUMN version TO revision;
ALTER TABLE crm_store RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_store RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_store MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_store MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_store ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_store SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_store DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_contact RENAME COLUMN version TO revision;
ALTER TABLE crm_contact RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_contact RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_contact MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_contact MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_contact ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_contact SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_contact DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_address RENAME COLUMN version TO revision;
ALTER TABLE crm_address RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_address RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_address MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_address MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_address ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_address SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_address DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_sales_assignment RENAME COLUMN version TO revision;
ALTER TABLE crm_sales_assignment RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_sales_assignment RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_sales_assignment MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_sales_assignment MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_sales_assignment ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_sync_run RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_sync_run RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_sync_run MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_sync_run ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER finished_at;
ALTER TABLE crm_sync_run ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_sync_run ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_sync_checkpoint RENAME COLUMN version TO revision;
ALTER TABLE crm_sync_checkpoint RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_sync_checkpoint RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_sync_checkpoint ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_sync_checkpoint ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_sync_checkpoint ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_sync_lock ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER expires_at;
ALTER TABLE crm_sync_lock ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_sync_lock ADD COLUMN created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间' AFTER created_by;
ALTER TABLE crm_sync_lock ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_sync_lock ADD COLUMN updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间' AFTER updated_by;
ALTER TABLE crm_sync_lock ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_source_binding RENAME COLUMN version TO revision;
ALTER TABLE crm_source_binding RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_source_binding RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_source_binding ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_source_binding ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_source_binding ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_source_identity_alias RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_source_identity_alias RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_source_identity_alias ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER last_seen_at;
ALTER TABLE crm_source_identity_alias ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_source_identity_alias ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_source_identity_alias ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
