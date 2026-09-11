-- Analytics BI：经营化看板补充客户建联、账期、目标和库存流转读模型。
--
-- Portal 仍只读取 rigour_bi；跨 CRM/Order/ERP 的 SQL 只在刷新任务中使用。

ALTER TABLE bi_customer_dim
    ADD COLUMN contact_name_snapshot VARCHAR(160) NULL COMMENT '主联系人名称快照' AFTER customer_name,
    ADD COLUMN contact_phone_snapshot VARCHAR(128) NULL COMMENT '主联系人电话快照' AFTER contact_name_snapshot,
    ADD COLUMN has_contact TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已建联：联系人或电话非空' AFTER contact_phone_snapshot,
    ADD COLUMN payment_term_days INT UNSIGNED NOT NULL DEFAULT 30 COMMENT '客户账期天数；缺失默认30天' AFTER status_code;

CREATE INDEX idx_bi_customer_dim_contact
    ON bi_customer_dim (tenant_id, has_contact, status_code, deleted);

ALTER TABLE bi_sales_order_fact
    ADD COLUMN payment_term_days INT UNSIGNED NOT NULL DEFAULT 30 COMMENT '订单客户账期天数快照' AFTER shipment_time,
    ADD COLUMN payment_due_date DATETIME(6) NULL COMMENT '订单应回款到期日，按订单日期+账期计算' AFTER payment_term_days;

CREATE INDEX idx_bi_sales_order_fact_due
    ON bi_sales_order_fact (tenant_id, payment_due_date, unpaid_amount, deleted);

UPDATE bi_sales_order_fact
   SET payment_due_date = DATE_ADD(order_date, INTERVAL COALESCE(payment_term_days, 30) DAY)
 WHERE payment_due_date IS NULL;

ALTER TABLE bi_inventory_balance_current
    ADD COLUMN unit_code VARCHAR(64) NULL COMMENT '库存单位，来自ERP商品规格订货单位' AFTER variant_code;

CREATE INDEX idx_bi_inventory_balance_current_category_unit
    ON bi_inventory_balance_current (tenant_id, product_category_id, unit_code, deleted);

CREATE TABLE bi_business_target (
    id                  BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)    NOT NULL COMMENT '租户ID',
    target_month        DATE           NOT NULL COMMENT '目标月份，取当月1日',
    dimension_type      VARCHAR(32)    NOT NULL COMMENT '目标维度：CITY/SALES_OWNER',
    dimension_code      VARCHAR(64)    NOT NULL COMMENT '维度编码',
    dimension_name      VARCHAR(160)   NULL COMMENT '维度名称快照',
    metric_code         VARCHAR(64)    NOT NULL COMMENT '指标编码：CONTACTED_CUSTOMER/COOPERATED_CUSTOMER/SALES_AMOUNT/PAID_AMOUNT',
    target_value        DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '目标值',
    source_system_code  VARCHAR(64)    NOT NULL DEFAULT 'MANUAL_IMPORT' COMMENT '目标来源',
    source_record_id    VARCHAR(120)   NULL COMMENT '来源记录ID',
    remark              VARCHAR(1000)  NULL COMMENT '备注',
    synced_time         DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    revision            INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by          VARCHAR(50)    NULL COMMENT '创建人',
    created_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)    NULL COMMENT '更新人',
    updated_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted             INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_business_target_metric (
        tenant_id, target_month, dimension_type, dimension_code, metric_code
    ),
    KEY idx_bi_business_target_dimension (tenant_id, dimension_type, dimension_code, target_month, deleted),
    KEY idx_bi_business_target_metric (tenant_id, metric_code, target_month, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI城市/销售经营目标配置';

CREATE TABLE bi_inventory_operation_fact (
    id                         BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)    NOT NULL COMMENT '租户ID',
    operation_type             VARCHAR(32)    NOT NULL COMMENT '流转类型：PROCUREMENT/SALES_SHIPMENT',
    source_document_id         BIGINT(20)     NOT NULL COMMENT '来源单据ID',
    source_line_id             BIGINT(20)     NOT NULL COMMENT '来源明细ID',
    source_document_no         VARCHAR(50)    NULL COMMENT '来源单据号快照',
    operation_time             DATETIME(6)    NOT NULL COMMENT '业务发生时间',
    product_id                 BIGINT(20)     NULL COMMENT 'ERP商品ID',
    product_code               VARCHAR(50)    NULL COMMENT '商品编码快照',
    product_name               VARCHAR(200)   NULL COMMENT '商品名称快照',
    product_category_id        BIGINT(20)     NULL COMMENT '商品分类ID快照',
    product_category_code      VARCHAR(64)    NULL COMMENT '商品分类编码快照',
    product_category_name      VARCHAR(160)   NULL COMMENT '商品分类名称快照',
    product_variant_id         BIGINT(20)     NULL COMMENT 'ERP商品规格ID',
    variant_code               VARCHAR(50)    NULL COMMENT '规格编码快照',
    unit_code                  VARCHAR(64)    NOT NULL COMMENT '数量单位',
    quantity                   DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '数量',
    source_updated_time        DATETIME(6)    NULL COMMENT '源数据更新时间水位',
    synced_time                DATETIME(6)    NOT NULL COMMENT '同步到BI时间',
    deleted                    INT            NOT NULL DEFAULT 0 COMMENT '源逻辑删除标识',
    created_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_inventory_operation_source (
        tenant_id, operation_type, source_document_id, source_line_id
    ),
    KEY idx_bi_inventory_operation_time (tenant_id, operation_time, operation_type, deleted),
    KEY idx_bi_inventory_operation_category (tenant_id, product_category_id, unit_code, operation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI采购/发货库存流转事实';
