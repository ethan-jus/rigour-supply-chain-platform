CREATE TABLE erp_cost_entry_attachment (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, entry_id VARCHAR(36) NOT NULL,
 file_key VARCHAR(512) NOT NULL, file_name VARCHAR(200) NOT NULL, content_type VARCHAR(80) NOT NULL,
 file_size BIGINT NOT NULL, created_by VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY ix_cost_attachment_entry(tenant_id,entry_id),
 CONSTRAINT fk_erp_cost_attachment FOREIGN KEY(entry_id) REFERENCES erp_cost_entry(id),
 CONSTRAINT ck_erp_cost_attachment_size CHECK(file_size>0 AND file_size<=10485760)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
