-- 内部订单模型：订货宝只是一个来源，订单中心拥有后续订单流程主权。
-- V1 的 dhb_* 表保留用于兼容既有一期数据；新同步写入本模型，后续查询逐步迁移到 order_* 表。

CREATE TABLE order_order (
    id                       CHAR(36)      NOT NULL,
    tenant_id                VARCHAR(64)   NOT NULL,
    order_no                 VARCHAR(80)   NOT NULL,
    source_system            VARCHAR(32)   NOT NULL,
    source_order_no          VARCHAR(80)   NOT NULL,
    internal_status          VARCHAR(40)   NOT NULL,
    source_status            VARCHAR(40)   NULL,
    payment_status           VARCHAR(40)   NULL,
    order_type               VARCHAR(40)   NULL,
    total_amount             DECIMAL(18,4) NULL,
    ordered_at               DATETIME(6)   NULL,
    source_updated_at        DATETIME(6)   NULL,
    source_update_time       VARCHAR(32)   NULL,
    delivery_date            VARCHAR(32)   NULL,
    remark                   VARCHAR(1000) NULL,
    source_customer_no       VARCHAR(80)   NULL,
    source_customer_guid     VARCHAR(80)   NULL,
    customer_name            VARCHAR(160)  NULL,
    receiver_name            VARCHAR(80)   NULL,
    receiver_company         VARCHAR(200)  NULL,
    receiver_phone           VARCHAR(64)   NULL,
    receiver_address         VARCHAR(500)  NULL,
    province                 VARCHAR(80)   NULL,
    city                     VARCHAR(80)   NULL,
    district                 VARCHAR(80)   NULL,
    source_api_status        VARCHAR(8)    NULL,
    source_exception_status  VARCHAR(8)    NULL,
    source_send_type         VARCHAR(80)   NULL,
    source_last_order_at     VARCHAR(32)   NULL,
    source_device            VARCHAR(40)   NULL,
    source_admin_order       VARCHAR(8)    NULL,
    split_type               VARCHAR(32)   NULL,
    split_type_name          VARCHAR(80)   NULL,
    source_payload_hash      CHAR(64)      NOT NULL,
    detail_synced_at         DATETIME(6)   NULL,
    imported_at              DATETIME(6)   NOT NULL,
    synced_at                DATETIME(6)   NOT NULL,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               DATETIME(6)   NOT NULL,
    updated_at               DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_source (tenant_id, source_system, source_order_no),
    UNIQUE KEY uk_order_no (tenant_id, order_no),
    KEY idx_order_status (tenant_id, internal_status),
    KEY idx_order_source_status (tenant_id, source_system, source_status),
    KEY idx_order_ordered_at (tenant_id, ordered_at),
    KEY idx_order_source_updated (tenant_id, source_updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台内部订单主模型';

CREATE TABLE order_order_line (
    id                    CHAR(36)      NOT NULL,
    order_id              CHAR(36)      NOT NULL,
    source_line_id        VARCHAR(100)  NOT NULL,
    source_product_guid   VARCHAR(100)  NULL,
    sku_no                VARCHAR(100)  NULL,
    source_options_goods_no VARCHAR(100) NULL,
    source_barcode        VARCHAR(160)  NULL,
    product_name          VARCHAR(200)  NULL,
    product_code          VARCHAR(100)  NULL,
    specification_first   VARCHAR(100)  NULL,
    specification_second  VARCHAR(100)  NULL,
    specification_name    VARCHAR(200)  NULL,
    unit_price            DECIMAL(18,4) NULL,
    quantity              DECIMAL(18,4) NULL,
    line_amount           DECIMAL(18,4) NULL,
    unit                  VARCHAR(40)   NULL,
    remark                VARCHAR(1000) NULL,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_order_line_source (order_id, source_line_id),
    KEY idx_order_line_sku (sku_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台内部订单明细';

CREATE TABLE order_order_shipment (
    id                    CHAR(36)     NOT NULL,
    order_id              CHAR(36)     NOT NULL,
    source_shipment_no    VARCHAR(100) NOT NULL,
    status                VARCHAR(40)  NULL,
    shipment_date         VARCHAR(32)  NULL,
    stock_up_time         VARCHAR(32)  NULL,
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_shipment_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_order_shipment_source (order_id, source_shipment_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台内部订单发货信息';

CREATE TABLE order_source_record (
    id               CHAR(36)     NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL,
    order_id         CHAR(36)     NOT NULL,
    source_system     VARCHAR(32)  NOT NULL,
    source_order_no   VARCHAR(80)  NOT NULL,
    payload_type      VARCHAR(16)  NOT NULL,
    payload_json      JSON         NOT NULL,
    payload_hash      CHAR(64)     NOT NULL,
    received_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_order_source_payload_type CHECK (payload_type IN ('LIST', 'DETAIL')),
    CONSTRAINT fk_order_source_record_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_order_source_record_hash (
        tenant_id, source_system, source_order_no, payload_type, payload_hash
    ),
    KEY idx_order_source_record_order (tenant_id, order_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝原始报文不可变记录';

CREATE TABLE order_sync_run (
    id                CHAR(36)     NOT NULL,
    tenant_id         VARCHAR(64)  NOT NULL,
    source_system     VARCHAR(32)  NOT NULL,
    function_name     VARCHAR(64)  NOT NULL,
    request_json      JSON         NOT NULL,
    provider_status   VARCHAR(16)  NULL,
    provider_message  VARCHAR(1000) NULL,
    provider_total    INT          NULL,
    fetched_count     INT          NOT NULL DEFAULT 0,
    accepted_count    INT          NOT NULL DEFAULT 0,
    duplicate_count   INT          NOT NULL DEFAULT 0,
    rejected_count    INT          NOT NULL DEFAULT 0,
    run_status        VARCHAR(16)  NOT NULL,
    error_message     VARCHAR(1000) NULL,
    started_at        DATETIME(6)  NOT NULL,
    finished_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_order_sync_run_status CHECK (run_status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    KEY idx_order_sync_run_tenant_time (tenant_id, source_system, started_at),
    KEY idx_order_sync_run_status (tenant_id, run_status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝订单导入批次';

CREATE TABLE order_outbox_event (
    id              CHAR(36)      NOT NULL,
    tenant_id       VARCHAR(64)   NOT NULL,
    aggregate_type  VARCHAR(64)   NOT NULL,
    aggregate_id    VARCHAR(80)   NOT NULL,
    event_type      VARCHAR(128)  NOT NULL,
    event_version   INT           NOT NULL,
    event_key       VARCHAR(255)  NOT NULL,
    payload_json    JSON          NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    attempts        INT           NOT NULL DEFAULT 0,
    available_at    DATETIME(6)   NOT NULL,
    published_at    DATETIME(6)   NULL,
    last_error      VARCHAR(2000) NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_order_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD')),
    UNIQUE KEY uk_order_outbox_event_key (tenant_id, event_key),
    KEY idx_order_outbox_dispatch (tenant_id, status, available_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单领域事件Transactional Outbox';

-- 将V1已落库的订货宝摘要迁移到内部订单模型。
-- 历史数据不补发Outbox事件，避免升级时重复触发ERP/库存/BI；后续来源变更才产生事件。
INSERT INTO order_order (
    id, tenant_id, order_no, source_system, source_order_no, internal_status, source_status,
    payment_status, order_type, total_amount, ordered_at, source_updated_at, source_update_time,
    delivery_date, remark, source_customer_no, source_customer_guid, customer_name, receiver_name,
    receiver_company, receiver_phone, receiver_address, province, city, district, source_api_status,
    source_exception_status, source_send_type, source_last_order_at, source_device, source_admin_order,
    split_type, split_type_name, source_payload_hash, detail_synced_at, imported_at, synced_at,
    version, created_at, updated_at
)
SELECT
    UUID(), d.tenant_id, d.order_sn, 'DINGHUOBAO', d.order_sn,
    CASE LOWER(COALESCE(d.order_status, ''))
        WHEN 'pricing' THEN 'PENDING_CONFIRMATION'
        WHEN 'pending' THEN 'PENDING_CONFIRMATION'
        WHEN 'stock_up' THEN 'ALLOCATING'
        WHEN 'shipped' THEN 'SHIPPED'
        WHEN 'received' THEN 'COMPLETED'
        WHEN 'finished' THEN 'COMPLETED'
        WHEN 'forcedone' THEN 'COMPLETED'
        WHEN 'cancelled' THEN 'CANCELLED'
        ELSE 'EXCEPTION'
    END,
    d.order_status, d.pay_status, d.order_type, d.order_total, d.order_date, d.order_update_date,
    d.order_update_time, d.delivery_date, d.order_remark, d.client_no, d.client_guid, d.client_name,
    d.receive_name, d.receive_company, d.receive_phone, d.receive_address, d.province, d.city, d.district,
    d.order_api, d.order_exception, d.order_send_type, d.last_order_at, d.source_device, d.is_admin_order,
    d.split_type, d.split_type_name, SHA2(CONCAT(CAST(d.raw_list_json AS CHAR), '\n'), 256),
    d.detail_synced_at, d.created_at, d.synced_at, 0, d.created_at, d.updated_at
FROM dhb_order d
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO order_order_line (
    id, order_id, source_line_id, source_product_guid, sku_no, source_options_goods_no, source_barcode,
    product_name, product_code, specification_first, specification_second, specification_name,
    unit_price, quantity, line_amount, unit, remark, created_at, updated_at
)
SELECT UUID(), o.id, l.line_id, l.product_guid, l.sku_no, l.options_goods_num, l.options_barcode,
       l.product_name, l.coding, l.multi_first, l.multi_second, l.multi_name,
       l.unit_price, l.quantity, NULL, l.unit, l.remark, l.created_at, l.updated_at
FROM dhb_order_line l
JOIN dhb_order d ON d.id = l.order_id
JOIN order_order o ON o.tenant_id = d.tenant_id
                  AND o.source_system = 'DINGHUOBAO'
                  AND o.source_order_no = d.order_sn
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO order_order_shipment (
    id, order_id, source_shipment_no, status, shipment_date, stock_up_time, created_at, updated_at
)
SELECT UUID(), o.id, s.shipment_no, s.status, s.shipment_date, s.stock_up_time, s.created_at, s.updated_at
FROM dhb_order_shipment s
JOIN dhb_order d ON d.id = s.order_id
JOIN order_order o ON o.tenant_id = d.tenant_id
                  AND o.source_system = 'DINGHUOBAO'
                  AND o.source_order_no = d.order_sn
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO order_source_record (
    id, tenant_id, order_id, source_system, source_order_no, payload_type, payload_json, payload_hash, received_at
)
SELECT UUID(), o.tenant_id, o.id, o.source_system, o.source_order_no, 'LIST', d.raw_list_json,
       o.source_payload_hash, d.synced_at
FROM dhb_order d
JOIN order_order o ON o.tenant_id = d.tenant_id
                  AND o.source_system = 'DINGHUOBAO'
                  AND o.source_order_no = d.order_sn
ON DUPLICATE KEY UPDATE received_at = VALUES(received_at);
