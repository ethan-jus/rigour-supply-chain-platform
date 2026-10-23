-- National holiday reference data is public GLOBAL configuration, never tenant business records.
CREATE TABLE settings_statutory_calendar_year (
 id VARCHAR(32) NOT NULL, tenant_id VARCHAR(64) NOT NULL, calendar_year INT NOT NULL,
 status VARCHAR(30) NOT NULL, content_json JSON NULL, content_version VARCHAR(64) NULL,
 fetched_at DATETIME(6) NULL, attempted_at DATETIME(6) NULL,
 lease_token VARCHAR(36) NULL, lease_until DATETIME(6) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_statutory_calendar_year(tenant_id,calendar_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE settings_statutory_calendar_history (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, calendar_year INT NOT NULL,
 content_json JSON NOT NULL, content_version VARCHAR(64) NOT NULL, fetched_at DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY ix_statutory_calendar_history(tenant_id,calendar_year,fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
