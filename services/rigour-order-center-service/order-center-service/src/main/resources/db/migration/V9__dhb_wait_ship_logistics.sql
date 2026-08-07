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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订货宝getWaitShips出库/发货物流明细';
