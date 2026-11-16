-- Legacy crm_contact points to binary-ID crm_party/crm_store. Keep it and its
-- source evidence intact; the unified store model uses explicit person links.
CREATE TABLE crm_contact_person (
 id BIGINT NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_contact_person_tenant(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE crm_store_contact (
 tenant_id VARCHAR(64) NOT NULL,
 store_id BIGINT NOT NULL,
 contact_id BIGINT NOT NULL,
 role_name VARCHAR(50) NOT NULL,
 is_primary BOOLEAN NOT NULL DEFAULT false,
 status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,store_id,contact_id),
 KEY ix_store_contact_person(tenant_id,contact_id),
 FOREIGN KEY(tenant_id,store_id) REFERENCES crm_store_master(tenant_id,id),
 FOREIGN KEY(tenant_id,contact_id) REFERENCES crm_contact_person(tenant_id,id),
 CHECK(status_code IN ('ACTIVE','LEFT')),
 CHECK(status_code='ACTIVE' OR is_primary=false)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE crm_contact_method (
 id BIGINT NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 contact_id BIGINT NOT NULL,
 method_type VARCHAR(24) NOT NULL,
 method_value VARCHAR(200) NOT NULL,
 status_code VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 verification_method VARCHAR(24) NULL,
 verified_at DATETIME(6) NULL,
 verified_by VARCHAR(64) NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 updated_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_contact_method_tenant(tenant_id,id),
 UNIQUE KEY uk_contact_method_value(tenant_id,contact_id,method_type,method_value),
 FOREIGN KEY(tenant_id,contact_id) REFERENCES crm_contact_person(tenant_id,id),
 CHECK(method_type IN ('PHONE','WECHAT_HINT')),
 CHECK(status_code IN ('PENDING','VERIFIED','INVALID')),
 CHECK(method_type='PHONE' OR status_code<>'VERIFIED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Audit also owns request idempotency. Phone/nickname are deliberately not
-- unique across people, and no change here mutates store protection.
CREATE TABLE crm_contact_change (
 id BIGINT NOT NULL PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL,
 store_id BIGINT NOT NULL,
 contact_id BIGINT NULL,
 operation VARCHAR(32) NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 command_hash CHAR(64) NOT NULL,
 actor_user_id VARCHAR(64) NOT NULL,
 actor_employee_id VARCHAR(64) NULL,
 reason VARCHAR(500) NOT NULL,
 before_data JSON NULL,
 after_data JSON NOT NULL,
 occurred_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_contact_change_request(tenant_id,request_id),
 KEY ix_contact_change_store(tenant_id,store_id,contact_id,occurred_at,id),
 FOREIGN KEY(tenant_id,store_id) REFERENCES crm_store_master(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE crm_visit_contact (
 tenant_id VARCHAR(64) NOT NULL,
 visit_id BIGINT NOT NULL,
 contact_id BIGINT NOT NULL,
 contact_snapshot JSON NOT NULL,
 recorded_at DATETIME(6) NOT NULL,
 recorded_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,visit_id,contact_id),
 FOREIGN KEY(tenant_id,visit_id) REFERENCES crm_visit(tenant_id,id),
 FOREIGN KEY(tenant_id,contact_id) REFERENCES crm_contact_person(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
