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
