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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Order销售发货单主表';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Order销售发货单明细表';
