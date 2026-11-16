-- Durable cache generation; changes share the owning service's business transaction.
CREATE TABLE query_cache_version (
    tenant_id VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
