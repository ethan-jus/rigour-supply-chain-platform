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
