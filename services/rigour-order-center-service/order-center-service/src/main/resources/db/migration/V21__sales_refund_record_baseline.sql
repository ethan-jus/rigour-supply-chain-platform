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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单退款记录表';
