-- CRM owns visit rules and durable source facts. Existing business rows and source IDs are preserved.
CREATE TABLE crm_visit_policy_version (
 tenant_id VARCHAR(64) NOT NULL,
 id CHAR(36) NOT NULL,
 version_no INT NOT NULL,
 policy_name VARCHAR(128) NOT NULL,
 status_code VARCHAR(24) NOT NULL,
 effective_from DATETIME(6) NULL,
 effective_to DATETIME(6) NULL,
 policy_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 created_by VARCHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,id),
 KEY ix_visit_policy_active(tenant_id,status_code,effective_from,effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE crm_visit_policy_scope (
 tenant_id VARCHAR(64) NOT NULL,
 policy_id CHAR(36) NOT NULL,
 scope_type VARCHAR(24) NOT NULL,
 scope_key VARCHAR(64) NOT NULL DEFAULT '',
 effective_from DATETIME(6) NOT NULL,
 effective_to DATETIME(6) NULL,
 PRIMARY KEY(tenant_id,policy_id,scope_type,scope_key,effective_from),
 KEY ix_visit_policy_scope(tenant_id,scope_type,scope_key,effective_from),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES crm_visit_policy_version(tenant_id,id),
 CHECK(scope_type IN ('ALL','EMPLOYEE','DEPARTMENT','AREA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE crm_visit
 ADD COLUMN policy_snapshot JSON NULL,
 ADD COLUMN capture_synced_at DATETIME(6) NULL;

CREATE TABLE crm_store_order_fact (
 tenant_id VARCHAR(64) NOT NULL,
 order_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 ownership_id BIGINT NOT NULL,
 salesperson_id BIGINT NULL,
 source_sequence BIGINT NOT NULL,
 effective_at DATETIME(6) NULL,
 valid_order TINYINT NOT NULL,
 pending_authorization TINYINT NOT NULL,
 observed_at DATETIME(6) NOT NULL,
 snapshot_json JSON NOT NULL,
 PRIMARY KEY(tenant_id,order_id),
 KEY ix_store_order_cycle(tenant_id,ownership_id,valid_order,effective_at),
 KEY ix_store_order_store(tenant_id,store_id,valid_order,effective_at),
 FOREIGN KEY(tenant_id,store_id) REFERENCES crm_store_master(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE crm_store_order_request (
 tenant_id VARCHAR(64) NOT NULL,
 order_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 ownership_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL,
 accepted_at DATETIME(6) NOT NULL,
 resolved_at DATETIME(6) NULL,
 PRIMARY KEY(tenant_id,order_id),
 UNIQUE KEY uk_store_order_request(tenant_id,request_id),
 KEY ix_store_order_request_pending(tenant_id,ownership_id,resolved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

INSERT INTO crm_store_order_request(tenant_id,order_id,store_id,ownership_id,request_id,accepted_at)
 SELECT tenant_id,CAST(object_id AS UNSIGNED),store_id,ownership_id,request_id,occurred_at
 FROM crm_store_event WHERE event_type='ORDER_AUTHORIZED';

-- Preserve already confirmed protection while Order replays its authoritative snapshots.
-- Sequence zero is always superseded by the source outbox's first delivery.
INSERT INTO crm_store_order_fact(tenant_id,order_id,store_id,ownership_id,salesperson_id,
 source_sequence,effective_at,valid_order,pending_authorization,observed_at,snapshot_json)
 SELECT tenant_id,last_valid_order_id,store_id,id,owner_employee_id,
 0,last_valid_order_at,1,0,CURRENT_TIMESTAMP(6),JSON_OBJECT('source','CRM_CONFIRMED_BASELINE')
 FROM crm_store_ownership WHERE last_valid_order_id IS NOT NULL AND last_valid_order_at IS NOT NULL;
