-- 销售回款需要承接实际收款方式；保留原有全款/月结用于销售订单结算条款。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('PAYMENT_METHOD', 1, NULL, 'CASH', '现金', '线下现金收款', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'BANK_TRANSFER', '银行转账', '银行或网银转账收款', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'WECHAT', '微信支付', '微信或微信扫码收款', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'ALIPAY', '支付宝', '支付宝或支付宝扫码收款', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'DEPOSIT', '预存款', '客户预存款抵扣', 70, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'CREDIT', '赊销', '客户账期赊销', 80, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'OTHER', '其他', '未归类收款方式', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);
