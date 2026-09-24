ALTER TABLE hr_employee_source_binding
 ADD source_employee_name VARCHAR(128) NULL,
 ADD source_mobile VARCHAR(32) NULL,
 ADD review_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD review_reason VARCHAR(300) NULL,
 ADD review_version BIGINT NOT NULL DEFAULT 1;

-- Old roster links saved the employee name in source_account_name. Recover only
-- from the stored DHB payload whose staff ID agrees with this exact binding.
UPDATE hr_employee_source_binding
SET source_account_name=JSON_UNQUOTE(JSON_EXTRACT(source_payload_json,'$.employee.accountName'))
WHERE source_system='DINGHUOBAO' AND deleted=0
 AND JSON_UNQUOTE(JSON_EXTRACT(source_payload_json,'$.employee.staffId'))=source_employee_id
 AND JSON_TYPE(JSON_EXTRACT(source_payload_json,'$.employee.accountName'))='STRING'
 AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(source_payload_json,'$.employee.accountName')))<>'';

CREATE TABLE hr_dhb_binding_review_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL, binding_id BIGINT NOT NULL,
 before_employee_code VARCHAR(50) NOT NULL, after_employee_code VARCHAR(50) NOT NULL,
 source_employee_name VARCHAR(128) NULL, source_mobile VARCHAR(32) NULL,
 source_account_name VARCHAR(128) NULL, review_reason VARCHAR(300) NULL,
 actor_id VARCHAR(64) NOT NULL, reviewed_at DATETIME(6) NOT NULL,
 review_version BIGINT NOT NULL,
 UNIQUE KEY uk_binding_review (tenant_id,binding_id,review_version)
);
