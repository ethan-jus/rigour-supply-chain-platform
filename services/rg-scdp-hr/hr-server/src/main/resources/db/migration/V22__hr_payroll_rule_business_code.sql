-- Stable business code per rule family; individual versions retain their own technical IDs.
ALTER TABLE hr_payroll_component_version
 ADD COLUMN rule_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL AFTER rule_id;
UPDATE hr_payroll_component_version v
JOIN (
 SELECT tenant_id,rule_id,CONCAT('PCR',DATE_FORMAT(first_created,'%Y%m%d'),LPAD(ROW_NUMBER() OVER(PARTITION BY tenant_id,DATE(first_created) ORDER BY rule_id),6,'0')) code
 FROM (SELECT tenant_id,rule_id,MIN(created_time) first_created FROM hr_payroll_component_version GROUP BY tenant_id,rule_id) families
) codes ON codes.tenant_id=v.tenant_id AND codes.rule_id=v.rule_id
SET v.rule_code=codes.code;
ALTER TABLE hr_payroll_component_version
 MODIFY COLUMN rule_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
 ADD UNIQUE KEY uk_payroll_component_code_version (tenant_id,rule_code,version);
