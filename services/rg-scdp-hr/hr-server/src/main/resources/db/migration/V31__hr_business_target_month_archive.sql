CREATE TABLE hr_business_target_month_archive (
    tenant_id VARCHAR(64) NOT NULL,
    target_month DATE NOT NULL,
    snapshot_json JSON NOT NULL,
    archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, target_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE hr_department_parent_history
    ADD COLUMN department_code VARCHAR(50) NULL,
    ADD COLUMN status_code VARCHAR(16) NULL;

UPDATE hr_department_parent_history h
JOIN hr_department d ON d.tenant_id COLLATE utf8mb4_general_ci=h.tenant_id AND d.id=h.department_id
   SET h.department_code=d.department_code,
       h.status_code=CASE WHEN d.updated_time=h.effective_at THEN d.status_code ELSE NULL END;
