-- 来源发货事实先落库，不以依赖映射或物流单号作为发货单同步门槛。
ALTER TABLE order_sales_shipment
    ADD COLUMN source_warehouse_name VARCHAR(200) COLLATE utf8mb4_general_ci NULL,
    ADD COLUMN stock_out_time DATETIME(6) NULL,
    ADD COLUMN source_updated_at DATETIME(6) NULL,
    ADD COLUMN source_payload_json JSON NULL;
CREATE TABLE order_sales_shipment_sync_guard (
    tenant_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
ALTER TABLE order_transport_shipment_source MODIFY COLUMN sales_order_id BIGINT NULL;
