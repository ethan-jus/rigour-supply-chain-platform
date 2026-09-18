CREATE TABLE integration_dhb_object_checkpoint (
 tenant_id CHAR(36) NOT NULL,connector_id CHAR(36) NOT NULL,object_type VARCHAR(40) NOT NULL,
 successful_to DATETIME(6) NOT NULL,last_run_id CHAR(36) NOT NULL,updated_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,object_type)
);
