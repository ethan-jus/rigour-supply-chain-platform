ALTER TABLE hr_payroll_component_version
 ADD COLUMN revision INT NOT NULL DEFAULT 1,
 ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;

ALTER TABLE hr_attendance_note ADD COLUMN exceptions_json JSON NULL;

CREATE TABLE hr_payroll_component_operation (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
 request_id VARCHAR(80) NOT NULL, rule_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, action_code VARCHAR(20) NOT NULL,
 effective_month CHAR(7) NULL, state_revision INT NULL,
 response_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL,
 actor_id VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_component_operation_request(tenant_id,request_id),
 UNIQUE KEY uk_component_state_revision(tenant_id,rule_id,state_revision),
 KEY ix_component_operation_rule(tenant_id,rule_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_payroll_component_review (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL,
 operation_id BIGINT NOT NULL, rule_id VARCHAR(36) NOT NULL,
 rule_name VARCHAR(120) NOT NULL, action_code VARCHAR(20) NOT NULL,
 subject VARCHAR(20) NOT NULL, subject_id VARCHAR(100) NOT NULL,
 employee_code VARCHAR(50) NOT NULL, review_month CHAR(7) NOT NULL,
 reason VARCHAR(1000) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 created_time DATETIME(6) NOT NULL, resolved_by VARCHAR(50) NULL,
 resolved_time DATETIME(6) NULL, resolution_reason VARCHAR(1000) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_component_review_subject(tenant_id,operation_id,subject,subject_id),
 KEY ix_component_review_month(tenant_id,subject,review_month,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
