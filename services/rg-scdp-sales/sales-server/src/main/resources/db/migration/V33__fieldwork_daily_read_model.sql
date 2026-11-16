-- Derived technical measurements; original points, CRM visits and HR employees remain authoritative.
CREATE TABLE sales_fieldwork_day_metrics (
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    source_version BIGINT NOT NULL,
    business_date DATE NOT NULL,
    distance_meters INT NULL,
    tracked_minutes INT NOT NULL,
    gap_count INT NOT NULL,
    rejected_point_count INT NOT NULL,
    calculated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, work_day_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
