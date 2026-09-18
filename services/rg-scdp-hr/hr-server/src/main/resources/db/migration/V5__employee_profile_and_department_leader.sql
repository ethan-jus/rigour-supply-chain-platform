-- 员工个人资料独立存储，列表及跨服务身份接口不读取。
CREATE TABLE hr_employee_profile (
 tenant_id VARCHAR(50) NOT NULL,
 employee_id BIGINT NOT NULL,
 id_number VARCHAR(18) NULL,
 contract_end_date DATE NULL,
 education VARCHAR(32) NULL,
 registered_address VARCHAR(300) NULL,
 household_type VARCHAR(32) NULL,
 residential_address VARCHAR(300) NULL,
 bank_account VARCHAR(32) NULL,
 bank_name VARCHAR(128) NULL,
 social_insurance VARCHAR(32) NULL,
 emergency_contact VARCHAR(128) NULL,
 emergency_phone VARCHAR(32) NULL,
 PRIMARY KEY (tenant_id, employee_id)
);
ALTER TABLE hr_employee ADD COLUMN created_by_name VARCHAR(128) NULL,
 ADD COLUMN updated_by_name VARCHAR(128) NULL;
ALTER TABLE hr_department ADD COLUMN leader_employee_code VARCHAR(50) NULL,
 ADD INDEX idx_department_leader (tenant_id, leader_employee_code);
-- 旧手填姓名仅在同租户唯一且在职的员工匹配时迁移为关联；重名不自动绑定。
UPDATE hr_department d JOIN (
 SELECT tenant_id,employee_name,MIN(employee_code) AS code FROM hr_employee
 WHERE deleted=0 AND employment_status='ACTIVE' GROUP BY tenant_id,employee_name HAVING COUNT(*)=1
) e ON e.tenant_id=d.tenant_id AND e.employee_name=d.leader_name
SET d.leader_employee_code=e.code WHERE d.deleted=0 AND d.leader_employee_code IS NULL;
