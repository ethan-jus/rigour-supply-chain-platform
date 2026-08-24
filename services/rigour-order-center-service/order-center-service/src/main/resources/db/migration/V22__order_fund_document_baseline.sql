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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资金收付款单表';
