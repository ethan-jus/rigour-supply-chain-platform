-- HR 部门为供应链组织主数据；不按旧名称自动生成或猜测映射。
CREATE TABLE hr_organization_state (
    tenant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hr_department (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
    department_code VARCHAR(50) NOT NULL, parent_id BIGINT NULL,
    department_name VARCHAR(128) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
    status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revision INT NOT NULL DEFAULT 1,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY(id), UNIQUE KEY uk_hr_department_identity(tenant_id,id),
    UNIQUE KEY uk_hr_department_code(tenant_id,department_code),
    KEY ix_hr_department_parent(tenant_id,parent_id,deleted,sort_order),
    CONSTRAINT fk_hr_department_parent FOREIGN KEY(tenant_id,parent_id) REFERENCES hr_department(tenant_id,id),
    CONSTRAINT ck_hr_department_status CHECK(status_code IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hr_department_closure (
    tenant_id VARCHAR(64) NOT NULL, ancestor_id BIGINT NOT NULL, descendant_id BIGINT NOT NULL, depth INT NOT NULL,
    PRIMARY KEY(tenant_id,ancestor_id,descendant_id),
    KEY ix_hr_department_ancestors(tenant_id,descendant_id,depth),
    CONSTRAINT fk_hr_closure_ancestor FOREIGN KEY(tenant_id,ancestor_id) REFERENCES hr_department(tenant_id,id),
    CONSTRAINT fk_hr_closure_descendant FOREIGN KEY(tenant_id,descendant_id) REFERENCES hr_department(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE hr_employee ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN department_id BIGINT NULL AFTER department_name_snapshot,
    ADD INDEX ix_hr_employee_department(tenant_id,department_id,employment_status,deleted),
    ADD CONSTRAINT fk_hr_employee_department FOREIGN KEY(tenant_id,department_id) REFERENCES hr_department(tenant_id,id);

CREATE TABLE hr_employee_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, employee_code VARCHAR(50) NOT NULL,
    department_id BIGINT NOT NULL, department_name_snapshot VARCHAR(128) NOT NULL,
    position_code VARCHAR(50) NOT NULL, position_name_snapshot VARCHAR(120) NOT NULL,
    effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6) NULL,
    actor_id VARCHAR(50) NOT NULL, revision INT NOT NULL DEFAULT 1,
    current_employee_code VARCHAR(50) GENERATED ALWAYS AS(CASE WHEN effective_to IS NULL THEN employee_code ELSE NULL END) STORED,
    PRIMARY KEY(id), UNIQUE KEY uk_hr_current_assignment(tenant_id,current_employee_code),
    KEY ix_hr_assignment_history(tenant_id,employee_code,effective_from),
    CONSTRAINT fk_hr_assignment_employee FOREIGN KEY(tenant_id,employee_code) REFERENCES hr_employee(tenant_id,employee_code),
    CONSTRAINT fk_hr_assignment_department FOREIGN KEY(tenant_id,department_id) REFERENCES hr_department(tenant_id,id),
    CONSTRAINT fk_hr_assignment_position FOREIGN KEY(tenant_id,position_code) REFERENCES hr_position(tenant_id,position_code),
    CONSTRAINT ck_hr_assignment_period CHECK(effective_to IS NULL OR effective_to>=effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE hr_organization_change (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL, object_ref VARCHAR(128) NOT NULL,
    actor_id VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id), UNIQUE KEY uk_hr_change_version(tenant_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
