-- 订单中心V15：保留订货宝已删来源数据，并以显式存在性状态支持业务识别。
-- UNKNOWN表示历史数据尚未经过升级后的权威全量核对；不伪造PRESENT证据。

ALTER TABLE order_order
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN/PRESENT/SOURCE_ABSENT；不改变internal_status' AFTER source_payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL
        COMMENT '首次成功FULL/REPAIR未见时间，UTC' AFTER source_presence,
    ADD COLUMN last_seen_run_id CHAR(36) NULL
        COMMENT '最近成功见到该来源对象的order_sync_run.id' AFTER source_absent_at,
    ADD CONSTRAINT ck_order_source_presence
        CHECK (source_presence IN ('UNKNOWN', 'PRESENT', 'SOURCE_ABSENT')),
    ADD KEY idx_order_source_presence
        (tenant_id, source_system, source_presence, source_absent_at);

ALTER TABLE order_dhb_shipment
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN/PRESENT/SOURCE_ABSENT' AFTER payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL
        COMMENT '首次成功FULL/REPAIR未见时间，UTC' AFTER source_presence,
    ADD COLUMN last_seen_run_id CHAR(36) NULL
        COMMENT '最近成功见到该来源对象的order_sync_run.id' AFTER source_absent_at,
    ADD CONSTRAINT ck_order_dhb_shipment_presence
        CHECK (source_presence IN ('UNKNOWN', 'PRESENT', 'SOURCE_ABSENT')),
    ADD KEY idx_order_dhb_shipment_presence
        (tenant_id, source_system, source_presence, source_absent_at);

ALTER TABLE order_dhb_shipment_logistics
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN/PRESENT/SOURCE_ABSENT' AFTER payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL
        COMMENT '首次成功FULL/REPAIR未见时间，UTC' AFTER source_presence,
    ADD COLUMN last_seen_run_id CHAR(36) NULL
        COMMENT '最近成功见到该来源对象的order_sync_run.id' AFTER source_absent_at,
    ADD CONSTRAINT ck_order_dhb_logistics_presence
        CHECK (source_presence IN ('UNKNOWN', 'PRESENT', 'SOURCE_ABSENT')),
    ADD KEY idx_order_dhb_logistics_presence
        (tenant_id, source_system, source_presence, source_absent_at);

ALTER TABLE order_dhb_return
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN/PRESENT/SOURCE_ABSENT' AFTER payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL
        COMMENT '首次成功FULL/REPAIR未见时间，UTC' AFTER source_presence,
    ADD COLUMN last_seen_run_id CHAR(36) NULL
        COMMENT '最近成功见到该来源对象的order_sync_run.id' AFTER source_absent_at,
    ADD CONSTRAINT ck_order_dhb_return_presence
        CHECK (source_presence IN ('UNKNOWN', 'PRESENT', 'SOURCE_ABSENT')),
    ADD KEY idx_order_dhb_return_presence
        (tenant_id, source_system, source_presence, source_absent_at);

ALTER TABLE order_dhb_financial_document
    ADD COLUMN source_presence VARCHAR(24) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN/PRESENT/SOURCE_ABSENT' AFTER payload_hash,
    ADD COLUMN source_absent_at DATETIME(6) NULL
        COMMENT '首次成功FULL/REPAIR未见时间，UTC' AFTER source_presence,
    ADD COLUMN last_seen_run_id CHAR(36) NULL
        COMMENT '最近成功见到该来源对象的order_sync_run.id' AFTER source_absent_at,
    ADD CONSTRAINT ck_order_dhb_financial_presence
        CHECK (source_presence IN ('UNKNOWN', 'PRESENT', 'SOURCE_ABSENT')),
    ADD KEY idx_order_dhb_financial_presence
        (tenant_id, source_system, document_type, source_presence, source_absent_at);
