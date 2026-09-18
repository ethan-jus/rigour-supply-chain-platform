-- 历史来源按门店组成关联组；不复制飞书业务订单。所有约束含租户。
CREATE TABLE order_sync_source (
 tenant_id VARCHAR(36) NOT NULL, connector_id VARCHAR(36) NOT NULL, source_no VARCHAR(128) NOT NULL,
 customer_id BIGINT NOT NULL, source_date DATETIME(6), amount DECIMAL(20,2) NOT NULL,
 payload LONGTEXT NOT NULL, checksum VARCHAR(128) NOT NULL, state VARCHAR(40) NOT NULL,
 group_id VARCHAR(36), revision INT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,connector_id,source_no), KEY idx_sync_source_customer(tenant_id,customer_id,state)
);
CREATE TABLE order_history_group (
 tenant_id VARCHAR(36) NOT NULL,id VARCHAR(36) NOT NULL,customer_id BIGINT NOT NULL,
 evidence VARCHAR(1000) NOT NULL,actor_id VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,id)
);
CREATE TABLE order_history_member (
 tenant_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,group_id VARCHAR(36) NOT NULL,
 baseline_at DATETIME(6) NOT NULL,opening_paid DECIMAL(20,2) NOT NULL,
 PRIMARY KEY(tenant_id,order_id), KEY idx_history_member_group(tenant_id,group_id)
);
CREATE TABLE order_sync_receipt (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 source_order_no VARCHAR(128),customer_id BIGINT,group_id VARCHAR(36),amount DECIMAL(20,2) NOT NULL,
 occurred_at DATETIME(6),source_updated_at DATETIME(6),source_status VARCHAR(40),checksum VARCHAR(128) NOT NULL,
 state VARCHAR(40) NOT NULL,revision INT NOT NULL DEFAULT 0,
 pending_payload LONGTEXT, pending_checksum VARCHAR(128),
 employee_code VARCHAR(50),employee_name VARCHAR(100),owner_evidence VARCHAR(1000),owner_actor VARCHAR(64),
 PRIMARY KEY(tenant_id,connector_id,receipt_no),KEY idx_sync_receipt_customer(tenant_id,customer_id,occurred_at)
);
CREATE TABLE order_sync_allocation (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 order_id BIGINT NOT NULL,payment_id BIGINT,amount DECIMAL(20,2) NOT NULL,evidence VARCHAR(1000) NOT NULL,
 actor_id VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,receipt_no,order_id)
);
CREATE TABLE order_sync_receipt_revision (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 revision INT NOT NULL,payload LONGTEXT NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,receipt_no,revision)
);

ALTER TABLE order_sync_source ADD classification_evidence VARCHAR(1000);
ALTER TABLE order_sync_source ADD classification_actor VARCHAR(64);

CREATE TABLE order_sync_product_allocation (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 order_id BIGINT NOT NULL,line_id BIGINT NOT NULL,amount DECIMAL(20,2) NOT NULL,
 evidence VARCHAR(1000) NOT NULL,actor_id VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,receipt_no,line_id)
);
