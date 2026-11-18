CREATE TABLE crm_wecom_identity (
 id BIGINT NOT NULL, tenant_id VARCHAR(64) NOT NULL, identity_key CHAR(64) NOT NULL,
 display_name VARCHAR(100) NOT NULL, contact_id BIGINT NULL, created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_wecom_identity(tenant_id,identity_key), UNIQUE KEY uk_wecom_identity_tenant(tenant_id,id),
 KEY idx_wecom_contact(tenant_id,contact_id),
 FOREIGN KEY(tenant_id,contact_id) REFERENCES crm_contact_person(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_wecom_sync (
 tenant_id VARCHAR(64) NOT NULL, scope_key CHAR(64) NOT NULL, employee_id VARCHAR(64) NOT NULL,
 sequence_no BIGINT NOT NULL, observed_at DATETIME(6) NOT NULL, payload_hash CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,scope_key,employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_wecom_relation (
 tenant_id VARCHAR(64) NOT NULL, identity_id BIGINT NOT NULL, scope_key CHAR(64) NOT NULL,
 employee_id VARCHAR(64) NOT NULL, employee_name VARCHAR(100) NOT NULL,
 added_at DATETIME(6) NOT NULL, active BOOLEAN NOT NULL, acquisition BOOLEAN NOT NULL,
 observed_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,identity_id,employee_id,added_at),
 KEY idx_wecom_employee(tenant_id,employee_id,active,identity_id),
 FOREIGN KEY(tenant_id,identity_id) REFERENCES crm_wecom_identity(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_wecom_link_change (
 tenant_id VARCHAR(64) NOT NULL, request_id VARCHAR(64) NOT NULL, identity_id BIGINT NOT NULL,
 contact_id BIGINT NOT NULL, store_id BIGINT NOT NULL, actor_user_id VARCHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL, command_hash CHAR(64) NOT NULL, occurred_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,request_id), KEY idx_wecom_link_history(tenant_id,identity_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
