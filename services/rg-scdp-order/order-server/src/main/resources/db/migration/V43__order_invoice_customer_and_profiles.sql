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
