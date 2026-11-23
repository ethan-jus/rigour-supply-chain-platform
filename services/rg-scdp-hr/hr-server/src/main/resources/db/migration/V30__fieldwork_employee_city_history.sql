CREATE TABLE hr_employee_city_history (
    tenant_id VARCHAR(64) NOT NULL,
    employee_code VARCHAR(50) NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    city_name VARCHAR(80) NULL,
    PRIMARY KEY (tenant_id,employee_code,effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- The current row establishes the earliest provable city, not a retroactive assignment.
INSERT INTO hr_employee_city_history (tenant_id,employee_code,effective_at,city_name)
SELECT tenant_id,employee_code,IF(revision=1,created_time,updated_time),city_name FROM hr_employee;

CREATE TABLE hr_department_parent_history (
    tenant_id VARCHAR(64) NOT NULL,
    department_id BIGINT NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    parent_id BIGINT NULL,
    department_name VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id,department_id,effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO hr_department_parent_history(tenant_id,department_id,effective_at,parent_id,department_name)
SELECT tenant_id,id,IF(revision=1,created_time,updated_time),parent_id,department_name FROM hr_department;
