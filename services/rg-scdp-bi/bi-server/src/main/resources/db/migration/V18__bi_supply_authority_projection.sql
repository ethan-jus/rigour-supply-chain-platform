-- 只接收所属领域提供的权威归属，不在 BI 编辑，也不从当前客户猜历史订单。
CREATE TABLE bi_order_authority (
 tenant_id VARCHAR(64) NOT NULL, order_id BIGINT NOT NULL, employee_code VARCHAR(64), employee_name VARCHAR(128),
 department_id BIGINT, department_path JSON, region_code VARCHAR(64), region_path JSON,
 warehouse_id BIGINT, attribution_state VARCHAR(32) NOT NULL, source_version VARCHAR(200),
 projected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,order_id), KEY idx_bi_order_authority_employee(tenant_id,employee_code)
);
CREATE TABLE bi_customer_authority (
 tenant_id VARCHAR(64) NOT NULL, customer_id BIGINT NOT NULL, employee_code VARCHAR(64),
 region_code VARCHAR(64), region_path JSON, source_revision BIGINT NOT NULL,
 projected_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,customer_id)
);
CREATE TABLE bi_region_authority (
 tenant_id VARCHAR(64) NOT NULL, region_code VARCHAR(64) NOT NULL, region_path JSON NOT NULL,
 PRIMARY KEY(tenant_id,region_code)
);
CREATE TABLE bi_authority_checkpoint (
 tenant_id VARCHAR(64) NOT NULL, source_code VARCHAR(32) NOT NULL,
 source_version VARCHAR(200) NOT NULL, projected_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,source_code)
);
ALTER TABLE bi_inventory_operation_fact ADD COLUMN warehouse_id BIGINT NULL COMMENT '业务单据实际仓库，缺失时不推断';
