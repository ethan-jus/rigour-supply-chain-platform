-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__dhb_order_snapshot.sql
-- 订货宝一期只读同步：规范化订单主表/明细表 + 原始报文兜底。
-- 该Schema由order-center独占，第三方接口凭据不落库。
CREATE TABLE dhb_order (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(64)  NOT NULL,
    order_sn            VARCHAR(80)  NOT NULL,
    delivery_date       VARCHAR(32)  NULL,
    order_remark        VARCHAR(1000) NULL,
    order_total         DECIMAL(18,4) NULL,
    order_status        VARCHAR(40)  NULL,
    order_date          DATETIME(6) NULL,
    order_update_date   DATETIME(6) NULL,
    order_update_time   VARCHAR(32) NULL,
    order_type          VARCHAR(40) NULL,
    order_api           VARCHAR(8)  NULL,
    order_exception     VARCHAR(8)  NULL,
    order_send_type     VARCHAR(80) NULL,
    last_order_at       VARCHAR(32) NULL,
    client_no           VARCHAR(80) NULL,
    client_guid         VARCHAR(80) NULL,
    source_device       VARCHAR(40) NULL,
    is_admin_order      VARCHAR(8)  NULL,
    pay_status          VARCHAR(40) NULL,
    client_name         VARCHAR(160) NULL,
    receive_name        VARCHAR(80) NULL,
    receive_company     VARCHAR(200) NULL,
    receive_phone       VARCHAR(64) NULL,
    receive_address     VARCHAR(500) NULL,
    province            VARCHAR(80) NULL,
    city                VARCHAR(80) NULL,
    district            VARCHAR(80) NULL,
    split_type          VARCHAR(32) NULL,
    split_type_name     VARCHAR(80) NULL,
    raw_list_json       JSON NOT NULL,
    raw_detail_json     JSON NULL,
    detail_synced_at    DATETIME(6) NULL,
    synced_at           DATETIME(6) NOT NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dhb_order_tenant_sn (tenant_id, order_sn),
    KEY idx_dhb_order_tenant_date (tenant_id, order_date),
    KEY idx_dhb_order_tenant_status (tenant_id, order_status),
    KEY idx_dhb_order_tenant_update (tenant_id, order_update_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝订单本地规范化投影';

CREATE TABLE dhb_order_line (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    order_id            BIGINT NOT NULL,
    line_id             VARCHAR(80) NULL,
    product_guid        VARCHAR(100) NULL,
    sku_no              VARCHAR(100) NULL,
    options_goods_num   VARCHAR(100) NULL,
    options_barcode     VARCHAR(160) NULL,
    product_name        VARCHAR(200) NULL,
    coding              VARCHAR(100) NULL,
    multi_first         VARCHAR(100) NULL,
    multi_second        VARCHAR(100) NULL,
    multi_name          VARCHAR(200) NULL,
    unit_price          DECIMAL(18,4) NULL,
    quantity            DECIMAL(18,4) NULL,
    unit                VARCHAR(40) NULL,
    remark              VARCHAR(1000) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dhb_order_line_order FOREIGN KEY (order_id) REFERENCES dhb_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_dhb_order_line (order_id, line_id),
    KEY idx_dhb_order_line_product (product_guid, options_goods_num)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝订单明细本地投影';

CREATE TABLE dhb_order_shipment (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    order_id            BIGINT NOT NULL,
    shipment_no         VARCHAR(100) NULL,
    status              VARCHAR(40) NULL,
    shipment_date       VARCHAR(32) NULL,
    stock_up_time       VARCHAR(32) NULL,
    created_at          DATETIME(6) NOT NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dhb_order_shipment_order FOREIGN KEY (order_id) REFERENCES dhb_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    KEY idx_dhb_order_shipment_order (order_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝订单发货信息本地投影';

CREATE TABLE dhb_order_sync_run (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(64) NOT NULL,
    function_name       VARCHAR(64) NOT NULL,
    request_json        JSON NOT NULL,
    response_status     VARCHAR(16) NULL,
    provider_total      INT NULL,
    synchronized_count  INT NOT NULL DEFAULT 0,
    run_status          VARCHAR(16) NOT NULL,
    error_message       VARCHAR(1000) NULL,
    started_at          DATETIME(6) NOT NULL,
    finished_at         DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_dhb_sync_tenant_started (tenant_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝只读同步运行记录';

-- Source: V2__internal_order_import.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台内部订单主模型';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台内部订单明细';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台内部订单发货信息';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝原始报文不可变记录';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝订单导入批次';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单领域事件Transactional Outbox';

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

-- Source: V3__order_demo_data.sql
-- 订单中心V3：本地开发租户的只读演示订单。
--
-- 这些数据用于验证 Portal 点击“订货单”后调用 GET /api/v1/orders/dhb 的分页查询链路。
-- 数据直接落在内部订单模型和来源报文表，不写入 order_outbox_event，避免演示数据触发ERP、库存、客户或BI下游事件。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-03 09:00:00.000000');

INSERT INTO order_order (
    id, tenant_id, order_no, source_system, source_order_no, internal_status, source_status,
    payment_status, order_type, total_amount, ordered_at, source_updated_at, source_update_time,
    delivery_date, remark, source_customer_no, source_customer_guid, customer_name, receiver_name,
    receiver_company, receiver_phone, receiver_address, province, city, district, source_api_status,
    source_exception_status, source_send_type, source_last_order_at, source_device, source_admin_order,
    split_type, split_type_name, source_payload_hash, detail_synced_at, imported_at, synced_at,
    version, created_at, updated_at
) VALUES
    (
        '019fc000-0000-7000-8000-000000000001', @tenant_id, 'DH.DEMO.20260803.0001', 'DINGHUOBAO',
        'DH.DEMO.20260803.0001', 'PENDING_CONFIRMATION', 'pending', 'uncollect', 'normal', 268.00,
        '2026-08-03 08:35:00.000000', '2026-08-03 08:36:10.000000', '2026-08-03 08:36:10', '2026-08-05',
        '本地开发演示订单-待审核', 'C-DEMO-001', 'DHB-CUSTOMER-DEMO-001', '成都演示客户', '张三',
        '成都演示门店', '13800000001', '四川省成都市高新区演示路1号', '四川省', '成都市', '高新区',
        'T', 'F', '快递', '2026-08-03 08:35:00', 'demo-portal', 'F', '0', '不拆单',
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', NULL, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000002', @tenant_id, 'DH.DEMO.20260803.0002', 'DINGHUOBAO',
        'DH.DEMO.20260803.0002', 'ALLOCATING', 'stock_up', 'paided', 'normal', 512.50,
        '2026-08-02 14:20:00.000000', '2026-08-02 15:02:00.000000', '2026-08-02 15:02:00', '2026-08-04',
        '本地开发演示订单-备货中', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '重庆演示客户', '李四',
        '重庆演示门店', '13800000002', '重庆市渝中区演示街2号', '重庆市', '重庆市', '渝中区',
        'T', 'F', '物流', '2026-08-02 14:20:00', 'demo-portal', 'F', '0', '不拆单',
        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', @seed_at, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000003', @tenant_id, 'DH.DEMO.20260801.0003', 'DINGHUOBAO',
        'DH.DEMO.20260801.0003', 'SHIPPED', 'shipped', 'part', 'normal', 1199.90,
        '2026-08-01 10:05:00.000000', '2026-08-01 18:30:00.000000', '2026-08-01 18:30:00', '2026-08-03',
        '本地开发演示订单-已发货', 'C-DEMO-003', 'DHB-CUSTOMER-DEMO-003', '德阳演示客户', '王五',
        '德阳演示门店', '13800000003', '四川省德阳市旌阳区演示路3号', '四川省', '德阳市', '旌阳区',
        'T', 'F', '专车配送', '2026-08-01 10:05:00', 'demo-portal', 'F', '0', '不拆单',
        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', @seed_at, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    );

INSERT INTO order_order_line (
    id, order_id, source_line_id, source_product_guid, sku_no, source_options_goods_no, source_barcode,
    product_name, product_code, specification_first, specification_second, specification_name,
    unit_price, quantity, line_amount, unit, remark, created_at, updated_at
) VALUES
    ('019fc001-0000-7000-8000-000000000001', '019fc000-0000-7000-8000-000000000001', 'DEMO-LINE-0001',
     'DHB-PRODUCT-DEMO-001', 'SKU-DEMO-001', 'GOODS-DEMO-001', '690000000001', '演示饮料A', 'DEMO-001',
     '500ml', '整箱', '500ml/整箱', 128.00, 2, 256.00, '箱', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000002', '019fc000-0000-7000-8000-000000000001', 'DEMO-LINE-0002',
     'DHB-PRODUCT-DEMO-002', 'SKU-DEMO-002', 'GOODS-DEMO-002', '690000000002', '演示零食B', 'DEMO-002',
     '100g', '袋装', '100g/袋装', 12.00, 1, 12.00, '袋', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000003', '019fc000-0000-7000-8000-000000000002', 'DEMO-LINE-0003',
     'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'GOODS-DEMO-003', '690000000003', '演示调味品C', 'DEMO-003',
     '1kg', '桶装', '1kg/桶装', 102.50, 5, 512.50, '桶', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000004', '019fc000-0000-7000-8000-000000000003', 'DEMO-LINE-0004',
     'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004', 'GOODS-DEMO-004', '690000000004', '演示粮油D', 'DEMO-004',
     '5L', '桶装', '5L/桶装', 239.98, 5, 1199.90, '桶', '测试明细', @seed_at, @seed_at);

INSERT INTO order_order_shipment (
    id, order_id, source_shipment_no, status, shipment_date, stock_up_time, created_at, updated_at
) VALUES
    ('019fc002-0000-7000-8000-000000000001', '019fc000-0000-7000-8000-000000000003',
     'DH-DEMO-SHIP-0003', 'SHIPPED', '2026-08-01 19:10:00', '2026-08-01 18:40:00', @seed_at, @seed_at);

INSERT INTO order_source_record (
    id, tenant_id, order_id, source_system, source_order_no, payload_type, payload_json, payload_hash, received_at
) VALUES
    ('019fc003-0000-7000-8000-000000000001', @tenant_id, '019fc000-0000-7000-8000-000000000001',
     'DINGHUOBAO', 'DH.DEMO.20260803.0001', 'LIST',
     '{"OrderSN":"DH.DEMO.20260803.0001","OrderStatus":"pending","OrderTotal":"268.00","ClientName":"成都演示客户"}',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', @seed_at),
    ('019fc003-0000-7000-8000-000000000002', @tenant_id, '019fc000-0000-7000-8000-000000000002',
     'DINGHUOBAO', 'DH.DEMO.20260803.0002', 'LIST',
     '{"OrderSN":"DH.DEMO.20260803.0002","OrderStatus":"stock_up","OrderTotal":"512.50","ClientName":"重庆演示客户"}',
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', @seed_at),
    ('019fc003-0000-7000-8000-000000000003', @tenant_id, '019fc000-0000-7000-8000-000000000003',
     'DINGHUOBAO', 'DH.DEMO.20260801.0003', 'LIST',
     '{"OrderSN":"DH.DEMO.20260801.0003","OrderStatus":"shipped","OrderTotal":"1199.90","ClientName":"德阳演示客户"}',
     'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', @seed_at);

-- Source: V4__order_more_demo_data.sql
-- 订单中心V4：补充不同来源状态的本地演示订单，验证订货单列表分页和状态展示。
-- 仅写入内部订单、明细、发货快照和来源报文，不写入Transactional Outbox。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-03 09:30:00.000000');

INSERT INTO order_order (
    id, tenant_id, order_no, source_system, source_order_no, internal_status, source_status,
    payment_status, order_type, total_amount, ordered_at, source_updated_at, source_update_time,
    delivery_date, remark, source_customer_no, source_customer_guid, customer_name, receiver_name,
    receiver_company, receiver_phone, receiver_address, province, city, district, source_api_status,
    source_exception_status, source_send_type, source_last_order_at, source_device, source_admin_order,
    split_type, split_type_name, source_payload_hash, detail_synced_at, imported_at, synced_at,
    version, created_at, updated_at
) VALUES
    (
        '019fc000-0000-7000-8000-000000000004', @tenant_id, 'DH.DEMO.20260803.0004', 'DINGHUOBAO',
        'DH.DEMO.20260803.0004', 'PENDING_CONFIRMATION', 'pricing', 'uncollect', 'normal', 88.00,
        '2026-08-03 09:05:00.000000', '2026-08-03 09:08:00.000000', '2026-08-03 09:08:00', '2026-08-06',
        '本地开发演示订单-待报价', 'C-DEMO-004', 'DHB-CUSTOMER-DEMO-004', '深圳演示客户', '赵六',
        '深圳演示门店', '13800000004', '广东省深圳市南山区演示路4号', '广东省', '深圳市', '南山区',
        'T', 'F', '快递', '2026-08-03 09:05:00', 'demo-portal', 'F', '0', '不拆单',
        'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', NULL, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000005', @tenant_id, 'DH.DEMO.20260802.0005', 'DINGHUOBAO',
        'DH.DEMO.20260802.0005', 'COMPLETED', 'received', 'paided', 'normal', 760.00,
        '2026-08-02 11:10:00.000000', '2026-08-02 19:00:00.000000', '2026-08-02 19:00:00', '2026-08-04',
        '本地开发演示订单-已完成', 'C-DEMO-005', 'DHB-CUSTOMER-DEMO-005', '西安演示客户', '孙七',
        '西安演示门店', '13800000005', '陕西省西安市雁塔区演示路5号', '陕西省', '西安市', '雁塔区',
        'T', 'F', '物流', '2026-08-02 11:10:00', 'demo-portal', 'F', '0', '不拆单',
        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', @seed_at, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000006', @tenant_id, 'DH.DEMO.20260801.0006', 'DINGHUOBAO',
        'DH.DEMO.20260801.0006', 'CANCELLED', 'cancelled', 'uncollect', 'normal', 45.00,
        '2026-08-01 16:25:00.000000', '2026-08-01 16:40:00.000000', '2026-08-01 16:40:00', '2026-08-03',
        '本地开发演示订单-已取消', 'C-DEMO-006', 'DHB-CUSTOMER-DEMO-006', '绵阳演示客户', '周八',
        '绵阳演示门店', '13800000006', '四川省绵阳市涪城区演示路6号', '四川省', '绵阳市', '涪城区',
        'T', 'F', '快递', '2026-08-01 16:25:00', 'demo-portal', 'F', '0', '不拆单',
        'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', NULL, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    );

INSERT INTO order_order_line (
    id, order_id, source_line_id, source_product_guid, sku_no, source_options_goods_no, source_barcode,
    product_name, product_code, specification_first, specification_second, specification_name,
    unit_price, quantity, line_amount, unit, remark, created_at, updated_at
) VALUES
    ('019fc001-0000-7000-8000-000000000005', '019fc000-0000-7000-8000-000000000004', 'DEMO-LINE-0005',
     'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'GOODS-DEMO-005', '690000000005', '演示纸品E', 'DEMO-005',
     '标准装', '箱装', '标准装/箱装', 44.00, 2, 88.00, '箱', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000006', '019fc000-0000-7000-8000-000000000005', 'DEMO-LINE-0006',
     'DHB-PRODUCT-DEMO-006', 'SKU-DEMO-006', 'GOODS-DEMO-006', '690000000006', '演示日用品F', 'DEMO-006',
     '家庭装', '箱装', '家庭装/箱装', 76.00, 10, 760.00, '箱', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000007', '019fc000-0000-7000-8000-000000000006', 'DEMO-LINE-0007',
     'DHB-PRODUCT-DEMO-007', 'SKU-DEMO-007', 'GOODS-DEMO-007', '690000000007', '演示清洁用品G', 'DEMO-007',
     '小规格', '袋装', '小规格/袋装', 15.00, 3, 45.00, '袋', '测试明细', @seed_at, @seed_at);

INSERT INTO order_order_shipment (
    id, order_id, source_shipment_no, status, shipment_date, stock_up_time, created_at, updated_at
) VALUES
    ('019fc002-0000-7000-8000-000000000002', '019fc000-0000-7000-8000-000000000005',
     'DH-DEMO-SHIP-0005', 'COMPLETED', '2026-08-02 20:10:00', '2026-08-02 19:20:00', @seed_at, @seed_at);

INSERT INTO order_source_record (
    id, tenant_id, order_id, source_system, source_order_no, payload_type, payload_json, payload_hash, received_at
) VALUES
    ('019fc003-0000-7000-8000-000000000004', @tenant_id, '019fc000-0000-7000-8000-000000000004',
     'DINGHUOBAO', 'DH.DEMO.20260803.0004', 'LIST',
     '{"OrderSN":"DH.DEMO.20260803.0004","OrderStatus":"pricing","OrderTotal":"88.00","ClientName":"深圳演示客户"}',
     'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', @seed_at),
    ('019fc003-0000-7000-8000-000000000005', @tenant_id, '019fc000-0000-7000-8000-000000000005',
     'DINGHUOBAO', 'DH.DEMO.20260802.0005', 'LIST',
     '{"OrderSN":"DH.DEMO.20260802.0005","OrderStatus":"received","OrderTotal":"760.00","ClientName":"西安演示客户"}',
     'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', @seed_at),
    ('019fc003-0000-7000-8000-000000000006', @tenant_id, '019fc000-0000-7000-8000-000000000006',
     'DINGHUOBAO', 'DH.DEMO.20260801.0006', 'LIST',
     '{"OrderSN":"DH.DEMO.20260801.0006","OrderStatus":"cancelled","OrderTotal":"45.00","ClientName":"绵阳演示客户"}',
     'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', @seed_at);

-- Source: V5__dhb_order_documents.sql
-- 订货宝一期订单域只读单据：独立发货单、退货单、收款单、付款单及明细。
-- 所有表由Order Center独占写入；来源状态保留订货宝原值，不替代平台内部状态机。

CREATE TABLE order_dhb_shipment (
    id                   CHAR(36)      NOT NULL COMMENT '平台发货单投影ID，UUID',
    tenant_id            VARCHAR(64)   NOT NULL COMMENT '租户ID，来自可信签名上下文',
    source_system        VARCHAR(32)   NOT NULL DEFAULT 'DINGHUOBAO' COMMENT '来源系统，固定DINGHUOBAO',
    source_shipment_id   VARCHAR(100)  NULL COMMENT '订货宝发货单主键ships_id',
    shipment_no          VARCHAR(100)  NOT NULL COMMENT '订货宝发货单号ships_num，租户内幂等键',
    order_no             VARCHAR(100)  NULL COMMENT '关联订货宝订单号orders_num',
    source_status        VARCHAR(40)   NULL COMMENT '来源状态：shipped待发货、receivedin待收货、received已收货、cancelled已取消',
    source_status_name   VARCHAR(80)   NULL COMMENT '订货宝返回的状态中文名status_name',
    source_type_id       VARCHAR(40)   NULL COMMENT '出库类型ID：-2采购退货、10销售出库、11盘亏、17其他、18调拨、19联营',
    source_type_name     VARCHAR(80)   NULL COMMENT '订货宝出库类型名称type_name',
    customer_no          VARCHAR(100)  NULL COMMENT '客户编号client_num',
    customer_name        VARCHAR(200)  NULL COMMENT '客户名称快照client_name',
    customer_guid        VARCHAR(100)  NULL COMMENT '客户ERP外码client_guid',
    warehouse_no         VARCHAR(100)  NULL COMMENT '出库仓库编号stock_num',
    warehouse_name       VARCHAR(200)  NULL COMMENT '出库仓库名称stock_name',
    warehouse_guid       VARCHAR(100)  NULL COMMENT '出库仓库外码stock_guid',
    shipment_at          DATETIME(6)   NULL COMMENT '来源发货时间ships_date，统一存UTC',
    logistics_name       VARCHAR(200)  NULL COMMENT '物流公司名称logistics_name',
    tracking_no          VARCHAR(160)  NULL COMMENT '物流单号express_num',
    remark               VARCHAR(1000) NULL COMMENT '发货单备注remark',
    source_created_at    DATETIME(6)   NULL COMMENT '订货宝单据创建时间create_date，统一存UTC',
    source_updated_at    DATETIME(6)   NULL COMMENT '订货宝单据更新时间update_date，统一存UTC',
    raw_json             JSON          NOT NULL COMMENT '列表同步为getShipsList单条JSON；含详情时为list+detail组合JSON，不含Token',
    payload_hash         CHAR(64)      NOT NULL COMMENT 'raw_json的SHA-256十六进制摘要，用于幂等和变更检测',
    detail_available     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已保存getShipsContent明细：0否、1是',
    synced_at            DATETIME(6)   NOT NULL COMMENT '最近一次成功落库时间，UTC',
    created_at           DATETIME(6)   NOT NULL COMMENT '本地首次创建时间，UTC',
    updated_at           DATETIME(6)   NOT NULL COMMENT '本地最近更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dhb_shipment_source (tenant_id, source_system, shipment_no),
    KEY idx_order_dhb_shipment_order (tenant_id, order_no),
    KEY idx_order_dhb_shipment_status (tenant_id, source_status, shipment_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝独立发货单只读本地投影';

CREATE TABLE order_dhb_shipment_line (
    id                   CHAR(36)      NOT NULL COMMENT '平台发货明细ID，UUID',
    shipment_id          CHAR(36)      NOT NULL COMMENT '所属order_dhb_shipment.id',
    source_line_id       VARCHAR(160)  NOT NULL COMMENT '来源明细ID；来源无ID时由Integration生成稳定键',
    source_product_guid  VARCHAR(100)  NULL COMMENT '商品ERP外码或订货宝商品ID',
    sku_no               VARCHAR(100)  NULL COMMENT '规格商品编码skuNo/options_goods_num',
    product_code         VARCHAR(100)  NULL COMMENT '商品编码coding',
    product_name         VARCHAR(240)  NULL COMMENT '商品名称name',
    quantity             DECIMAL(18,4) NULL COMMENT '本次发货数量，沿用来源小单位语义',
    unit_price           DECIMAL(18,4) NULL COMMENT 'orders_list_info.orders_price/order_units_price来源发货单价，最多4位小数',
    line_amount          DECIMAL(18,4) NULL COMMENT 'orders_list_info.actual_amount来源明细金额；来源无值时为空',
    unit_name            VARCHAR(80)   NULL COMMENT 'orders_list_info.order_units_name/base_units_name来源计量单位名称',
    warehouse_no         VARCHAR(100)  NULL COMMENT '主单stock_num出库仓库编号；明细未单独返回仓库时继承主单',
    remark               VARCHAR(1000) NULL COMMENT '发货明细备注',
    created_at           DATETIME(6)   NOT NULL COMMENT '本地创建时间，UTC',
    updated_at           DATETIME(6)   NOT NULL COMMENT '本地更新时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT fk_order_dhb_shipment_line FOREIGN KEY (shipment_id) REFERENCES order_dhb_shipment(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_order_dhb_shipment_line (shipment_id, source_line_id),
    KEY idx_order_dhb_shipment_line_sku (sku_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝发货单商品明细只读本地投影';

CREATE TABLE order_dhb_return (
    id                   CHAR(36)      NOT NULL COMMENT '平台退货单投影ID，UUID',
    tenant_id            VARCHAR(64)   NOT NULL COMMENT '租户ID，来自可信签名上下文',
    source_system        VARCHAR(32)   NOT NULL DEFAULT 'DINGHUOBAO' COMMENT '来源系统，固定DINGHUOBAO',
    return_no            VARCHAR(100)  NOT NULL COMMENT '订货宝退货单号ReturnsSN，租户内幂等键',
    order_no             VARCHAR(100)  NULL COMMENT '关联订货宝订单号OrdersNum',
    source_status        VARCHAR(40)   NULL COMMENT '来源状态：return_audit待审核、shipp_cust待客户发货、shipped待收货、refunded待退款、finished已完成、cancelled已取消',
    staff_name           VARCHAR(160)  NULL COMMENT '退货单经办人StaffName',
    return_amount        DECIMAL(18,4) NULL COMMENT '退单金额ReturnsTotal',
    settlement_amount    DECIMAL(18,4) NULL COMMENT '退单结算金额ReturnsDiscountTotal',
    returned_at          DATETIME(6)   NULL COMMENT '退货单日期ReturnsDate，统一存UTC',
    source_updated_at    DATETIME(6)   NULL COMMENT '退货单更新时间ReturnsUpdateDate，统一存UTC',
    reason               VARCHAR(1000) NULL COMMENT '退货原因ReturnsReason',
    customer_no          VARCHAR(100)  NULL COMMENT '客户编号ClientNum',
    customer_guid        VARCHAR(100)  NULL COMMENT '客户ERP外码ClientGUID',
    consignee            VARCHAR(160)  NULL COMMENT '退单收货人ReturnsConsignee',
    phone                VARCHAR(64)   NULL COMMENT '退单联系电话ReturnsPhone，前端按权限脱敏',
    address              VARCHAR(500)  NULL COMMENT '退货地址ReturnsAddress，前端按权限脱敏',
    logistics_company    VARCHAR(200)  NULL COMMENT '退货物流公司ReturnsSendCompany',
    logistics_no         VARCHAR(160)  NULL COMMENT '退货物流单号ReturnsSendNo',
    return_type          VARCHAR(16)   NULL COMMENT '退货类型：0未确认、1退货退款、2仅退款',
    delivery_mode        VARCHAR(100)  NULL COMMENT '退货配送方式ReturnsSendMode',
    raw_json             JSON          NOT NULL COMMENT '列表同步为getReturnsList单条JSON；含详情时为list+detail组合JSON，不含Token',
    payload_hash         CHAR(64)      NOT NULL COMMENT 'raw_json的SHA-256十六进制摘要，用于幂等和变更检测',
    detail_available     TINYINT       NOT NULL DEFAULT 0 COMMENT '是否已保存getReturnsContent明细：0否、1是',
    synced_at            DATETIME(6)   NOT NULL COMMENT '最近一次成功落库时间，UTC',
    created_at           DATETIME(6)   NOT NULL COMMENT '本地首次创建时间，UTC',
    updated_at           DATETIME(6)   NOT NULL COMMENT '本地最近更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dhb_return_source (tenant_id, source_system, return_no),
    KEY idx_order_dhb_return_order (tenant_id, order_no),
    KEY idx_order_dhb_return_status (tenant_id, source_status, returned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝退货单只读本地投影';

CREATE TABLE order_dhb_return_line (
    id                   CHAR(36)      NOT NULL COMMENT '平台退货明细ID，UUID',
    return_id            CHAR(36)      NOT NULL COMMENT '所属order_dhb_return.id',
    source_line_id       VARCHAR(160)  NOT NULL COMMENT '来源明细ID；来源无ID时由Integration生成稳定键',
    source_product_guid  VARCHAR(100)  NULL COMMENT '商品ERP外码Guid/TrueGuid',
    sku_no               VARCHAR(100)  NULL COMMENT '规格商品编码OptionsGoodsNum',
    product_code         VARCHAR(100)  NULL COMMENT '商品编码Coding',
    product_name         VARCHAR(240)  NULL COMMENT '商品名称Name',
    quantity             DECIMAL(18,4) NULL COMMENT '申请退货数量ReturnsNumber',
    confirmed_quantity   DECIMAL(18,4) NULL COMMENT '确认退货数量ReturnsConfirmNumber',
    unit_price           DECIMAL(18,4) NULL COMMENT '申请退货价格ReturnsPrice',
    confirmed_price      DECIMAL(18,4) NULL COMMENT '确认退货价格ReturnsConfirmPrice',
    unit_name            VARCHAR(80)   NULL COMMENT '退货单位名称ReturnsUnitsName',
    warehouse_no         VARCHAR(100)  NULL COMMENT 'body.Stock.StockGuid或StockId退货仓库外码/编号',
    warehouse_name       VARCHAR(200)  NULL COMMENT 'body.Stock.StockName退货仓库名称',
    remark               VARCHAR(1000) NULL COMMENT '退货明细备注ReturnsRemark',
    created_at           DATETIME(6)   NOT NULL COMMENT '本地创建时间，UTC',
    updated_at           DATETIME(6)   NOT NULL COMMENT '本地更新时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT fk_order_dhb_return_line FOREIGN KEY (return_id) REFERENCES order_dhb_return(id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_order_dhb_return_line (return_id, source_line_id),
    KEY idx_order_dhb_return_line_sku (sku_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝退货单商品明细只读本地投影';

CREATE TABLE order_dhb_financial_document (
    id                   CHAR(36)      NOT NULL COMMENT '平台收付款投影ID，UUID',
    tenant_id            VARCHAR(64)   NOT NULL COMMENT '租户ID，来自可信签名上下文',
    source_system        VARCHAR(32)   NOT NULL DEFAULT 'DINGHUOBAO' COMMENT '来源系统，固定DINGHUOBAO',
    document_type        VARCHAR(16)   NOT NULL COMMENT '单据类型：RECEIPT收款单、PAYMENT付款单',
    document_no          VARCHAR(100)  NOT NULL COMMENT '收款单ReceiptsNum或付款单PaymentNum，租户内幂等键',
    related_document_no  VARCHAR(100)  NULL COMMENT '付款关联收款单ReceiptsNum等来源关联单号',
    order_no             VARCHAR(100)  NULL COMMENT '关联订货宝订单号OrdersNum',
    customer_no          VARCHAR(100)  NULL COMMENT '客户编号ClientNum',
    customer_guid        VARCHAR(100)  NULL COMMENT '客户ERP外码ClientGuid',
    business_type        VARCHAR(40)   NULL COMMENT 'IncexpId：1普通充值、19预付款充值、13订单收款、8期初充值、2退货退款、10退款失败回冲、9退款红冲；其他值原样保存',
    payment_method       VARCHAR(100)  NULL COMMENT '来源支付方式兼容字段TypeId；当前官方收付款列表可能不返回，未返回时为空',
    amount               DECIMAL(18,4) NULL COMMENT '收款或付款金额Amount，最多4位小数',
    source_status        VARCHAR(40)   NULL COMMENT '来源状态：pend_receipt待确认、pend_receipted已确认、canceled已取消；来源未返回时为空',
    transaction_at       DATETIME(6)   NULL COMMENT '来源转账日期ReceiptsDate，统一存UTC',
    source_created_at    DATETIME(6)   NULL COMMENT '来源录入时间CreateDate，统一存UTC',
    source_updated_at    DATETIME(6)   NULL COMMENT '来源修改时间UpdateDate，统一存UTC',
    serial_number        VARCHAR(160)  NULL COMMENT '来源收付款流水号SerialNumber',
    account_name         VARCHAR(200)  NULL COMMENT '来源开户名称AccountName',
    bank_name            VARCHAR(200)  NULL COMMENT '来源开户行BankName',
    account_number       VARCHAR(200)  NULL COMMENT '来源银行账号AccountNumber，前端按权限脱敏',
    remark               VARCHAR(1000) NULL COMMENT '来源备注Remark',
    raw_json             JSON          NOT NULL COMMENT 'getReceiptsList或getPaymentList的单据原始JSON，不含Token',
    payload_hash         CHAR(64)      NOT NULL COMMENT 'raw_json的SHA-256十六进制摘要，用于幂等和变更检测',
    synced_at            DATETIME(6)   NOT NULL COMMENT '最近一次成功落库时间，UTC',
    created_at           DATETIME(6)   NOT NULL COMMENT '本地首次创建时间，UTC',
    updated_at           DATETIME(6)   NOT NULL COMMENT '本地最近更新时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT ck_order_dhb_financial_type CHECK (document_type IN ('RECEIPT', 'PAYMENT')),
    UNIQUE KEY uk_order_dhb_financial_source (tenant_id, source_system, document_type, document_no),
    KEY idx_order_dhb_financial_order (tenant_id, order_no),
    KEY idx_order_dhb_financial_status (tenant_id, document_type, source_status, transaction_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝收款单和付款单只读本地投影';

-- Source: V6__order_import_field_comments.sql
-- 为既有订单导入表补齐列级说明；不修改V2历史迁移，避免已执行Flyway校验和漂移。
-- 状态注释明确区分订货宝来源状态与平台internal_status。
-- 先临时移除三条外键，避免MySQL拒绝MODIFY被外键引用的列；迁移末尾按原规则恢复。

ALTER TABLE order_order_line DROP FOREIGN KEY fk_order_line_order;
ALTER TABLE order_order_shipment DROP FOREIGN KEY fk_order_shipment_order;
ALTER TABLE order_source_record DROP FOREIGN KEY fk_order_source_record_order;

ALTER TABLE order_order
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '平台内部订单ID，UUID',
    MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL COMMENT '租户ID，来自可信签名上下文；所有查询和写入必须隔离',
    MODIFY COLUMN order_no VARCHAR(80) NOT NULL COMMENT '平台订单号；一期订货宝导入默认等于source_order_no',
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL COMMENT '来源系统编码；订货宝数据固定DINGHUOBAO',
    MODIFY COLUMN source_order_no VARCHAR(80) NOT NULL COMMENT '订货宝订单号OrderSN；与tenant_id、source_system组成幂等键',
    MODIFY COLUMN internal_status VARCHAR(40) NOT NULL COMMENT '平台内部状态：RECEIVED、PENDING_CONFIRMATION、ALLOCATING、SHIPPED、COMPLETED、CANCELLED、EXCEPTION；外部同步不得覆盖已有值',
    MODIFY COLUMN source_status VARCHAR(40) NULL COMMENT '订货宝OrderStatus响应原值：pricing待核价、pending待审核、stockup待出库、shipped待发货、received待收货、finished已完成、forcedone强制完成、cancelled已取消；stock_up为查询参数/历史兼容值',
    MODIFY COLUMN payment_status VARCHAR(40) NULL COMMENT 'getOrderList.PayStatus收款状态：oblig待收款、uncollect部分收款、paided已收款、cancelled已取消、wait待确认、part部分确认',
    MODIFY COLUMN order_type VARCHAR(40) NULL COMMENT '订货宝订单类型OrderType原值',
    MODIFY COLUMN total_amount DECIMAL(18,4) NULL COMMENT '订货宝订单总金额OrderTotal，最多4位小数',
    MODIFY COLUMN ordered_at DATETIME(6) NULL COMMENT '订货宝下单时间OrderDate；按Asia/Shanghai解析后统一存UTC',
    MODIFY COLUMN source_updated_at DATETIME(6) NULL COMMENT '订货宝订单更新时间OrderUpdateDate；按Asia/Shanghai解析后统一存UTC',
    MODIFY COLUMN source_update_time VARCHAR(32) NULL COMMENT '订货宝OrderUpdateDate原始文本，供追溯异常时间格式',
    MODIFY COLUMN delivery_date VARCHAR(32) NULL COMMENT '订货宝要求交付日期DeliveryDate/SendDate原始文本',
    MODIFY COLUMN remark VARCHAR(1000) NULL COMMENT '订货宝订单备注OrderRemark/Remark',
    MODIFY COLUMN source_customer_no VARCHAR(80) NULL COMMENT '订货宝客户编号ClientNO/ClientNum',
    MODIFY COLUMN source_customer_guid VARCHAR(80) NULL COMMENT '订货宝客户ERP外码ClientGUID',
    MODIFY COLUMN customer_name VARCHAR(160) NULL COMMENT '订货宝客户名称ClientName/ClientCompanyName快照',
    MODIFY COLUMN receiver_name VARCHAR(80) NULL COMMENT '订货宝收货人OrderReceiveName快照',
    MODIFY COLUMN receiver_company VARCHAR(200) NULL COMMENT '订货宝收货单位OrderReceiveCompany快照',
    MODIFY COLUMN receiver_phone VARCHAR(64) NULL COMMENT '订货宝收货电话OrderReceivePhone；敏感字段，前端按权限脱敏',
    MODIFY COLUMN receiver_address VARCHAR(500) NULL COMMENT '订货宝收货地址OrderReceiveAdd/OrderReceiveAddTwo；敏感字段，前端按权限脱敏',
    MODIFY COLUMN province VARCHAR(80) NULL COMMENT '订货宝收货省份Province',
    MODIFY COLUMN city VARCHAR(80) NULL COMMENT '订货宝收货城市City',
    MODIFY COLUMN district VARCHAR(80) NULL COMMENT '订货宝收货区县District',
    MODIFY COLUMN source_api_status VARCHAR(8) NULL COMMENT '订货宝下载标记OrderApi/ApiStatus：F未下载、T已下载',
    MODIFY COLUMN source_exception_status VARCHAR(8) NULL COMMENT '订货宝异常标记ExceptionStatus：F正常、T异常',
    MODIFY COLUMN source_send_type VARCHAR(80) NULL COMMENT '订货宝发货方式SendType原值',
    MODIFY COLUMN source_last_order_at VARCHAR(32) NULL COMMENT '订货宝最后下单时间LastOrderDate原始文本',
    MODIFY COLUMN source_device VARCHAR(40) NULL COMMENT '订货宝下单设备SourceDevice原值',
    MODIFY COLUMN source_admin_order VARCHAR(8) NULL COMMENT '订货宝管理员订单标记IsAdminOrder原值',
    MODIFY COLUMN split_type VARCHAR(32) NULL COMMENT '订货宝拆单类型SplitType原值',
    MODIFY COLUMN split_type_name VARCHAR(80) NULL COMMENT '订货宝拆单类型中文名SplitTypeName',
    MODIFY COLUMN source_payload_hash CHAR(64) NOT NULL COMMENT '当前有效列表或详情Raw JSON的SHA-256小写十六进制摘要',
    MODIFY COLUMN detail_synced_at DATETIME(6) NULL COMMENT '最近成功保存getOrderContent详情的时间，UTC；为空表示只有列表摘要',
    MODIFY COLUMN imported_at DATETIME(6) NOT NULL COMMENT '订单首次导入订单中心的时间，UTC',
    MODIFY COLUMN synced_at DATETIME(6) NOT NULL COMMENT '订单最近一次成功同步落库时间，UTC',
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '本地记录版本号；来源内容变化或详情刷新时递增',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '本地记录创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '本地记录最后更新时间，UTC';

ALTER TABLE order_order_line
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '平台订单明细ID，UUID',
    MODIFY COLUMN order_id CHAR(36) NOT NULL COMMENT '所属order_order.id',
    MODIFY COLUMN source_line_id VARCHAR(100) NOT NULL COMMENT '订货宝订单明细ID orders_list_id；来源缺失时由Integration生成稳定键',
    MODIFY COLUMN source_product_guid VARCHAR(100) NULL COMMENT '订货宝商品ERP外码guid/Guid/TrueGuid',
    MODIFY COLUMN sku_no VARCHAR(100) NULL COMMENT '订货宝规格商品编码options_goods_num/skuNo',
    MODIFY COLUMN source_options_goods_no VARCHAR(100) NULL COMMENT '订货宝商品选项编号OptionsGoodsNo；来源未返回时为空',
    MODIFY COLUMN source_barcode VARCHAR(160) NULL COMMENT '订货宝规格条码options_barcode',
    MODIFY COLUMN product_name VARCHAR(200) NULL COMMENT '订货宝商品名称Name快照',
    MODIFY COLUMN product_code VARCHAR(100) NULL COMMENT '订货宝商品编码Coding',
    MODIFY COLUMN specification_first VARCHAR(100) NULL COMMENT '订货宝第一层规格multiFirst',
    MODIFY COLUMN specification_second VARCHAR(100) NULL COMMENT '订货宝第二层规格multiSecond',
    MODIFY COLUMN specification_name VARCHAR(200) NULL COMMENT '订货宝组合规格名称multiName',
    MODIFY COLUMN unit_price DECIMAL(18,4) NULL COMMENT '订货宝订单明细单价ContentPrice/order_units_price',
    MODIFY COLUMN quantity DECIMAL(18,4) NULL COMMENT '订货宝订单明细数量ContentNumber/order_units_number',
    MODIFY COLUMN line_amount DECIMAL(18,4) NULL COMMENT '订货宝订单明细实际金额ActualAmount',
    MODIFY COLUMN unit VARCHAR(40) NULL COMMENT '订货宝订单单位order_units_name/base_units_name/Units',
    MODIFY COLUMN remark VARCHAR(1000) NULL COMMENT '订货宝订单明细备注remark',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '本地明细创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '本地明细最后更新时间，UTC';

ALTER TABLE order_order_shipment
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '平台订单内发货快照ID，UUID',
    MODIFY COLUMN order_id CHAR(36) NOT NULL COMMENT '所属order_order.id',
    MODIFY COLUMN source_shipment_no VARCHAR(100) NOT NULL COMMENT 'getOrderContent.Ships发货单号ships_num；与order_id组成幂等键',
    MODIFY COLUMN status VARCHAR(40) NULL COMMENT 'Ships.status来源状态原值：shipped待发货、receivedin待收货、received已收货、cancelled已取消',
    MODIFY COLUMN shipment_date VARCHAR(32) NULL COMMENT 'Ships.ships_date来源发货时间原始文本',
    MODIFY COLUMN stock_up_time VARCHAR(32) NULL COMMENT 'Ships.stock_up_time来源备货时间原始文本',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL COMMENT '本地快照创建时间，UTC',
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL COMMENT '本地快照最后更新时间，UTC';

ALTER TABLE order_source_record
    MODIFY COLUMN id CHAR(36) NOT NULL COMMENT '不可变来源报文记录ID，UUID',
    MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL COMMENT '租户ID，来自可信签名上下文',
    MODIFY COLUMN order_id CHAR(36) NOT NULL COMMENT '关联order_order.id',
    MODIFY COLUMN source_system VARCHAR(32) NOT NULL COMMENT '来源系统编码；订货宝数据固定DINGHUOBAO',
    MODIFY COLUMN source_order_no VARCHAR(80) NOT NULL COMMENT '订货宝订单号OrderSN',
    MODIFY COLUMN payload_type VARCHAR(16) NOT NULL COMMENT '报文类型：LIST=getOrderList单条摘要、DETAIL=getOrderContent完整详情',
    MODIFY COLUMN payload_json JSON NOT NULL COMMENT '单条订货宝原始JSON，不含sKey、SerialNumber或Password；用于审计和重放',
    MODIFY COLUMN payload_hash CHAR(64) NOT NULL COMMENT 'payload_json的SHA-256小写十六进制摘要，用于幂等去重',
    MODIFY COLUMN received_at DATETIME(6) NOT NULL COMMENT '订单中心接收该来源报文的时间，UTC';

ALTER TABLE order_order_line
    ADD CONSTRAINT fk_order_line_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE order_order_shipment
    ADD CONSTRAINT fk_order_shipment_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE order_source_record
    ADD CONSTRAINT fk_order_source_record_order FOREIGN KEY (order_id) REFERENCES order_order(id)
        ON DELETE CASCADE ON UPDATE RESTRICT;

-- Source: V7__dhb_order_sync_checkpoint.sql
-- 订货宝订单域增量同步游标；只在Order Center完成业务幂等落库后推进。
CREATE TABLE order_dhb_sync_checkpoint (
    id                 CHAR(36)     NOT NULL COMMENT '同步游标主键，UUID',
    tenant_id          VARCHAR(64)  NOT NULL COMMENT '租户ID，来自可信调用上下文',
    connector_id       CHAR(36)     NOT NULL COMMENT 'Integration中的订货宝连接器UUID',
    object_type        VARCHAR(32)  NOT NULL COMMENT '同步对象：ORDER；后续可扩展SHIPMENT、RETURN、RECEIPT、PAYMENT',
    last_success_at    DATETIME(6)  NULL COMMENT '最近一次完整业务落库成功的窗口结束时间，UTC；首次同步为空',
    last_run_id        CHAR(36)     NULL COMMENT '最近一次同步运行ID，用于跨服务追踪',
    sync_status        VARCHAR(16)  NOT NULL COMMENT '最近运行状态：IDLE、SUCCEEDED、FAILED',
    last_run_at        DATETIME(6)  NULL COMMENT '最近一次运行时间，UTC',
    last_error         VARCHAR(1000) NULL COMMENT '最近一次失败信息；不得保存凭据和Token',
    created_at         DATETIME(6)  NOT NULL COMMENT '本地创建时间，UTC',
    updated_at         DATETIME(6)  NOT NULL COMMENT '本地最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dhb_checkpoint (tenant_id, connector_id, object_type),
    KEY idx_order_dhb_checkpoint_status (sync_status, last_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝订单域增量同步游标和运行状态';

-- Source: V8__dhb_document_demo_data.sql
-- 订单中心V8：本地开发租户的单据演示数据。
--
-- 仅用于验证 Portal 的出库单、发货单、退货单、收款单和付款单只读查询链路；
-- 数据使用 demo-portal 语义，不写入 Outbox，也不触发下游业务事件。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-05 10:00:00.000000');

INSERT INTO order_dhb_shipment (
    id, tenant_id, source_system, source_shipment_id, shipment_no, order_no, source_status,
    source_status_name, source_type_id, source_type_name, customer_no, customer_name, customer_guid,
    warehouse_no, warehouse_name, warehouse_guid, shipment_at, logistics_name, tracking_no, remark,
    source_created_at, source_updated_at, raw_json, payload_hash, detail_available, synced_at, created_at, updated_at
) VALUES
    (
        '019fd000-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DHB-SHIP-ID-0001',
        'DH.DEMO.SHIP.0001', 'DH.DEMO.20260803.0002', 'shipped', '待发货', '10', '销售出库',
        'C-DEMO-002', '重庆演示客户', 'DHB-CUSTOMER-DEMO-002', 'WH-DEMO-001', '成都中心仓',
        'DHB-WH-DEMO-001', NULL, '顺丰速运', NULL, '出库单演示数据-待发货',
        '2026-08-05 09:20:00.000000', '2026-08-05 09:25:00.000000',
        '{"ships_num":"DH.DEMO.SHIP.0001","orders_num":"DH.DEMO.20260803.0002","status":"shipped"}',
        '1111111111111111111111111111111111111111111111111111111111111111', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd000-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DHB-SHIP-ID-0002',
        'DH.DEMO.SHIP.0002', 'DH.DEMO.20260801.0003', 'received', '已收货', '10', '销售出库',
        'C-DEMO-003', '德阳演示客户', 'DHB-CUSTOMER-DEMO-003', 'WH-DEMO-002', '德阳分仓',
        'DHB-WH-DEMO-002', '2026-08-04 15:20:00.000000', '中通快递', 'ZTO-DEMO-0002', '出库单演示数据-已收货',
        '2026-08-03 09:20:00.000000', '2026-08-04 15:20:00.000000',
        '{"ships_num":"DH.DEMO.SHIP.0002","orders_num":"DH.DEMO.20260801.0003","status":"received"}',
        '2222222222222222222222222222222222222222222222222222222222222222', 1, @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_shipment_line (
    id, shipment_id, source_line_id, source_product_guid, sku_no, product_code, product_name,
    quantity, unit_price, line_amount, unit_name, warehouse_no, remark, created_at, updated_at
) VALUES
    (
        '019fd001-0000-7000-8000-000000000001', '019fd000-0000-7000-8000-000000000001', 'DHB-SHIP-LINE-0001',
        'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'DEMO-003', '演示调味品C', 5, 102.50, 512.50,
        '桶', 'WH-DEMO-001', '出库明细演示数据', @seed_at, @seed_at
    ),
    (
        '019fd001-0000-7000-8000-000000000002', '019fd000-0000-7000-8000-000000000002', 'DHB-SHIP-LINE-0002',
        'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004', 'DEMO-004', '演示粮油D', 5, 239.98, 1199.90,
        '桶', 'WH-DEMO-002', '出库明细演示数据', @seed_at, @seed_at
    );

INSERT INTO order_dhb_return (
    id, tenant_id, source_system, return_no, order_no, source_status, staff_name, return_amount,
    settlement_amount, returned_at, source_updated_at, reason, customer_no, customer_guid, consignee,
    phone, address, logistics_company, logistics_no, return_type, delivery_mode, raw_json, payload_hash,
    detail_available, synced_at, created_at, updated_at
) VALUES
    (
        '019fd010-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0001',
        'DH.DEMO.20260803.0002', 'return_audit', '演示经办人', 102.50, NULL, '2026-08-05 09:40:00.000000',
        '2026-08-05 09:45:00.000000', '商品破损', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '李四',
        '13800000002', '重庆市渝中区演示街2号', NULL, NULL, '1', '快递寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0001","OrdersNum":"DH.DEMO.20260803.0002","Status":"return_audit"}',
        '3333333333333333333333333333333333333333333333333333333333333333', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd010-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0002',
        'DH.DEMO.20260801.0003', 'finished', '演示经办人', 239.98, 239.98, '2026-08-04 11:10:00.000000',
        '2026-08-04 18:00:00.000000', '客户误下单', 'C-DEMO-003', 'DHB-CUSTOMER-DEMO-003', '王五',
        '13800000003', '四川省德阳市旌阳区演示路3号', '中通快递', 'ZTO-RETURN-DEMO-0002', '1', '快递寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0002","OrdersNum":"DH.DEMO.20260801.0003","Status":"finished"}',
        '4444444444444444444444444444444444444444444444444444444444444444', 1, @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_return_line (
    id, return_id, source_line_id, source_product_guid, sku_no, product_code, product_name,
    quantity, confirmed_quantity, unit_price, confirmed_price, unit_name, warehouse_no, warehouse_name,
    remark, created_at, updated_at
) VALUES
    (
        '019fd011-0000-7000-8000-000000000001', '019fd010-0000-7000-8000-000000000001', 'DHB-RETURN-LINE-0001',
        'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'DEMO-003', '演示调味品C', 1, NULL, 102.50, NULL,
        '桶', 'WH-DEMO-001', '成都中心仓', '退货明细演示数据', @seed_at, @seed_at
    ),
    (
        '019fd011-0000-7000-8000-000000000002', '019fd010-0000-7000-8000-000000000002', 'DHB-RETURN-LINE-0002',
        'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004', 'DEMO-004', '演示粮油D', 1, 1, 239.98, 239.98,
        '桶', 'WH-DEMO-002', '德阳分仓', '退货明细演示数据', @seed_at, @seed_at
    );

INSERT INTO order_dhb_financial_document (
    id, tenant_id, source_system, document_type, document_no, related_document_no, order_no, customer_no,
    customer_guid, business_type, payment_method, amount, source_status, transaction_at, source_created_at,
    source_updated_at, serial_number, account_name, bank_name, account_number, remark, raw_json, payload_hash,
    synced_at, created_at, updated_at
) VALUES
    (
        '019fd020-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'RECEIPT', 'DH.DEMO.RECEIPT.0001',
        NULL, 'DH.DEMO.20260803.0002', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '13', '银行转账', 512.50,
        'pend_receipted', '2026-08-05 09:50:00.000000', '2026-08-05 09:50:00.000000', '2026-08-05 09:55:00.000000',
        'DEMO-RECEIPT-SERIAL-0001', '成都演示供应链', '演示银行', '622200000000000001', '收款单演示数据',
        '{"ReceiptsNum":"DH.DEMO.RECEIPT.0001","OrdersNum":"DH.DEMO.20260803.0002","Amount":"512.50"}',
        '5555555555555555555555555555555555555555555555555555555555555555', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd020-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'PAYMENT', 'DH.DEMO.PAYMENT.0001',
        'DH.DEMO.RECEIPT.0001', 'DH.DEMO.20260803.0002', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '2', '原路退款', 100.00,
        'pend_receipt', '2026-08-05 10:00:00.000000', '2026-08-05 10:00:00.000000', '2026-08-05 10:05:00.000000',
        'DEMO-PAYMENT-SERIAL-0001', '成都演示供应链', '演示银行', '622200000000000001', '付款单演示数据',
        '{"PaymentNum":"DH.DEMO.PAYMENT.0001","ReceiptsNum":"DH.DEMO.RECEIPT.0001","Amount":"100.00"}',
        '6666666666666666666666666666666666666666666666666666666666666666', @seed_at, @seed_at, @seed_at
    );

-- Source: V9__dhb_wait_ship_logistics.sql
-- 订单中心V9：订货宝getWaitShips出库/发货物流快照。
-- 主表按租户+订单号幂等，明细同时保存shipped已出库/已发货和wait_stock待出库两类来源数据。

CREATE TABLE order_dhb_shipment_logistics (
    id                   CHAR(36)      NOT NULL COMMENT '平台物流快照ID，UUID',
    tenant_id            VARCHAR(64)   NOT NULL COMMENT '租户ID，来自可信签名上下文',
    source_system        VARCHAR(32)   NOT NULL DEFAULT 'DINGHUOBAO' COMMENT '来源系统，固定DINGHUOBAO',
    order_no             VARCHAR(100)  NOT NULL COMMENT '订货宝订单号orders_num，租户内幂等键',
    shipment_no          VARCHAR(100)  NULL COMMENT '最近一条shipped记录的ships_num',
    source_status        VARCHAR(40)   NULL COMMENT '最近一条shipped记录状态：shipped待发货、receivedin待收货、received已收货、cancelled已取消',
    logistics_name       VARCHAR(200)  NULL COMMENT '物流公司名称logistics_name',
    logistics_code       VARCHAR(100)  NULL COMMENT '物流公司编码logistics_code',
    tracking_no          VARCHAR(160)  NULL COMMENT '物流单号express_num',
    shipment_at          DATETIME(6)   NULL COMMENT '发货时间ships_date，统一存UTC',
    stock_up_at          DATETIME(6)   NULL COMMENT '出库时间ships_time，统一存UTC',
    warehouse_no         VARCHAR(100)  NULL COMMENT '仓库编号stock_num',
    warehouse_name       VARCHAR(200)  NULL COMMENT '仓库名称stock_name',
    shipped_count        INT           NOT NULL DEFAULT 0 COMMENT '已出库/已发货记录数量',
    wait_stock_count     INT           NOT NULL DEFAULT 0 COMMENT '待出库明细数量',
    raw_json             JSON          NOT NULL COMMENT 'getWaitShips完整业务原始JSON，不含sKey',
    payload_hash         CHAR(64)      NOT NULL COMMENT 'raw_json的SHA-256十六进制摘要，用于幂等和变更检测',
    synced_at            DATETIME(6)   NOT NULL COMMENT '最近一次成功落库时间，UTC',
    created_at            DATETIME(6)   NOT NULL COMMENT '本地首次创建时间，UTC',
    updated_at            DATETIME(6)   NOT NULL COMMENT '本地最近更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dhb_wait_ship_source (tenant_id, source_system, order_no),
    KEY idx_order_dhb_wait_ship_status (tenant_id, source_status, shipment_at),
    KEY idx_order_dhb_wait_ship_tracking (tenant_id, tracking_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝getWaitShips出库/发货物流快照';

CREATE TABLE order_dhb_shipment_logistics_line (
    id                   CHAR(36)      NOT NULL COMMENT '平台物流明细ID，UUID',
    logistics_id         CHAR(36)      NOT NULL COMMENT '所属order_dhb_shipment_logistics.id',
    line_type            VARCHAR(20)   NOT NULL COMMENT '明细类型：SHIPPED已出库/已发货、WAIT_STOCK待出库',
    shipment_no          VARCHAR(100)  NULL COMMENT 'SHIPPED来源ships_num；WAIT_STOCK为空',
    source_line_id       VARCHAR(160)  NOT NULL COMMENT '来源明细ID：ships_list_id或orders_list_id',
    order_line_id        VARCHAR(160)  NULL COMMENT '关联订单明细IDorders_list_id',
    product_id           VARCHAR(100)  NULL COMMENT '商品IDgoods_id',
    sku_no               VARCHAR(100)  NULL COMMENT '规格商品编码options_goods_num',
    list_type            VARCHAR(20)   NULL COMMENT '买品buy或赠品gift',
    product_code         VARCHAR(100)  NULL COMMENT '商品编码goods_num',
    product_name         VARCHAR(240)  NULL COMMENT '商品名称goods_name',
    specification        VARCHAR(500)  NULL COMMENT '商品规格goods_options',
    unit                 VARCHAR(80)   NULL COMMENT '小单位base_units',
    container_unit       VARCHAR(80)   NULL COMMENT '大单位container_units',
    conversion_number    DECIMAL(18,4) NULL COMMENT '单位换算关系conversion_number',
    quantity             DECIMAL(18,4) NULL COMMENT 'SHIPPED出库数量ships_number',
    ordered_quantity     DECIMAL(18,4) NULL COMMENT 'WAIT_STOCK订购数量orders_number',
    stocked_quantity     DECIMAL(18,4) NULL COMMENT 'WAIT_STOCK已出库数量stock_number',
    real_stock           DECIMAL(18,4) NULL COMMENT 'WAIT_STOCK实际库存real_number',
    wait_quantity        DECIMAL(18,4) NULL COMMENT 'WAIT_STOCK待出库数量wait_stock_number',
    warehouse_no         VARCHAR(100)  NULL COMMENT '来源仓库编号stock_num',
    warehouse_name       VARCHAR(200)  NULL COMMENT '来源仓库名称stock_name',
    remark               VARCHAR(1000) NULL COMMENT '明细备注remark',
    created_at           DATETIME(6)   NOT NULL COMMENT '本地创建时间，UTC',
    updated_at           DATETIME(6)   NOT NULL COMMENT '本地更新时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT fk_order_dhb_wait_ship_line FOREIGN KEY (logistics_id)
        REFERENCES order_dhb_shipment_logistics(id) ON DELETE CASCADE ON UPDATE RESTRICT,
    UNIQUE KEY uk_order_dhb_wait_ship_line (logistics_id, line_type, shipment_no, source_line_id),
    KEY idx_order_dhb_wait_ship_line_sku (sku_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝getWaitShips出库/发货物流明细';

-- Source: V10__dhb_wait_ship_logistics_demo_data.sql
-- 订单中心V10：本地开发租户的getWaitShips物流演示数据。
-- V9先建表，本迁移再写入演示快照；生产环境可按环境策略关闭演示数据迁移。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-05 10:00:00.000000');

INSERT INTO order_dhb_shipment_logistics (
    id, tenant_id, source_system, order_no, shipment_no, source_status, logistics_name,
    logistics_code, tracking_no, shipment_at, stock_up_at, warehouse_no, warehouse_name,
    shipped_count, wait_stock_count, raw_json, payload_hash, synced_at, created_at, updated_at
) VALUES
    (
        '019fd030-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260803.0002',
        'DH.DEMO.SHIP.0001', 'receivedin', '顺丰速运', 'SF', 'SF-DEMO-0001',
        '2026-08-05 09:30:00.000000', '2026-08-05 09:20:00.000000', 'WH-DEMO-001', '成都中心仓',
        1, 1,
        '{"shipped":[{"ships_num":"DH.DEMO.SHIP.0001","status":"receivedin"}],"wait_stock":[{"orders_list_id":"DH.DEMO.ORDER.LINE.0002","wait_stock_number":"2"}]}',
        '7777777777777777777777777777777777777777777777777777777777777777', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd030-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260801.0003',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'WH-DEMO-002', '德阳分仓',
        0, 1,
        '{"shipped":[],"wait_stock":[{"orders_list_id":"DH.DEMO.ORDER.LINE.0003","wait_stock_number":"1"}]}',
        '8888888888888888888888888888888888888888888888888888888888888888', @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_shipment_logistics_line (
    id, logistics_id, line_type, shipment_no, source_line_id, order_line_id, product_id, sku_no,
    list_type, product_code, product_name, specification, unit, container_unit, conversion_number,
    quantity, ordered_quantity, stocked_quantity, real_stock, wait_quantity, warehouse_no,
    warehouse_name, remark, created_at, updated_at
) VALUES
    (
        '019fd031-0000-7000-8000-000000000001', '019fd030-0000-7000-8000-000000000001',
        'SHIPPED', 'DH.DEMO.SHIP.0001', 'DH.DEMO.SHIP.LINE.0001', 'DH.DEMO.ORDER.LINE.0001',
        'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'buy', 'DEMO-003', '演示调味品C', '原味', '桶', '箱', 10,
        5, NULL, NULL, NULL, NULL, 'WH-DEMO-001', '成都中心仓', '已发货物流演示数据', @seed_at, @seed_at
    ),
    (
        '019fd031-0000-7000-8000-000000000002', '019fd030-0000-7000-8000-000000000001',
        'WAIT_STOCK', '', 'DH.DEMO.ORDER.LINE.0002', NULL, 'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004',
        'buy', 'DEMO-004', '演示粮油D', '标准', '桶', '箱', 10,
        NULL, 3, 1, 5, 2, 'WH-DEMO-001', '成都中心仓', '待出库物流演示数据', @seed_at, @seed_at
    ),
    (
        '019fd031-0000-7000-8000-000000000003', '019fd030-0000-7000-8000-000000000002',
        'WAIT_STOCK', '', 'DH.DEMO.ORDER.LINE.0003', NULL, 'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005',
        'buy', 'DEMO-005', '演示饮料E', '整箱', '箱', '托', 12,
        NULL, 2, 1, 0, 1, 'WH-DEMO-002', '德阳分仓', '待出库物流演示数据', @seed_at, @seed_at
    );

-- Source: V11__dhb_logistics_return_more_demo_data.sql
-- 订单中心V11：补充本地开发租户的出库/发货物流、退货单及明细演示数据。
-- 覆盖已发货、已收货、待出库，以及待客户发货、待收货、已取消等状态筛选场景。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-06 09:30:00.000000');

INSERT INTO order_dhb_shipment_logistics (
    id, tenant_id, source_system, order_no, shipment_no, source_status, logistics_name,
    logistics_code, tracking_no, shipment_at, stock_up_at, warehouse_no, warehouse_name,
    shipped_count, wait_stock_count, raw_json, payload_hash, synced_at, created_at, updated_at
) VALUES
    (
        '019fd040-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260803.0001',
        'DH.DEMO.SHIP.0003', 'shipped', '京东物流', 'JD', 'JD-DEMO-0003',
        '2026-08-06 08:40:00.000000', '2026-08-06 08:20:00.000000', 'WH-DEMO-003', '深圳前置仓',
        1, 0,
        '{"shipped":[{"ships_num":"DH.DEMO.SHIP.0003","status":"shipped","express_num":"JD-DEMO-0003"}],"wait_stock":[]}',
        '9999999999999999999999999999999999999999999999999999999999999999', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd040-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260803.0004',
        'DH.DEMO.SHIP.0004', 'received', '圆通速递', 'YTO', 'YTO-DEMO-0004',
        '2026-08-04 16:30:00.000000', '2026-08-04 16:00:00.000000', 'WH-DEMO-004', '深圳中心仓',
        1, 0,
        '{"shipped":[{"ships_num":"DH.DEMO.SHIP.0004","status":"received","express_num":"YTO-DEMO-0004"}],"wait_stock":[]}',
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd040-0000-7000-8000-000000000003', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260802.0005',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'WH-DEMO-005', '西安分仓',
        0, 2,
        '{"shipped":[],"wait_stock":[{"orders_list_id":"DH.DEMO.ORDER.LINE.0005A","wait_stock_number":"4"},{"orders_list_id":"DH.DEMO.ORDER.LINE.0005B","wait_stock_number":"2"}]}',
        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_shipment_logistics_line (
    id, logistics_id, line_type, shipment_no, source_line_id, order_line_id, product_id, sku_no,
    list_type, product_code, product_name, specification, unit, container_unit, conversion_number,
    quantity, ordered_quantity, stocked_quantity, real_stock, wait_quantity, warehouse_no,
    warehouse_name, remark, created_at, updated_at
) VALUES
    (
        '019fd041-0000-7000-8000-000000000001', '019fd040-0000-7000-8000-000000000001',
        'SHIPPED', 'DH.DEMO.SHIP.0003', 'DHB.DEMO.SHIP.LINE.0003', 'DHB.DEMO.ORDER.LINE.0001',
        'DHB-PRODUCT-DEMO-001', 'SKU-DEMO-001', 'buy', 'DEMO-001', '演示饮料A', '整箱', '箱', '托', 12,
        3, NULL, NULL, NULL, NULL, 'WH-DEMO-003', '深圳前置仓', '已发货物流测试数据', @seed_at, @seed_at
    ),
    (
        '019fd041-0000-7000-8000-000000000002', '019fd040-0000-7000-8000-000000000002',
        'SHIPPED', 'DH.DEMO.SHIP.0004', 'DHB.DEMO.SHIP.LINE.0004', 'DHB.DEMO.ORDER.LINE.0005',
        'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'buy', 'DEMO-005', '演示纸品E', '标准', '箱', '托', 10,
        2, NULL, NULL, NULL, NULL, 'WH-DEMO-004', '深圳中心仓', '已收货物流测试数据', @seed_at, @seed_at
    ),
    (
        '019fd041-0000-7000-8000-000000000003', '019fd040-0000-7000-8000-000000000003',
        'WAIT_STOCK', '', 'DHB.DEMO.ORDER.LINE.0005A', NULL,
        'DHB-PRODUCT-DEMO-006', 'SKU-DEMO-006', 'buy', 'DEMO-006', '演示日用品F', '标准', '件', '箱', 24,
        NULL, 4, 2, 1, 2, 'WH-DEMO-005', '西安分仓', '待出库物流测试数据', @seed_at, @seed_at
    ),
    (
        '019fd041-0000-7000-8000-000000000004', '019fd040-0000-7000-8000-000000000003',
        'WAIT_STOCK', '', 'DHB.DEMO.ORDER.LINE.0005B', NULL,
        'DHB-PRODUCT-DEMO-007', 'SKU-DEMO-007', 'buy', 'DEMO-007', '演示清洁用品G', '补充装', '件', '箱', 20,
        NULL, 2, 0, 0, 2, 'WH-DEMO-005', '西安分仓', '待出库物流测试数据', @seed_at, @seed_at
    );

INSERT INTO order_dhb_return (
    id, tenant_id, source_system, return_no, order_no, source_status, staff_name, return_amount,
    settlement_amount, returned_at, source_updated_at, reason, customer_no, customer_guid, consignee,
    phone, address, logistics_company, logistics_no, return_type, delivery_mode, raw_json, payload_hash,
    detail_available, synced_at, created_at, updated_at
) VALUES
    (
        '019fd050-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0003',
        'DH.DEMO.20260803.0004', 'shipp_cust', '演示经办人-赵六', 88.00, NULL,
        '2026-08-05 14:20:00.000000', '2026-08-05 14:30:00.000000', '客户拒收',
        'C-DEMO-004', 'DHB-CUSTOMER-DEMO-004', '赵六', '13800000004', '深圳市南山区演示路4号',
        NULL, NULL, '1', '上门取件',
        '{"ReturnsSN":"DH.DEMO.RETURN.0003","OrdersNum":"DH.DEMO.20260803.0004","Status":"shipp_cust"}',
        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd050-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0004',
        'DH.DEMO.20260802.0005', 'shipped', '演示经办人-孙七', 45.00, NULL,
        '2026-08-04 10:15:00.000000', '2026-08-05 08:10:00.000000', '商品质量问题',
        'C-DEMO-005', 'DHB-CUSTOMER-DEMO-005', '孙七', '13800000005', '陕西省西安市演示路5号',
        '顺丰速运', 'SF-RETURN-DEMO-0004', '1', '快递寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0004","OrdersNum":"DH.DEMO.20260802.0005","Status":"shipped"}',
        'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd050-0000-7000-8000-000000000003', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0005',
        'DH.DEMO.20260802.0005', 'cancelled', '演示经办人-周八', 760.00, 0.00,
        '2026-08-02 09:00:00.000000', '2026-08-02 09:20:00.000000', '客户取消退货',
        'C-DEMO-005', 'DHB-CUSTOMER-DEMO-005', '周八', '13800000006', '陕西省西安市演示路6号',
        NULL, NULL, '1', '无需寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0005","OrdersNum":"DH.DEMO.20260802.0005","Status":"cancelled"}',
        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', 1, @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_return_line (
    id, return_id, source_line_id, source_product_guid, sku_no, product_code, product_name,
    quantity, confirmed_quantity, unit_price, confirmed_price, unit_name, warehouse_no, warehouse_name,
    remark, created_at, updated_at
) VALUES
    (
        '019fd051-0000-7000-8000-000000000001', '019fd050-0000-7000-8000-000000000001',
        'DHB.DEMO.RETURN.LINE.0003', 'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'DEMO-005', '演示纸品E',
        1, NULL, 88.00, NULL, '箱', 'WH-DEMO-004', '深圳中心仓', '待客户发货退货测试数据', @seed_at, @seed_at
    ),
    (
        '019fd051-0000-7000-8000-000000000002', '019fd050-0000-7000-8000-000000000002',
        'DHB.DEMO.RETURN.LINE.0004', 'DHB-PRODUCT-DEMO-006', 'SKU-DEMO-006', 'DEMO-006', '演示日用品F',
        2, 1, 45.00, 45.00, '件', 'WH-DEMO-005', '西安分仓', '待收货退货测试数据', @seed_at, @seed_at
    ),
    (
        '019fd051-0000-7000-8000-000000000003', '019fd050-0000-7000-8000-000000000003',
        'DHB.DEMO.RETURN.LINE.0005', 'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'DEMO-005', '演示纸品E',
        5, 0, 152.00, 0.00, '箱', 'WH-DEMO-005', '西安分仓', '已取消退货测试数据', @seed_at, @seed_at
    );

-- Source: V12__dhb_order_detail_fields.sql
-- 补齐订货宝订单详情中已有的业务字段；原始报文仍由 order_source_record 完整保留。
ALTER TABLE order_order
    ADD COLUMN customer_type VARCHAR(100) NULL COMMENT '订货宝客户类型名称' AFTER synced_at,
    ADD COLUMN customer_area VARCHAR(160) NULL COMMENT '订货宝客户区域名称' AFTER customer_type,
    ADD COLUMN admin_user VARCHAR(160) NULL COMMENT '订货宝管理员名称' AFTER customer_area,
    ADD COLUMN operation_name VARCHAR(160) NULL COMMENT '订货宝操作人名称' AFTER admin_user,
    ADD COLUMN sales_person VARCHAR(160) NULL COMMENT '订货宝业务员名称' AFTER operation_name,
    ADD COLUMN sales_person_mobile VARCHAR(64) NULL COMMENT '订货宝业务员电话' AFTER sales_person,
    ADD COLUMN assistant_sales_persons VARCHAR(1000) NULL COMMENT '订货宝辅助业务员名称快照' AFTER sales_person_mobile,
    ADD COLUMN audit_at VARCHAR(32) NULL COMMENT '订货宝审核时间原值' AFTER assistant_sales_persons,
    ADD COLUMN settlement_method VARCHAR(80) NULL COMMENT '订货宝支付/结算方式' AFTER audit_at,
    ADD COLUMN goods_weight DECIMAL(18,4) NULL COMMENT '订货宝订单商品重量合计' AFTER settlement_method,
    ADD COLUMN tax_amount DECIMAL(18,4) NULL COMMENT '订货宝税费' AFTER goods_weight,
    ADD COLUMN discount_price DECIMAL(18,4) NULL COMMENT '订货宝特批优惠价' AFTER tax_amount,
    ADD COLUMN discount_total DECIMAL(18,4) NULL COMMENT '订货宝结算价' AFTER discount_price,
    ADD COLUMN freight_amount DECIMAL(18,4) NULL COMMENT '订货宝运费' AFTER discount_total,
    ADD COLUMN apply_total DECIMAL(18,4) NULL COMMENT '订货宝申请优惠合计' AFTER freight_amount,
    ADD COLUMN coupon_discounted_amount DECIMAL(18,4) NULL COMMENT '订货宝优惠券优惠金额' AFTER apply_total,
    ADD COLUMN customer_remark VARCHAR(2000) NULL COMMENT '订货宝客户留言' AFTER coupon_discounted_amount,
    ADD COLUMN internal_comment VARCHAR(2000) NULL COMMENT '订货宝内部沟通' AFTER customer_remark,
    ADD COLUMN invoice_title VARCHAR(200) NULL COMMENT '订货宝发票抬头' AFTER internal_comment,
    ADD COLUMN invoice_content VARCHAR(500) NULL COMMENT '订货宝发票内容' AFTER invoice_title,
    ADD COLUMN invoice_bank VARCHAR(200) NULL COMMENT '订货宝发票开户行' AFTER invoice_content,
    ADD COLUMN invoice_bank_account VARCHAR(100) NULL COMMENT '订货宝发票银行账号' AFTER invoice_bank,
    ADD COLUMN taxpayer_number VARCHAR(100) NULL COMMENT '订货宝发票纳税人识别号' AFTER invoice_bank_account;

ALTER TABLE order_order_line
    ADD COLUMN purchase_price DECIMAL(18,4) NULL COMMENT '订货宝进货价' AFTER remark,
    ADD COLUMN conversion_number DECIMAL(18,4) NULL COMMENT '订货宝换算关系' AFTER purchase_price,
    ADD COLUMN offer_price DECIMAL(18,4) NULL COMMENT '订货宝整箱优惠总价' AFTER conversion_number,
    ADD COLUMN actual_amount DECIMAL(18,4) NULL COMMENT '订货宝明细折后总价' AFTER offer_price,
    ADD COLUMN goods_weight DECIMAL(18,4) NULL COMMENT '订货宝明细商品重量' AFTER actual_amount,
    ADD COLUMN pre_sale VARCHAR(8) NULL COMMENT '订货宝是否预售' AFTER goods_weight,
    ADD COLUMN content_type VARCHAR(8) NULL COMMENT '订货宝商品类型' AFTER pre_sale,
    ADD COLUMN invoice_tax VARCHAR(32) NULL COMMENT '订货宝明细发票税率' AFTER content_type,
    ADD COLUMN content_percent DECIMAL(18,4) NULL COMMENT '订货宝明细折扣比例' AFTER invoice_tax;

-- Source: V13__dhb_order_customer_invoice_fields.sql
-- 补齐订货宝订单详情中的客户标签和发票类型查询字段。
ALTER TABLE order_order
    ADD COLUMN customer_tag VARCHAR(500) NULL COMMENT '订货宝客户标签' AFTER customer_type,
    ADD COLUMN invoice_type VARCHAR(80) NULL COMMENT '订货宝发票类型' AFTER invoice_title;

-- Source: V14__dhb_sync_reconciliation_audit.sql
-- 订单中心V14：将既有order_sync_run正式用于订单域同步审计，并增加逐对象完整性核对账。
-- 历史迁移只追加不改写；已有历史行按MANUAL/FULL/ALL兼容补值。

ALTER TABLE order_sync_run
    DROP CHECK ck_order_sync_run_status;

ALTER TABLE order_sync_run
    MODIFY COLUMN run_status VARCHAR(32) NOT NULL,
    ADD COLUMN connector_id CHAR(36) NULL COMMENT 'Integration订货宝连接器UUID' AFTER tenant_id,
    ADD COLUMN source_task_id CHAR(36) NULL COMMENT 'Integration同步任务UUID；SCHEDULED必填，MANUAL为空' AFTER connector_id,
    ADD COLUMN trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL或SCHEDULED' AFTER function_name,
    ADD COLUMN sync_mode VARCHAR(16) NOT NULL DEFAULT 'FULL' COMMENT 'FULL、INCREMENTAL或REPAIR' AFTER trigger_type,
    ADD COLUMN sync_scope VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '命令指定的订单域对象范围' AFTER sync_mode,
    ADD COLUMN completed_objects JSON NULL COMMENT '已完成或按策略明确跳过的对象集合' AFTER rejected_count,
    ADD COLUMN persisted_count INT NOT NULL DEFAULT 0 COMMENT '事务完成且幂等核验通过的对象数，包含未变化' AFTER completed_objects,
    ADD COLUMN changed_count INT NOT NULL DEFAULT 0 COMMENT '新增、来源变化、本地缺口修复或REPAIR强制重建对象数' AFTER persisted_count,
    ADD COLUMN skip_reason VARCHAR(1000) NULL COMMENT '策略或租约冲突跳过原因，已脱敏截断' AFTER error_message,
    ADD CONSTRAINT ck_order_sync_run_status
        CHECK (run_status IN ('RUNNING', 'SUCCEEDED', 'SUCCEEDED_WITH_WARNINGS', 'PARTIAL', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_order_sync_run_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    ADD CONSTRAINT ck_order_sync_run_source_task
        CHECK ((trigger_type = 'SCHEDULED' AND source_task_id IS NOT NULL)
            OR (trigger_type = 'MANUAL' AND source_task_id IS NULL)),
    ADD CONSTRAINT ck_order_sync_run_mode CHECK (sync_mode IN ('FULL', 'INCREMENTAL', 'REPAIR')),
    ADD UNIQUE KEY uk_order_sync_run_scope (id, tenant_id, connector_id),
    ADD KEY idx_order_sync_run_connector_time (tenant_id, connector_id, started_at),
    ADD KEY idx_order_sync_run_source_task_time (tenant_id, source_task_id, started_at);

ALTER TABLE order_dhb_sync_checkpoint
    ADD COLUMN last_full_success_at DATETIME(6) NULL
        COMMENT '最近一次FULL完整对账的窗口终点，UTC' AFTER last_success_at;

-- 不从旧last_success_at回填：历史值同时包含FULL和INCREMENTAL，不能作为FULL成功证据。
-- 保持NULL会使升级后首轮调度强制FULL，之后再与增量游标分别推进。

-- 兼容早期实现可能遗留的无finished_at终态，再启用强终态约束。
UPDATE order_sync_run
SET finished_at = started_at
WHERE run_status <> 'RUNNING' AND finished_at IS NULL;

ALTER TABLE order_sync_run
    ADD CONSTRAINT ck_order_sync_run_terminal_time
        CHECK (run_status = 'RUNNING' OR finished_at IS NOT NULL),
    ADD CONSTRAINT ck_order_sync_run_skip_reason
        CHECK (run_status <> 'SKIPPED'
            OR (finished_at IS NOT NULL AND skip_reason IS NOT NULL AND CHAR_LENGTH(TRIM(skip_reason)) > 0));

CREATE TABLE order_sync_reconciliation (
    id                         CHAR(36)      NOT NULL COMMENT '核对项UUID',
    run_id                     CHAR(36)      NOT NULL COMMENT 'order_sync_run.id',
    tenant_id                  VARCHAR(64)   NOT NULL COMMENT '可信租户ID，冗余用于强制隔离查询',
    connector_id               CHAR(36)      NOT NULL COMMENT 'Integration订货宝连接器UUID',
    object_type                VARCHAR(32)   NOT NULL COMMENT 'ORDER、SHIPMENT、SHIPMENT_LOGISTICS、RETURN、RECEIPT或PAYMENT',
    stage_status               VARCHAR(16)   NOT NULL COMMENT 'RUNNING、COLLECTED、PERSISTED、SUCCEEDED、FAILED或SKIPPED',
    expected_count             BIGINT        NULL COMMENT '来源首个分页声明总数；未收到有效分页时为空',
    fetched_count              BIGINT        NOT NULL DEFAULT 0 COMMENT '已成功取得的列表或逐单对象数',
    distinct_count             BIGINT        NOT NULL DEFAULT 0 COMMENT '按来源业务键去重后的对象数',
    raw_landed_count           BIGINT        NOT NULL DEFAULT 0 COMMENT 'Integration已完成对象Raw Landing后返回的数量',
    raw_detail_landed_count    BIGINT        NOT NULL DEFAULT 0 COMMENT 'Integration已完成详情Raw Landing后返回的数量',
    detail_expected_count      BIGINT        NOT NULL DEFAULT 0 COMMENT '应补拉详情对象数',
    detail_succeeded_count     BIGINT        NOT NULL DEFAULT 0 COMMENT '详情成功数',
    detail_failed_count        BIGINT        NOT NULL DEFAULT 0 COMMENT '详情失败数',
    persisted_count            BIGINT        NOT NULL DEFAULT 0 COMMENT '事务完成且幂等核验通过对象数，包含未变化',
    changed_count              BIGINT        NOT NULL DEFAULT 0 COMMENT '新增、来源变化、本地缺口修复或REPAIR强制重建对象数',
    failure_reason             VARCHAR(1000) NULL COMMENT '失败或策略跳过原因，已脱敏截断',
    started_at                 DATETIME(6)   NOT NULL COMMENT '本次run开始时间，UTC',
    finished_at                DATETIME(6)   NULL COMMENT '该对象终态时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT fk_order_sync_reconciliation_run
        FOREIGN KEY (run_id, tenant_id, connector_id)
        REFERENCES order_sync_run(id, tenant_id, connector_id)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT ck_order_sync_reconciliation_status
        CHECK (stage_status IN ('RUNNING', 'COLLECTED', 'PERSISTED', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_order_sync_reconciliation_terminal_time
        CHECK (stage_status IN ('RUNNING', 'COLLECTED', 'PERSISTED') OR finished_at IS NOT NULL),
    CONSTRAINT ck_order_sync_reconciliation_terminal_reason
        CHECK (stage_status NOT IN ('FAILED', 'SKIPPED')
            OR (failure_reason IS NOT NULL AND CHAR_LENGTH(TRIM(failure_reason)) > 0)),
    UNIQUE KEY uk_order_sync_reconciliation_object (run_id, object_type),
    KEY idx_order_sync_reconciliation_parent (run_id, tenant_id, connector_id),
    KEY idx_order_sync_reconciliation_tenant (tenant_id, started_at),
    KEY idx_order_sync_reconciliation_status (tenant_id, stage_status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='订货宝订单域来源Raw到本地持久化逐对象核对账';

-- Source: V15__dhb_source_presence.sql
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

-- Source: V16__internal_sales_order_baseline.sql
-- Order 自研业务基线：销售订单、订单明细、回款记录。
-- 本迁移只定义我们的销售订单流程；订货宝订单后续通过 Integration 映射到本业务模型。

CREATE TABLE order_sales_order (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    order_no                 VARCHAR(50)    NOT NULL COMMENT '销售订单号，由Order编码规则生成',
    customer_id              BIGINT(20)     NOT NULL COMMENT 'CRM客户ID，跨服务引用',
    customer_code_snapshot   VARCHAR(50)    NULL COMMENT '下单时客户编号快照',
    customer_name_snapshot   VARCHAR(200)   NOT NULL COMMENT '下单时客户名称快照',
    contact_name_snapshot    VARCHAR(100)   NULL COMMENT '联系人快照',
    contact_phone_snapshot   VARCHAR(50)    NULL COMMENT '联系电话快照',
    region_code              VARCHAR(64)    NULL COMMENT '客户归属地区，关联 REGION 字典项',
    owner_sales_user_id      VARCHAR(64)    NULL COMMENT '归属销售用户ID',
    owner_sales_name         VARCHAR(100)   NULL COMMENT '归属销售名称快照',
    order_date               DATETIME(6)    NOT NULL COMMENT '销售日期',
    order_status_code        VARCHAR(64)    NOT NULL DEFAULT 'DRAFT' COMMENT '销售订单状态，关联 SALES_ORDER_STATUS 字典项',
    order_type_code          VARCHAR(64)    NULL COMMENT '订单类型，关联 ORDER_TYPE 字典项',
    payment_method_code      VARCHAR(64)    NULL COMMENT '付款方式，关联 PAYMENT_METHOD 字典项',
    payment_status_code      VARCHAR(64)    NOT NULL DEFAULT 'UNPAID' COMMENT '付款状态，关联 PAYMENT_STATUS 字典项',
    outbound_status_code     VARCHAR(64)    NOT NULL DEFAULT 'PENDING' COMMENT '出库状态，关联 OUTBOUND_STATUS 字典项',
    total_quantity           DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '订单总数量，由明细汇总',
    original_amount          DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '原价小计',
    discount_rate            DECIMAL(10,6)  NULL COMMENT '优惠比例',
    discount_amount          DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '优惠金额',
    payable_amount           DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '应收金额',
    paid_amount              DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '已收金额',
    unpaid_amount            DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '待收金额',
    remark                   VARCHAR(1000)  NULL COMMENT '备注',
    revision                 INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by               VARCHAR(50)    NULL COMMENT '创建人',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by               VARCHAR(50)    NULL COMMENT '更新人',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sales_order_no (tenant_id, order_no),
    KEY idx_order_sales_customer (tenant_id, customer_id),
    KEY idx_order_sales_region (tenant_id, region_code, order_date),
    KEY idx_order_sales_owner (tenant_id, owner_sales_user_id, order_date),
    KEY idx_order_sales_status (tenant_id, order_status_code, order_date),
    KEY idx_order_sales_payment (tenant_id, payment_status_code, order_date),
    KEY idx_order_sales_outbound (tenant_id, outbound_status_code, order_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售订单主表';

CREATE TABLE order_sales_order_line (
    id                          BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                   VARCHAR(64)    NOT NULL COMMENT '租户ID',
    order_id                    BIGINT(20)     NOT NULL COMMENT '销售订单ID',
    line_no                     INT            NOT NULL COMMENT '行号',
    product_id                  BIGINT(20)     NOT NULL COMMENT 'ERP商品ID，跨服务引用',
    product_variant_id          BIGINT(20)     NOT NULL COMMENT 'ERP商品规格ID，跨服务引用',
    product_code_snapshot       VARCHAR(128)   NULL COMMENT '商品编码快照',
    sku_code_snapshot           VARCHAR(128)   NULL COMMENT 'SKU编码快照',
    product_name_snapshot       VARCHAR(200)   NOT NULL COMMENT '商品名称快照',
    specification_snapshot      VARCHAR(500)   NULL COMMENT '规格快照',
    unit_code                   VARCHAR(64)    NOT NULL COMMENT '订货单位，关联 PRODUCT_UNIT 字典项',
    quantity                    DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '订货数量',
    unit_price                  DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '成交单价',
    discount_rate               DECIMAL(10,6)  NULL COMMENT '明细优惠比例',
    discount_amount             DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '明细优惠金额',
    line_amount                 DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '明细应收金额',
    remark                      VARCHAR(1000)  NULL COMMENT '备注',
    created_time                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time                DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                     INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_order_sales_line_no (tenant_id, order_id, line_no),
    CONSTRAINT fk_order_sales_line_order FOREIGN KEY (order_id) REFERENCES order_sales_order (id),
    KEY idx_order_sales_line_product (tenant_id, product_id, product_variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售订单明细表';

CREATE TABLE order_payment_record (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    payment_no               VARCHAR(50)    NOT NULL COMMENT '回款单号，由Order编码规则生成',
    order_id                 BIGINT(20)     NOT NULL COMMENT '销售订单ID',
    collector_user_id        VARCHAR(64)    NULL COMMENT '回款人用户ID',
    collector_name_snapshot  VARCHAR(100)   NULL COMMENT '回款人名称快照',
    payment_time             DATETIME(6)    NOT NULL COMMENT '回款时间',
    payment_method_code      VARCHAR(64)    NULL COMMENT '付款方式，关联 PAYMENT_METHOD 字典项',
    paid_amount              DECIMAL(24,6)  NOT NULL COMMENT '回款金额',
    voucher_keys_json        JSON           NULL COMMENT '付款凭证COS key数组',
    remark                   VARCHAR(1000)  NULL COMMENT '备注',
    revision                 INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by               VARCHAR(50)    NULL COMMENT '创建人',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by               VARCHAR(50)    NULL COMMENT '更新人',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_payment_no (tenant_id, payment_no),
    CONSTRAINT fk_order_payment_order FOREIGN KEY (order_id) REFERENCES order_sales_order (id),
    KEY idx_order_payment_order (tenant_id, order_id, payment_time),
    KEY idx_order_payment_collector (tenant_id, collector_user_id, payment_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售订单回款记录表';

-- Source: V17__drop_legacy_dhb_order_tables.sql
-- 订货宝订单同步已收口到 Integration 编排器：Integration 负责 Raw Landing、映射和对账，
-- Order 只保留自研销售订单业务表。删除旧 Order 内部订货宝快照、导入和审计表。
-- 旧表物理删除由 docs/LEGACY_DHB_TABLE_CLEANUP.sql 在确认备份和切流后使用 DBA 账号清理。
-- 日常 Flyway 迁移账号不授予 DROP 权限，自动迁移只记录新模型切换完成。
SELECT 1 AS legacy_order_projection_cleanup_deferred;

-- Source: V18__sales_order_owner_staff_code.sql
-- Order V18：销售订单归属人员改为 IAM 员工业务编码。
--
-- 业务口径：
-- 1. owner_staff_code 是跨 CRM、Order、IAM 关联人员的主字段。
-- 2. owner_sales_user_id 仅保留兼容旧接口入参和旧数据，不再作为新同步的关联依据。
-- 3. owner_staff_name_snapshot 记录订单创建或同步时的员工姓名快照。

ALTER TABLE order_sales_order
    ADD COLUMN owner_staff_code VARCHAR(50) NULL COMMENT '归属销售人员员工编码，来自IAM员工中心'
        AFTER owner_sales_name,
    ADD COLUMN owner_staff_name_snapshot VARCHAR(100) NULL COMMENT '归属销售人员姓名快照'
        AFTER owner_staff_code;

CREATE INDEX idx_order_sales_owner_staff
    ON order_sales_order (tenant_id, owner_staff_code, order_date);

ALTER TABLE order_sales_order
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_staff_code';

-- Source: V19__internal_sales_shipment_baseline.sql
-- Order V19：自研销售发货单基线。
--
-- 业务口径：
-- 1. 销售订单负责客户下单与应收，ERP 出库单负责库存扣减。
-- 2. 销售发货单负责客户侧履约、物流和发货数量，可由人工创建或订货宝发货单同步映射。
-- 3. 所有关联优先保存我们自己的业务编号和业务 ID，订货宝来源 ID 进入同步映射表，不进入业务主表。

CREATE TABLE order_sales_shipment (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    shipment_no              VARCHAR(50)    NOT NULL COMMENT '销售发货单号，由Order编码规则生成',
    sales_order_id           BIGINT(20)     NULL COMMENT '销售订单ID，关联order_sales_order.id',
    sales_order_no_snapshot  VARCHAR(50)    NULL COMMENT '销售订单号快照',
    customer_id              BIGINT(20)     NULL COMMENT '客户ID，关联CRM客户主表ID',
    customer_code_snapshot   VARCHAR(50)    NULL COMMENT '客户编号快照',
    customer_name_snapshot   VARCHAR(200)   NULL COMMENT '客户名称快照',
    contact_phone_snapshot   VARCHAR(50)    NULL COMMENT '联系电话快照',
    region_code              VARCHAR(64)    NULL COMMENT '客户归属地区编码',
    owner_staff_code         VARCHAR(50)    NULL COMMENT '归属销售人员员工编码，来自IAM员工中心',
    warehouse_id             BIGINT(20)     NULL COMMENT '发货仓库ID，关联ERP仓库',
    stock_out_order_id       BIGINT(20)     NULL COMMENT 'ERP销售出库单ID',
    stock_out_no             VARCHAR(50)    NULL COMMENT 'ERP销售出库单号快照',
    shipment_status_code     VARCHAR(64)    NOT NULL DEFAULT 'CREATED' COMMENT '发货单状态，关联ORDER/SALES_SHIPMENT_STATUS',
    logistics_company        VARCHAR(120)   NULL COMMENT '物流公司或配送方名称',
    tracking_no              VARCHAR(120)   NULL COMMENT '物流单号/配送单号',
    ship_time                DATETIME(6)    NULL COMMENT '发货时间',
    total_quantity           DECIMAL(20,6)  NOT NULL DEFAULT 0 COMMENT '发货总数量',
    remark                   VARCHAR(1000)  NULL COMMENT '备注',
    revision                 INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by               VARCHAR(50)    NULL COMMENT '创建人',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by               VARCHAR(50)    NULL COMMENT '更新人',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_sales_shipment_no (tenant_id, shipment_no),
    KEY idx_order_sales_shipment_order (tenant_id, sales_order_id),
    KEY idx_order_sales_shipment_order_no (tenant_id, sales_order_no_snapshot),
    KEY idx_order_sales_shipment_customer (tenant_id, customer_name_snapshot),
    KEY idx_order_sales_shipment_status (tenant_id, shipment_status_code, ship_time),
    KEY idx_order_sales_shipment_tracking (tenant_id, tracking_no),
    KEY idx_order_sales_shipment_owner_staff (tenant_id, owner_staff_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Order销售发货单主表';

CREATE TABLE order_sales_shipment_line (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    shipment_id                BIGINT(20)     NOT NULL COMMENT '销售发货单ID，关联order_sales_shipment.id',
    sales_order_line_id        BIGINT(20)     NULL COMMENT '销售订单明细ID，关联order_sales_order_line.id',
    line_no                    INT            NOT NULL COMMENT '行号',
    product_id                 BIGINT(20)     NULL COMMENT 'ERP商品ID',
    product_variant_id         BIGINT(20)     NULL COMMENT 'ERP商品规格ID',
    product_code_snapshot      VARCHAR(50)    NULL COMMENT '商品编码快照',
    sku_code_snapshot          VARCHAR(50)    NULL COMMENT 'SKU编码快照',
    product_name_snapshot      VARCHAR(200)   NULL COMMENT '商品名称快照',
    specification_snapshot     VARCHAR(500)   NULL COMMENT '规格快照',
    unit_code                  VARCHAR(64)    NULL COMMENT '单位编码，关联COMMON/PRODUCT_UNIT',
    shipped_quantity           DECIMAL(20,6)  NOT NULL DEFAULT 0 COMMENT '本次发货数量',
    remark                     VARCHAR(1000)  NULL COMMENT '备注',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_order_sales_shipment_line_head (tenant_id, shipment_id, line_no),
    KEY idx_order_sales_shipment_line_order (tenant_id, sales_order_line_id),
    KEY idx_order_sales_shipment_line_product (tenant_id, product_id, product_variant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Order销售发货单明细表';

-- Source: V20__sales_payment_record_staff_code.sql
-- 销售回款记录补齐业务快照与人员编码。
-- 回款单属于 Order 自研业务表，订货宝收款单只能同步映射到本表。

ALTER TABLE order_payment_record
    ADD COLUMN sales_order_no_snapshot VARCHAR(50) NULL COMMENT '销售订单号快照' AFTER order_id,
    ADD COLUMN customer_id BIGINT(20) NULL COMMENT 'CRM客户ID，跨服务引用' AFTER sales_order_no_snapshot,
    ADD COLUMN customer_code_snapshot VARCHAR(50) NULL COMMENT '客户编号快照' AFTER customer_id,
    ADD COLUMN customer_name_snapshot VARCHAR(200) NULL COMMENT '客户名称快照' AFTER customer_code_snapshot,
    ADD COLUMN collector_staff_code VARCHAR(50) NULL COMMENT '回款人员工编码，关联IAM员工中心staff_code' AFTER collector_user_id;

ALTER TABLE order_payment_record
    ADD KEY idx_order_payment_staff_code (tenant_id, collector_staff_code, payment_time),
    ADD KEY idx_order_payment_customer (tenant_id, customer_id, payment_time);

-- Source: V21__sales_refund_record_baseline.sql
-- Order V21：自研销售退款记录。
--
-- 业务口径：
-- 1. 销售退款是 Order 域业务单据，订货宝 getPaymentList 只能作为来源映射。
-- 2. 退款不写入销售回款表负数，单独建表，便于对账、审计和后续关联退货单。
-- 3. 销售订单实收金额按“回款合计 - 退款合计”重算。

CREATE TABLE order_refund_record (
    id                       BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                VARCHAR(64)    NOT NULL COMMENT '租户ID',
    refund_no                VARCHAR(50)    NOT NULL COMMENT '退款单号，由Order编码规则生成',
    order_id                 BIGINT(20)     NOT NULL COMMENT '销售订单ID',
    sales_order_no_snapshot  VARCHAR(50)    NULL COMMENT '销售订单号快照',
    customer_id              BIGINT(20)     NULL COMMENT 'CRM客户ID，跨服务引用',
    customer_code_snapshot   VARCHAR(50)    NULL COMMENT '客户编号快照',
    customer_name_snapshot   VARCHAR(200)   NULL COMMENT '客户名称快照',
    refund_staff_code        VARCHAR(50)    NULL COMMENT '退款经办人员工编码，关联IAM员工中心staff_code',
    refund_staff_name_snapshot VARCHAR(100) NULL COMMENT '退款经办人员名称快照',
    refund_time              DATETIME(6)    NOT NULL COMMENT '退款时间',
    refund_method_code       VARCHAR(64)    NULL COMMENT '退款方式，关联PAYMENT_METHOD字典项',
    refund_status_code       VARCHAR(64)    NOT NULL DEFAULT 'CONFIRMED' COMMENT '退款状态，关联ORDER/SALES_REFUND_STATUS',
    refund_amount            DECIMAL(24,6)  NOT NULL COMMENT '退款金额',
    voucher_keys_json        JSON           NULL COMMENT '退款凭证COS key数组',
    remark                   VARCHAR(1000)  NULL COMMENT '备注',
    revision                 INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by               VARCHAR(50)    NULL COMMENT '创建人',
    created_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by               VARCHAR(50)    NULL COMMENT '更新人',
    updated_time             DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                  INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_refund_no (tenant_id, refund_no),
    CONSTRAINT fk_order_refund_order FOREIGN KEY (order_id) REFERENCES order_sales_order (id),
    KEY idx_order_refund_order (tenant_id, order_id, refund_time),
    KEY idx_order_refund_staff_code (tenant_id, refund_staff_code, refund_time),
    KEY idx_order_refund_customer (tenant_id, customer_id, refund_time),
    KEY idx_order_refund_status (tenant_id, refund_status_code, refund_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售订单退款记录表';

-- Source: V22__order_fund_document_baseline.sql
-- Order V22：自研资金收付款单。
--
-- 业务口径：
-- 1. 资金收付款单是我方业务单据，订货宝 getReceiptsList/getPaymentList 只能作为来源数据。
-- 2. 收款单和付款单按 direction_code 区分。订货宝付款单通常表示客户预存款/余额被订单抵扣的消费流水，不等同于销售退款。
-- 3. 需要影响销售订单收款状态时，应由明确的订单收款业务写入销售回款记录；资金付款单只做余额抵扣、支出凭证和对账依据。

CREATE TABLE order_fund_document (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    document_no                VARCHAR(50)    NOT NULL COMMENT '资金单据编号，由Order编码规则生成',
    direction_code             VARCHAR(20)    NOT NULL COMMENT '资金方向：RECEIPT收款，PAYMENT付款',
    related_order_id           BIGINT(20)     NULL COMMENT '关联销售订单ID，可为空；为空表示非订单收付款',
    sales_order_no_snapshot    VARCHAR(50)    NULL COMMENT '销售订单号快照',
    customer_id                BIGINT(20)     NULL COMMENT 'CRM客户ID，跨服务引用',
    customer_code_snapshot     VARCHAR(50)    NULL COMMENT '客户编号快照',
    customer_name_snapshot     VARCHAR(200)   NULL COMMENT '客户名称快照',
    counterparty_type_code     VARCHAR(40)    NULL COMMENT '往来方类型：CUSTOMER客户，SUPPLIER供应商，OTHER其他',
    counterparty_code_snapshot VARCHAR(80)    NULL COMMENT '往来方编号快照',
    counterparty_name_snapshot VARCHAR(200)   NULL COMMENT '往来方名称快照',
    handler_staff_code         VARCHAR(50)    NULL COMMENT '经办人员工编码，关联IAM员工中心staff_code',
    handler_staff_name_snapshot VARCHAR(100)  NULL COMMENT '经办人员名称快照',
    occurred_time              DATETIME(6)    NOT NULL COMMENT '收付款发生时间',
    settlement_method_code     VARCHAR(64)    NULL COMMENT '结算方式，关联ORDER/PAYMENT_METHOD字典项',
    business_type_code         VARCHAR(64)    NULL COMMENT '业务类型，关联ORDER/FUND_DOCUMENT_BUSINESS_TYPE字典项；无法映射时为OTHER',
    document_status_code       VARCHAR(64)    NOT NULL DEFAULT 'CONFIRMED' COMMENT '单据状态，关联ORDER/FUND_DOCUMENT_STATUS字典项',
    amount                     DECIMAL(24,6)  NOT NULL COMMENT '收付款金额',
    voucher_keys_json          JSON           NULL COMMENT '凭证COS key数组',
    remark                     VARCHAR(1000)  NULL COMMENT '备注',
    revision                   INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by                 VARCHAR(50)    NULL COMMENT '创建人',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by                 VARCHAR(50)    NULL COMMENT '更新人',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_fund_document_no (tenant_id, document_no),
    KEY idx_order_fund_document_direction (tenant_id, direction_code, occurred_time),
    KEY idx_order_fund_document_order (tenant_id, related_order_id, occurred_time),
    KEY idx_order_fund_document_customer (tenant_id, customer_id, occurred_time),
    KEY idx_order_fund_document_handler (tenant_id, handler_staff_code, occurred_time),
    KEY idx_order_fund_document_status (tenant_id, document_status_code, occurred_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='资金收付款单表';

-- Source: V23__sales_order_source_document.sql
-- 销售订单保留来源平台单号，供业务人员按订货宝订单号搜索和对账。
-- Integration 外部对象映射仍是幂等与跨系统定位的权威来源。

ALTER TABLE order_sales_order
    ADD COLUMN source_system_code VARCHAR(32) NULL COMMENT '来源系统编码；订货宝同步为DINGHUOBAO，手工订单为空' AFTER order_no,
    ADD COLUMN source_order_no VARCHAR(80) NULL COMMENT '来源平台订单号；用于业务对账展示和搜索，不作为集成幂等唯一依据' AFTER source_system_code,
    ADD KEY idx_order_sales_source_order (tenant_id, source_system_code, source_order_no);

-- Source: V24__sales_order_payment_and_shipment_time.sql
ALTER TABLE order_sales_order
    ADD COLUMN payment_time DATETIME(6) NULL COMMENT '付款时间，来自关联资金付款单',
    ADD COLUMN shipment_time DATETIME(6) NULL COMMENT '发货时间，来自关联销售发货单或销售出库确认',
    ADD KEY idx_order_sales_payment_time (tenant_id, payment_time),
    ADD KEY idx_order_sales_shipment_time (tenant_id, shipment_time);

UPDATE order_sales_order o
JOIN (
    SELECT tenant_id, related_order_id, MAX(occurred_time) AS payment_time
    FROM order_fund_document
    WHERE deleted = 0
      AND direction_code = 'PAYMENT'
      AND related_order_id IS NOT NULL
      AND document_status_code <> 'CANCELLED'
    GROUP BY tenant_id, related_order_id
) p ON p.tenant_id = o.tenant_id AND p.related_order_id = o.id
SET o.payment_time = p.payment_time
WHERE o.deleted = 0;

UPDATE order_sales_order o
JOIN (
    SELECT tenant_id, sales_order_id, MAX(ship_time) AS shipment_time
    FROM order_sales_shipment
    WHERE deleted = 0
      AND sales_order_id IS NOT NULL
      AND ship_time IS NOT NULL
      AND shipment_status_code <> 'CANCELLED'
    GROUP BY tenant_id, sales_order_id
) s ON s.tenant_id = o.tenant_id AND s.sales_order_id = o.id
SET o.shipment_time = s.shipment_time
WHERE o.deleted = 0;

-- Source: V25__order_line_audit_columns.sql
-- Order V25：补齐销售订单/发货单明细的统一本地审计字段。
-- 来源字段 source_* 不属于本地审计字段，本迁移不处理旧订货宝投影表。

ALTER TABLE order_sales_order_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE order_sales_order_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE order_sales_order_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

ALTER TABLE order_sales_shipment_line ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER remark;
ALTER TABLE order_sales_shipment_line ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE order_sales_shipment_line ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;

-- Source: V26__order_fund_document_source_payment_fields.sql
-- Order V26：资金收付款单保留订货宝来源和支付账户字段。
--
-- V24 曾按 order_fund_document.direction_code = PAYMENT 回填销售订单 payment_time。
-- 订货宝付款单常见语义是客户预存款/余额消费流水，不应默认等同销售订单回款；
-- 销售订单回款时间以明确的 order_payment_record 为准。

ALTER TABLE order_fund_document
    ADD COLUMN source_document_no VARCHAR(80) NULL COMMENT '来源资金单号，如订货宝FR/FP单号' AFTER amount,
    ADD COLUMN source_order_no VARCHAR(80) NULL COMMENT '来源关联订单号，如订货宝DH订单号' AFTER source_document_no,
    ADD COLUMN payment_serial_no VARCHAR(120) NULL COMMENT '支付流水号' AFTER source_order_no,
    ADD COLUMN bank_account_name VARCHAR(200) NULL COMMENT '开户名称/账户名称' AFTER payment_serial_no,
    ADD COLUMN bank_name VARCHAR(200) NULL COMMENT '开户银行' AFTER bank_account_name,
    ADD COLUMN bank_account_no VARCHAR(120) NULL COMMENT '收付款账号' AFTER bank_name,
    ADD COLUMN submitted_at DATETIME(6) NULL COMMENT '来源提交时间' AFTER bank_account_no,
    ADD COLUMN confirmed_at DATETIME(6) NULL COMMENT '来源审核确认时间' AFTER submitted_at,
    ADD COLUMN source_attachment_keys_json JSON NULL COMMENT '来源附件标识或URL数组' AFTER confirmed_at,
    ADD KEY idx_order_fund_document_source_no (tenant_id, source_document_no),
    ADD KEY idx_order_fund_document_serial_no (tenant_id, payment_serial_no),
    ADD KEY idx_order_fund_document_confirmed (tenant_id, confirmed_at);

UPDATE order_sales_order o
LEFT JOIN (
    SELECT tenant_id, order_id, MAX(payment_time) AS payment_time
    FROM order_payment_record
    WHERE deleted = 0
    GROUP BY tenant_id, order_id
) p ON p.tenant_id = o.tenant_id AND p.order_id = o.id
SET o.payment_time = p.payment_time
WHERE o.deleted = 0;

-- Source: V27__sales_order_source_status.sql
-- 销售订单保存外部来源订单状态，供订货宝状态展示、筛选和对账使用。
-- 该字段不参与我方销售订单人工流程状态机。
ALTER TABLE order_sales_order
    ADD COLUMN source_status_code VARCHAR(64) NULL COMMENT '来源平台订单状态原值，如订货宝DHB_ORDER_STATUS'
        AFTER source_order_no,
    ADD KEY idx_order_sales_source_status (tenant_id, source_system_code, source_status_code);

-- Source: V28__sales_order_source_creator_and_sync_audit.sql
-- Order V28：销售订单保留订货宝制单人来源字段，并统一历史同步审计口径。
--
-- 业务口径：
-- 1. source_creator_* 表示来源平台的业务制单人，可匹配到我方员工编码。
-- 2. created_by/updated_by 表示谁写入了我方系统；订货宝同步统一写 SYSTEM，前端枚举展示为“系统同步”。

ALTER TABLE order_sales_order
    ADD COLUMN source_creator_id VARCHAR(80) NULL COMMENT '来源平台制单人ID，如订货宝员工账号ID' AFTER source_status_code,
    ADD COLUMN source_creator_staff_code VARCHAR(50) NULL COMMENT '来源制单人匹配到的我方员工编码' AFTER source_creator_id,
    ADD COLUMN source_creator_name VARCHAR(100) NULL COMMENT '来源制单人名称或我方员工姓名快照' AFTER source_creator_staff_code,
    ADD KEY idx_order_sales_source_creator_staff (tenant_id, source_creator_staff_code, order_date);

UPDATE order_sales_order
SET created_by = CASE
        WHEN created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE created_by
    END,
    updated_by = CASE
        WHEN updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE updated_by
    END
WHERE source_system_code = 'DINGHUOBAO'
  AND (
      created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_sales_order_line l
JOIN order_sales_order o ON o.tenant_id = l.tenant_id AND o.id = l.order_id
SET l.created_by = CASE
        WHEN l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.created_by
    END,
    l.updated_by = CASE
        WHEN l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.updated_by
    END
WHERE o.source_system_code = 'DINGHUOBAO'
  AND (
      l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_payment_record p
JOIN order_sales_order o ON o.tenant_id = p.tenant_id AND o.id = p.order_id
SET p.created_by = CASE
        WHEN p.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE p.created_by
    END,
    p.updated_by = CASE
        WHEN p.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE p.updated_by
    END
WHERE o.source_system_code = 'DINGHUOBAO'
  AND (
      p.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR p.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_refund_record r
JOIN order_sales_order o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
SET r.created_by = CASE
        WHEN r.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE r.created_by
    END,
    r.updated_by = CASE
        WHEN r.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE r.updated_by
    END
WHERE o.source_system_code = 'DINGHUOBAO'
  AND (
      r.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR r.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_sales_shipment s
LEFT JOIN order_sales_order o ON o.tenant_id = s.tenant_id AND o.id = s.sales_order_id
SET s.created_by = CASE
        WHEN s.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE s.created_by
    END,
    s.updated_by = CASE
        WHEN s.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE s.updated_by
    END
WHERE (o.source_system_code = 'DINGHUOBAO'
        OR s.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
        OR s.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system'))
  AND (
      s.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR s.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_sales_shipment_line l
JOIN order_sales_shipment s ON s.tenant_id = l.tenant_id AND s.id = l.shipment_id
LEFT JOIN order_sales_order o ON o.tenant_id = s.tenant_id AND o.id = s.sales_order_id
SET l.created_by = CASE
        WHEN l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.created_by
    END,
    l.updated_by = CASE
        WHEN l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.updated_by
    END
WHERE (o.source_system_code = 'DINGHUOBAO'
        OR s.created_by = 'SYSTEM'
        OR s.updated_by = 'SYSTEM')
  AND (
      l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_fund_document
SET created_by = CASE
        WHEN created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE created_by
    END,
    updated_by = CASE
        WHEN updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE updated_by
    END
WHERE (source_document_no IS NOT NULL OR source_order_no IS NOT NULL)
  AND (
      created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

-- Source: V29__order_leaf_external_source_identity.sql
ALTER TABLE order_sales_shipment
    ADD COLUMN connector_id CHAR(36) NULL COMMENT '外部连接器ID' AFTER shipment_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '领域来源系统编码，订货宝统一为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '外部来源发货单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_order_sales_shipment_source_identity
        (tenant_id, connector_id, source_system_code, source_document_no);

ALTER TABLE order_payment_record
    ADD COLUMN connector_id CHAR(36) NULL COMMENT '外部连接器ID' AFTER payment_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '领域来源系统编码，订货宝统一为DINGHUOBAO' AFTER connector_id,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '外部来源收款单号' AFTER source_system_code,
    ADD UNIQUE KEY uk_order_payment_record_source_identity
        (tenant_id, connector_id, source_system_code, source_document_no);

ALTER TABLE order_fund_document
    ADD COLUMN connector_id CHAR(36) NULL COMMENT '外部连接器ID' AFTER document_no,
    ADD COLUMN source_system_code VARCHAR(64) NULL COMMENT '领域来源系统编码，订货宝统一为DINGHUOBAO' AFTER connector_id,
    ADD KEY idx_order_fund_document_source_identity
        (tenant_id, connector_id, source_system_code, source_document_no);

UPDATE order_fund_document
SET source_system_code = 'DINGHUOBAO'
WHERE deleted = 0
  AND source_system_code IS NULL
  AND (source_document_no IS NOT NULL OR source_order_no IS NOT NULL OR payment_serial_no IS NOT NULL);

UPDATE order_sales_shipment s
JOIN order_sales_order o
  ON o.tenant_id = s.tenant_id
 AND o.id = s.sales_order_id
 AND o.deleted = 0
SET s.source_system_code = o.source_system_code
WHERE s.deleted = 0
  AND s.source_system_code IS NULL
  AND o.source_system_code = 'DINGHUOBAO'
  AND (s.created_by = 'SYSTEM' OR s.updated_by = 'SYSTEM');

UPDATE order_payment_record p
JOIN order_sales_order o
  ON o.tenant_id = p.tenant_id
 AND o.id = p.order_id
 AND o.deleted = 0
SET p.source_system_code = o.source_system_code
WHERE p.deleted = 0
  AND p.source_system_code IS NULL
  AND o.source_system_code = 'DINGHUOBAO'
  AND (p.created_by = 'SYSTEM' OR p.updated_by = 'SYSTEM');

-- Source: V30__order_sales_employee_reference_fields.sql
-- Order 销售单归属人员字段从 staff 语义收口到 HR employee 语义。
-- 人员主档归 HR；Order 只保存员工编码和姓名快照，不再把业务人员字段指向 IAM 员工中心。

ALTER TABLE order_sales_order
    DROP INDEX idx_order_sales_owner_staff;

ALTER TABLE order_sales_order
    CHANGE COLUMN owner_staff_code owner_employee_code VARCHAR(50) NULL
        COMMENT '归属销售人员工编码，来自HR员工主档',
    CHANGE COLUMN owner_staff_name_snapshot owner_employee_name_snapshot VARCHAR(100) NULL
        COMMENT '归属销售人员姓名快照';

CREATE INDEX idx_order_sales_order_owner_employee
    ON order_sales_order (tenant_id, owner_employee_code, order_date);

ALTER TABLE order_sales_order
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_employee_code';

ALTER TABLE order_sales_shipment
    DROP INDEX idx_order_sales_shipment_owner_staff;

ALTER TABLE order_sales_shipment
    CHANGE COLUMN owner_staff_code owner_employee_code VARCHAR(50) NULL
        COMMENT '归属销售人员工编码，来自HR员工主档';

CREATE INDEX idx_order_sales_shipment_owner_employee
    ON order_sales_shipment (tenant_id, owner_employee_code);

-- Source: V31__sales_order_data_quality_for_feishu_import.sql
-- 飞书历史订单允许先落为草稿，缺失的内部映射由业务人员在销售订单页面补齐。

ALTER TABLE order_sales_order
    ADD COLUMN data_quality_status_code VARCHAR(64) NOT NULL DEFAULT 'COMPLETE'
        COMMENT '数据质量状态：COMPLETE完整，NEEDS_REVIEW待业务补齐' AFTER source_creator_name,
    ADD COLUMN data_quality_message VARCHAR(1000) NULL
        COMMENT '数据质量说明，记录导入后待补齐字段' AFTER data_quality_status_code,
    MODIFY COLUMN customer_id BIGINT(20) NULL COMMENT 'CRM客户ID，跨服务引用；飞书导入草稿允许待补齐',
    MODIFY COLUMN customer_name_snapshot VARCHAR(200) NULL COMMENT '下单时客户名称快照；飞书导入草稿允许待补齐';

ALTER TABLE order_sales_order_line
    MODIFY COLUMN product_id BIGINT(20) NULL COMMENT 'ERP商品ID，跨服务引用；飞书导入草稿允许待补齐',
    MODIFY COLUMN product_variant_id BIGINT(20) NULL COMMENT 'ERP商品规格ID，跨服务引用；飞书导入草稿允许待补齐',
    MODIFY COLUMN product_name_snapshot VARCHAR(200) NULL COMMENT '商品名称快照；飞书导入草稿允许待补齐',
    MODIFY COLUMN unit_code VARCHAR(64) NULL COMMENT '订货单位，关联 PRODUCT_UNIT 字典项；飞书导入草稿允许待补齐';

CREATE INDEX idx_order_sales_data_quality
    ON order_sales_order (tenant_id, data_quality_status_code, order_date);

UPDATE order_sales_order target
SET target.data_quality_status_code = CASE
        WHEN target.customer_id IS NULL
            OR target.customer_name_snapshot IS NULL
            OR target.customer_name_snapshot = ''
            OR EXISTS (
                SELECT 1
                FROM order_sales_order_line line
                WHERE line.tenant_id = target.tenant_id
                  AND line.order_id = target.id
                  AND line.deleted = 0
                  AND (
                      line.product_id IS NULL
                      OR line.product_variant_id IS NULL
                      OR line.unit_code IS NULL
                      OR line.unit_code = ''
                      OR line.product_name_snapshot IS NULL
                      OR line.product_name_snapshot = ''
                  )
            )
        THEN 'NEEDS_REVIEW'
        ELSE 'COMPLETE'
    END,
    target.data_quality_message = CASE
        WHEN target.customer_id IS NULL
            OR target.customer_name_snapshot IS NULL
            OR target.customer_name_snapshot = ''
            OR EXISTS (
                SELECT 1
                FROM order_sales_order_line line
                WHERE line.tenant_id = target.tenant_id
                  AND line.order_id = target.id
                  AND line.deleted = 0
                  AND (
                      line.product_id IS NULL
                      OR line.product_variant_id IS NULL
                      OR line.unit_code IS NULL
                      OR line.unit_code = ''
                      OR line.product_name_snapshot IS NULL
                      OR line.product_name_snapshot = ''
                  )
            )
        THEN '历史订单存在缺失引用，请在销售订单页面补齐客户、商品、规格或单位'
        ELSE NULL
    END
WHERE target.deleted = 0;

-- Source: V32__sales_order_payment_voucher_keys.sql
ALTER TABLE order_sales_order
    ADD COLUMN payment_voucher_keys_json JSON NULL COMMENT '订单级付款凭证COS key数组，来自飞书付款凭证字段';

-- Source: V33__mark_complete_feishu_sales_orders_completed.sql
-- 完整的飞书历史订单不需要业务人员再提交，只有待完善订单继续保留为草稿。
UPDATE order_sales_order
SET order_status_code = 'COMPLETED',
    updated_by = 'SYSTEM',
    updated_time = UTC_TIMESTAMP(6)
WHERE source_system_code = 'FEISHU'
  AND data_quality_status_code = 'COMPLETE'
  AND order_status_code = 'DRAFT'
  AND deleted = 0;

-- Source: V34__feishu_review_orders_back_to_draft.sql
-- 飞书导入的待补充订单必须保持草稿状态，便于业务人员补录或删除。
-- 已取消单保留取消状态，避免重新开放已经被业务关闭的单据。

UPDATE order_sales_order
SET order_status_code = 'DRAFT',
    revision = revision + 1,
    updated_by = 'SYSTEM',
    updated_time = CURRENT_TIMESTAMP(6)
WHERE deleted = 0
  AND source_system_code = 'FEISHU'
  AND data_quality_status_code = 'NEEDS_REVIEW'
  AND order_status_code NOT IN ('DRAFT', 'CANCELLED');

-- Source: V35__sales_order_source_unpaid_amount.sql
ALTER TABLE order_sales_order
    ADD COLUMN source_unpaid_amount DECIMAL(24,6) NULL COMMENT '来源订单显式待收金额；飞书导入保留来源待付金额对账口径' AFTER unpaid_amount;

-- Source: V36__sales_order_product_repair.sql
-- 仅创建修复预览和追加式证据表，不修改任何历史订单或退款。
CREATE TABLE order_product_repair_preview (
    preview_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    actor_id VARCHAR(50) NOT NULL,
    status VARCHAR(16) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    preview_json LONGTEXT NOT NULL,
    applied_json LONGTEXT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    applied_at DATETIME(6) NULL,
    PRIMARY KEY (preview_id),
    KEY idx_order_repair_history (tenant_id, order_id, status, applied_at),
    CONSTRAINT fk_order_repair_order FOREIGN KEY (order_id) REFERENCES order_sales_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='历史订单商品修复预览与应用审计';

CREATE TABLE order_product_repair_line (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    line_id BIGINT NOT NULL,
    preview_id VARCHAR(36) NOT NULL,
    line_revision INT NOT NULL,
    product_id BIGINT NOT NULL,
    product_variant_id BIGINT NOT NULL,
    product_code VARCHAR(128) NOT NULL,
    sku_code VARCHAR(128) NOT NULL,
    stored_unit_code VARCHAR(64) NULL COMMENT '原数据库单位，可能来自ERP默认值，不作为历史凭证',
    historical_transaction_unit_code VARCHAR(64) NULL COMMENT '操作员凭证确认的历史交易单位',
    transaction_unit_evidence VARCHAR(2000) NULL,
    transaction_unit_status VARCHAR(32) NOT NULL COMMENT 'UNVERIFIED或OPERATOR_CONFIRMED',
    transaction_quantity DECIMAL(24,6) NOT NULL,
    standard_unit_code VARCHAR(64) NULL,
    standard_quantity DECIMAL(24,6) NULL,
    conversion_factor DECIMAL(24,6) NULL,
    binding_evidence VARCHAR(2000) NOT NULL,
    source_namespace VARCHAR(200) NULL,
    source_capture_ref VARCHAR(500) NULL,
    source_product_record_id VARCHAR(200) NULL,
    source_product_code VARCHAR(200) NULL,
    source_evidence VARCHAR(2000) NULL,
    source_identity_status VARCHAR(32) NOT NULL COMMENT 'OPERATOR_CONFIRMED仅表示人工声明，非来源API独立验证',
    conversion_evidence VARCHAR(2000) NULL,
    original_json LONGTEXT NOT NULL,
    applied_json LONGTEXT NOT NULL,
    applied_by VARCHAR(50) NOT NULL,
    applied_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_repair_line (tenant_id, preview_id, line_id),
    KEY idx_order_repair_effective (tenant_id, order_id, line_id, line_revision),
    CONSTRAINT fk_order_repair_preview FOREIGN KEY (preview_id) REFERENCES order_product_repair_preview (preview_id),
    CONSTRAINT fk_order_repair_line FOREIGN KEY (line_id) REFERENCES order_sales_order_line (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品绑定及独立标准数量历史证据';

-- Source: V37__order_attribution_and_fulfillment.sql
-- 不根据今天的客户或组织补造历史归属。存量订单由迁移预检逐项核对。
ALTER TABLE order_sales_order ADD UNIQUE KEY uk_order_tenant_id(tenant_id,id),
    ADD COLUMN selected_warehouse_id BIGINT NULL,
    ADD COLUMN warehouse_selected_by VARCHAR(64) NULL,
    ADD COLUMN warehouse_selected_at DATETIME(6) NULL;
CREATE TABLE order_attribution_snapshot (
    tenant_id VARCHAR(64) NOT NULL,order_id BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,employee_code VARCHAR(50) NULL,employee_name VARCHAR(128) NULL,
    department_id BIGINT NULL,department_name VARCHAR(160) NULL,department_path JSON NOT NULL,
    region_code VARCHAR(128) NULL,region_path JSON NOT NULL,source_version VARCHAR(64) NOT NULL,
    customer_revision BIGINT NOT NULL,employee_revision BIGINT NOT NULL,organization_version BIGINT NOT NULL,
    resolved_at DATETIME(6) NOT NULL,frozen_at DATETIME(6) NULL,revision BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY(tenant_id,order_id),CONSTRAINT ck_order_attribution_state CHECK(state IN ('DRAFT','FROZEN','REVIEW')),
    CONSTRAINT fk_order_attribution_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE order_fulfillment_execution (
    tenant_id VARCHAR(64) NOT NULL,order_id BIGINT NOT NULL,execution_id VARCHAR(36) NOT NULL,
    warehouse_id BIGINT NOT NULL,order_revision INT NOT NULL,request_json JSON NOT NULL,request_hash VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
    erp_stock_out_id BIGINT NULL,erp_stock_out_no VARCHAR(64) NULL,erp_stock_out_at DATETIME(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,order_id),UNIQUE KEY uk_order_execution(tenant_id,execution_id),
    CONSTRAINT ck_order_execution_state CHECK(status IN ('PREPARED','EXECUTING','ERP_CONFIRMED','COMPLETED','REVIEW')),
    CONSTRAINT fk_order_execution_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V38__order_business_parameters.sql
CREATE TABLE order_business_parameter(
 tenant_id VARCHAR(36) NOT NULL,parameter_code VARCHAR(80) NOT NULL,parameter_value VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,updated_by VARCHAR(50) NOT NULL,updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,parameter_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE order_parameter_audit(
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,actor_id VARCHAR(50) NOT NULL,
 parameter_code VARCHAR(80) NOT NULL,old_value VARCHAR(100) NOT NULL,new_value VARCHAR(100) NOT NULL,
 reason VARCHAR(500) NOT NULL,revision BIGINT NOT NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 KEY ix_order_parameter_audit(tenant_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Source: V39__order_attribution_adjustment_review.sql
-- 历史归属采用明确证据、双人复核和不可变的调整前后记录，普通更新不能覆盖。
CREATE TABLE order_attribution_adjustment (
 tenant_id VARCHAR(64) NOT NULL, id VARCHAR(36) NOT NULL, order_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', expected_order_revision INT NOT NULL,
 expected_snapshot_revision BIGINT NOT NULL, before_json JSON NOT NULL, proposed_json JSON NOT NULL,
 source_system_code VARCHAR(64) NULL, source_order_no VARCHAR(128) NULL,
 evidence_ref VARCHAR(1000) NOT NULL, evidence_text VARCHAR(4000) NOT NULL, reason VARCHAR(1000) NOT NULL,
 proposed_by VARCHAR(64) NOT NULL, proposed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 reviewed_by VARCHAR(64) NULL, reviewed_at DATETIME(6) NULL, review_reason VARCHAR(1000) NULL,
 PRIMARY KEY(tenant_id,id), KEY ix_order_adjustment(tenant_id,order_id,proposed_at),
 CONSTRAINT fk_attribution_adjustment_order FOREIGN KEY(tenant_id,order_id) REFERENCES order_sales_order(tenant_id,id),
 CONSTRAINT ck_attribution_adjustment_status CHECK(status IN ('PENDING','APPLIED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V40__history_order_groups_and_receipt_ledger.sql
-- 历史来源按门店组成关联组；不复制飞书业务订单。所有约束含租户。
CREATE TABLE order_sync_source (
 tenant_id VARCHAR(36) NOT NULL, connector_id VARCHAR(36) NOT NULL, source_no VARCHAR(128) NOT NULL,
 customer_id BIGINT NOT NULL, source_date DATETIME(6), amount DECIMAL(20,2) NOT NULL,
 payload LONGTEXT NOT NULL, checksum VARCHAR(128) NOT NULL, state VARCHAR(40) NOT NULL,
 group_id VARCHAR(36), revision INT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,connector_id,source_no), KEY idx_sync_source_customer(tenant_id,customer_id,state)
);
CREATE TABLE order_history_group (
 tenant_id VARCHAR(36) NOT NULL,id VARCHAR(36) NOT NULL,customer_id BIGINT NOT NULL,
 evidence VARCHAR(1000) NOT NULL,actor_id VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,id)
);
CREATE TABLE order_history_member (
 tenant_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,group_id VARCHAR(36) NOT NULL,
 baseline_at DATETIME(6) NOT NULL,opening_paid DECIMAL(20,2) NOT NULL,
 PRIMARY KEY(tenant_id,order_id), KEY idx_history_member_group(tenant_id,group_id)
);
CREATE TABLE order_sync_receipt (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 source_order_no VARCHAR(128),customer_id BIGINT,group_id VARCHAR(36),amount DECIMAL(20,2) NOT NULL,
 occurred_at DATETIME(6),source_updated_at DATETIME(6),source_status VARCHAR(40),checksum VARCHAR(128) NOT NULL,
 state VARCHAR(40) NOT NULL,revision INT NOT NULL DEFAULT 0,
 pending_payload LONGTEXT, pending_checksum VARCHAR(128),
 employee_code VARCHAR(50),employee_name VARCHAR(100),owner_evidence VARCHAR(1000),owner_actor VARCHAR(64),
 PRIMARY KEY(tenant_id,connector_id,receipt_no),KEY idx_sync_receipt_customer(tenant_id,customer_id,occurred_at)
);
CREATE TABLE order_sync_allocation (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 order_id BIGINT NOT NULL,payment_id BIGINT,amount DECIMAL(20,2) NOT NULL,evidence VARCHAR(1000) NOT NULL,
 actor_id VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,receipt_no,order_id)
);
CREATE TABLE order_sync_receipt_revision (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 revision INT NOT NULL,payload LONGTEXT NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,receipt_no,revision)
);

ALTER TABLE order_sync_source ADD classification_evidence VARCHAR(1000);
ALTER TABLE order_sync_source ADD classification_actor VARCHAR(64);

CREATE TABLE order_sync_product_allocation (
 tenant_id VARCHAR(36) NOT NULL,connector_id VARCHAR(36) NOT NULL,receipt_no VARCHAR(128) NOT NULL,
 order_id BIGINT NOT NULL,line_id BIGINT NOT NULL,amount DECIMAL(20,2) NOT NULL,
 evidence VARCHAR(1000) NOT NULL,actor_id VARCHAR(64) NOT NULL,created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(tenant_id,connector_id,receipt_no,line_id)
);

-- Source: V41__order_register_and_financial_events.sql
-- Order V41：订货宝订单登记读取、来源/同步审计、订单号映射与资金历史事件。
-- 仅新增，不修改已执行的 V1-V40。

ALTER TABLE order_sales_order
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源真实创建时间' AFTER source_creator_name,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源真实修改时间' AFTER source_created_at,
    ADD COLUMN source_modifier_id VARCHAR(80) NULL COMMENT '来源真实修改人ID' AFTER source_updated_at,
    ADD COLUMN source_modifier_name VARCHAR(100) NULL COMMENT '来源真实修改人名称' AFTER source_modifier_id,
    ADD COLUMN synced_by VARCHAR(50) NULL COMMENT '最近成功同步人' AFTER updated_by,
    ADD COLUMN synced_at DATETIME(6) NULL COMMENT '最近成功同步时间' AFTER synced_by;

ALTER TABLE order_payment_record
    ADD COLUMN payment_status_code VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT '单笔收款状态：PENDING/RECEIVED/CANCELLED/CHECKED' AFTER paid_amount,
    ADD COLUMN transaction_no VARCHAR(128) NULL COMMENT '支付或银行渠道真实流水号' AFTER payment_status_code,
    ADD COLUMN source_record_id VARCHAR(128) NULL COMMENT '来源稳定收款记录ID' AFTER source_document_no,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源真实创建时间' AFTER source_record_id,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源真实修改时间' AFTER source_created_at,
    ADD COLUMN source_modifier_id VARCHAR(80) NULL COMMENT '来源真实修改人ID' AFTER source_updated_at,
    ADD COLUMN source_modifier_name VARCHAR(100) NULL COMMENT '来源真实修改人名称' AFTER source_modifier_id,
    ADD COLUMN synced_by VARCHAR(50) NULL COMMENT '最近成功同步人' AFTER updated_by,
    ADD COLUMN synced_at DATETIME(6) NULL COMMENT '最近成功同步时间' AFTER synced_by,
    ADD COLUMN checked_by VARCHAR(50) NULL COMMENT '财务核对人' AFTER synced_at,
    ADD COLUMN checked_at DATETIME(6) NULL COMMENT '财务核对时间' AFTER checked_by;

UPDATE order_payment_record
SET source_record_id = source_document_no
WHERE source_record_id IS NULL
  AND source_document_no IS NOT NULL;

CREATE TABLE order_number_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    connector_id CHAR(36) NULL,
    source_system_code VARCHAR(32) NOT NULL,
    source_object_type VARCHAR(64) NOT NULL,
    source_object_id VARCHAR(128) NOT NULL,
    dhb_order_no VARCHAR(128) NULL,
    internal_order_no VARCHAR(50) NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    evidence VARCHAR(1000) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    created_by VARCHAR(50) NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(50) NULL,
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_number_mapping_source (tenant_id, connector_id, source_object_type, source_object_id),
    KEY idx_order_number_mapping_order (tenant_id, internal_order_no),
    KEY idx_order_number_mapping_dhb (tenant_id, dhb_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE order_financial_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    source_key VARCHAR(160) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    receivable_delta DECIMAL(24,6) NOT NULL DEFAULT 0.000000,
    received_delta DECIMAL(24,6) NOT NULL DEFAULT 0.000000,
    effective_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    evidence VARCHAR(1000) NULL,
    revision INT NOT NULL DEFAULT 1,
    created_by VARCHAR(50) NULL,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_by VARCHAR(50) NULL,
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_financial_event_source (tenant_id, source_key),
    KEY idx_order_financial_event_order (tenant_id, order_id, effective_at),
    CONSTRAINT fk_order_financial_event_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES order_sales_order (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V42__order_invoice.sql
-- Order V42：自研开票登记（一单一票）。
-- 状态在发票行内流转：无记录=未申请，PENDING=待开票，INVOICED=已开票，REVOKED=已撤回（回显未申请）。
-- 本表只由订单服务维护，不参与订货宝同步；订单重新同步不会覆盖发票数据（按内部订单id关联）。
-- 仅新增，不修改已执行的 V1-V41。

CREATE TABLE order_invoice (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id            VARCHAR(64)  NOT NULL COMMENT '租户ID',
    sales_order_id       BIGINT       NOT NULL COMMENT '内部销售订单ID（order_sales_order.id）',
    order_no             VARCHAR(64)  NOT NULL COMMENT '订单号冗余，便于对账和排查',
    status               VARCHAR(16)  NOT NULL COMMENT 'PENDING待开票/INVOICED已开票/REVOKED已撤回',
    title_type           VARCHAR(16)  NOT NULL COMMENT 'COMPANY企业/PERSONAL个人',
    title                VARCHAR(200) NOT NULL COMMENT '发票抬头',
    tax_no               VARCHAR(64)  NULL COMMENT '纳税人识别号（企业必填）',
    invoice_type         VARCHAR(16)  NOT NULL COMMENT 'NORMAL普票/SPECIAL专票',
    bank_name            VARCHAR(200) NULL COMMENT '开户行（专票必填）',
    bank_account         VARCHAR(64)  NULL COMMENT '银行账号（专票必填）',
    register_address     VARCHAR(300) NULL COMMENT '注册地址（专票必填）',
    register_phone       VARCHAR(64)  NULL COMMENT '注册电话（专票必填）',
    email                VARCHAR(200) NULL COMMENT '收票邮箱',
    remark               VARCHAR(500) NULL COMMENT '备注',
    amount               DECIMAL(24,6) NULL COMMENT '申请时订单金额快照（payable_amount口径）',
    attachment_keys_json JSON         NULL COMMENT '发票附件COS对象键数组，读取时签发短时URL',
    invoice_no           VARCHAR(64)  NULL COMMENT '发票号码',
    applied_by           VARCHAR(64)  NULL COMMENT '申请人',
    applied_at           DATETIME(6)  NULL COMMENT '申请时间',
    invoiced_by          VARCHAR(64)  NULL COMMENT '完成开票人',
    invoiced_at          DATETIME(6)  NULL COMMENT '开票日期',
    created_at           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by           VARCHAR(64)  NULL COMMENT '最近修改人',
    updated_at           DATETIME(6)  NULL COMMENT '最近修改时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '软删标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_invoice_tenant_order (tenant_id, sales_order_id),
    KEY idx_order_invoice_tenant_order_no (tenant_id, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='自研开票登记（一单一票）；不参与订货宝同步';

-- Source: V43__order_invoice_customer_and_profiles.sql
-- Order V43：开票登记补充客户快照，并新增客户开票资料表（同一客户可保存多个抬头，申请时下拉选择回显）。
-- 资料表只由订单服务维护，不参与订货宝同步；去重在应用层按 抬头+税号+票种 处理，避免 NULL 唯一键限制。
-- 仅新增，不修改已执行的 V1-V42。

ALTER TABLE order_invoice
    ADD COLUMN customer_id BIGINT NULL COMMENT '客户ID快照（申请时）' AFTER sales_order_id,
    ADD COLUMN customer_code VARCHAR(64) NULL COMMENT '客户编码快照（申请时）' AFTER customer_id,
    ADD KEY idx_order_invoice_tenant_customer (tenant_id, customer_id);

UPDATE order_invoice inv
JOIN order_sales_order o ON o.tenant_id = inv.tenant_id AND o.id = inv.sales_order_id
   SET inv.customer_id = o.customer_id,
       inv.customer_code = o.customer_code_snapshot
 WHERE inv.customer_id IS NULL;

CREATE TABLE order_invoice_profile (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tenant_id        VARCHAR(64)  NOT NULL COMMENT '租户ID',
    customer_id      BIGINT       NOT NULL COMMENT '客户ID',
    customer_code    VARCHAR(64)  NULL COMMENT '客户编码快照',
    title_type       VARCHAR(16)  NOT NULL COMMENT 'COMPANY企业/PERSONAL个人',
    title            VARCHAR(200) NOT NULL COMMENT '发票抬头',
    tax_no           VARCHAR(64)  NULL COMMENT '纳税人识别号',
    invoice_type     VARCHAR(16)  NOT NULL COMMENT 'NORMAL普票/SPECIAL专票',
    bank_name        VARCHAR(200) NULL COMMENT '开户行',
    bank_account     VARCHAR(64)  NULL COMMENT '银行账号',
    register_address VARCHAR(300) NULL COMMENT '注册地址',
    register_phone   VARCHAR(64)  NULL COMMENT '注册电话',
    email            VARCHAR(200) NULL COMMENT '收票邮箱',
    remark           VARCHAR(500) NULL COMMENT '备注',
    last_used_at     DATETIME(6)  NULL COMMENT '最近一次用于申请的时间',
    created_by       VARCHAR(64)  NULL COMMENT '创建人',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by       VARCHAR(64)  NULL COMMENT '最近修改人',
    updated_at       DATETIME(6)  NULL COMMENT '最近修改时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '软删标记',
    PRIMARY KEY (id),
    KEY idx_order_invoice_profile_customer (tenant_id, customer_id, deleted, last_used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户开票资料（一个客户可多个抬头）；不参与订货宝同步';

-- Source: V44__order_payment_transaction_unique.sql
-- 回款核对并发保护：交易单号在有效记录内唯一。
-- 生成的键对空交易单号取 NULL，NULL 不参与唯一约束，未核对/无流水号的记录互不冲突。
-- V41 已执行，这里只做增量：不改 V41。
ALTER TABLE order_payment_record
    ADD COLUMN transaction_no_key VARCHAR(128)
        GENERATED ALWAYS AS (NULLIF(TRIM(transaction_no), '')) STORED
        COMMENT '交易单号唯一键：空值不参与唯一约束';

ALTER TABLE order_payment_record
    ADD UNIQUE KEY uk_order_payment_transaction (tenant_id, transaction_no_key);

-- Source: V45__order_invoice_revision.sql
-- 发票状态流转乐观锁：完成开票 / 撤回申请 / 修改申请都按 status + revision 原子流转。
-- V42、V43 已执行，这里只做增量：不改 V42/V43。
ALTER TABLE order_invoice
    ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本：每次状态或内容变更 +1' AFTER deleted;

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
