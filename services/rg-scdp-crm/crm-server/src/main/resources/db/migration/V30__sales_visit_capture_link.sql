-- 新采集入口关联；旧拜访不迁移，V29 成功历史不改写。
ALTER TABLE crm_visit
 ADD COLUMN capture_ref CHAR(36) NULL,
 ADD COLUMN employee_code_snapshot VARCHAR(50) NULL,
 ADD COLUMN review_source VARCHAR(16) NULL,
 ADD COLUMN capture_revision BIGINT NULL,
 MODIFY COLUMN validation_policy_version VARCHAR(64) NOT NULL,
 ADD UNIQUE KEY uk_visit_capture(tenant_id,capture_ref);
