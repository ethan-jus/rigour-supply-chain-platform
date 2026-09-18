-- 客户当前主责是新订单归属的来源；本地接管后外部同步不得静默覆盖。
ALTER TABLE crm_customer
    ADD COLUMN owner_management_mode VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL',
    ADD COLUMN region_management_mode VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL',
    ADD COLUMN owner_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN region_revision BIGINT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_crm_customer_tenant_id(tenant_id,id),
    ADD CONSTRAINT ck_crm_owner_management CHECK(owner_management_mode IN ('EXTERNAL','LOCAL')),
    ADD CONSTRAINT ck_crm_region_management CHECK(region_management_mode IN ('EXTERNAL','LOCAL'));
CREATE TABLE crm_customer_responsibility_history (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, customer_id BIGINT NOT NULL,
    old_employee_code VARCHAR(50) NULL, new_employee_code VARCHAR(50) NULL,
    old_employee_name VARCHAR(128) NULL,new_employee_name VARCHAR(128) NULL,
    old_region_code VARCHAR(128) NULL,new_region_code VARCHAR(128) NULL,
    change_type VARCHAR(32) NOT NULL, reason VARCHAR(500) NOT NULL, source_system VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL, customer_revision BIGINT NOT NULL, effective_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id),KEY ix_crm_responsibility_history(tenant_id,customer_id,effective_at,id),
    CONSTRAINT fk_crm_responsibility_customer FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE crm_authority_state(tenant_id VARCHAR(64) NOT NULL,version BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE crm_authority_change(id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(64) NOT NULL,version BIGINT NOT NULL,object_type VARCHAR(32) NOT NULL,object_ref VARCHAR(128) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(id),UNIQUE KEY uk_crm_authority_version(tenant_id,version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE crm_responsibility_source_conflict(id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(64) NOT NULL,customer_id BIGINT NOT NULL,source_system VARCHAR(32) NOT NULL,proposed_employee_code VARCHAR(50) NULL,proposed_region_code VARCHAR(128) NULL,source_revision VARCHAR(128) NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(id),KEY ix_crm_responsibility_conflict(tenant_id,customer_id,status),CONSTRAINT fk_crm_responsibility_conflict_customer FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
