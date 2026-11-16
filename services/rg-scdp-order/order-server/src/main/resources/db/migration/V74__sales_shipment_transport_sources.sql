-- 原始发货身份稳定去重；来源变化与已确认运输快照分开保存。
CREATE TABLE order_transport_shipment_source (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    platform_code VARCHAR(32) NOT NULL DEFAULT 'DHB',
    connector_id CHAR(36) NOT NULL,
    source_shipment_id VARCHAR(128) NOT NULL,
    source_shipment_no VARCHAR(128) NOT NULL,
    sales_order_id BIGINT NOT NULL,
    cost_id CHAR(36) NULL,
    latest_json LONGTEXT NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    issue VARCHAR(500) NULL,
    difference_pending TINYINT NOT NULL DEFAULT 0,
    created_by VARCHAR(64) NOT NULL,
    updated_by VARCHAR(64) NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transport_shipment_source (tenant_id, platform_code, connector_id, source_shipment_id),
    KEY idx_transport_shipment_cost (tenant_id, cost_id),
    KEY idx_transport_shipment_order (tenant_id, sales_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE order_transport_shipment_history (
    id CHAR(36) NOT NULL,
    tenant_id CHAR(36) NOT NULL,
    platform_code VARCHAR(32) NOT NULL DEFAULT 'DHB',
    source_id CHAR(36) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    source_json LONGTEXT NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_transport_shipment_version (tenant_id, platform_code, source_id, fingerprint)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
