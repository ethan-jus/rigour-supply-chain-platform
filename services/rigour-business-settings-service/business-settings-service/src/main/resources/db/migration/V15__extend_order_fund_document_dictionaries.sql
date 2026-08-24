-- Business Settings V15：Order资金收付款单字典。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('FUND_DOCUMENT_DIRECTION', '资金单据方向', 'ORDER', '资金单据收款、付款方向', 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', '资金单据业务类型', 'ORDER', '订货宝收付款单业务类型映射后的我方业务分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_STATUS', '资金单据状态', 'ORDER', '资金单据待确认、已确认、已取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('COUNTERPARTY_TYPE', '往来方类型', 'ORDER', '资金收付款单对应客户、供应商或其他往来方', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('FUND_DOCUMENT_DIRECTION', 1, NULL, 'RECEIPT', '收款', '资金流入单据', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_DIRECTION', 1, NULL, 'PAYMENT', '付款', '资金流出单据', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'ORDER_RECEIPT', '订单收款', '销售订单直接收款；可同步生成销售回款核销记录', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'CUSTOMER_RECHARGE', '客户充值', '客户预存款或余额充值产生的收款流水', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'BALANCE_DEDUCTION', '余额抵扣', '客户预存款或余额被订单抵扣产生的付款/消费流水', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'CUSTOMER_REFUND', '客户退款', '明确向客户退款的付款流水；订货宝普通付款单不默认归入此类', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'PREPAYMENT', '预收预付', '无法细分为充值或抵扣的预收预付相关流水', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'REVERSAL', '冲正回冲', '退款失败、红冲或其他资金冲正', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_BUSINESS_TYPE', 1, NULL, 'OTHER', '其他', '无法明确映射的资金业务类型', 90, 1, 'SYSTEM', 'SYSTEM', 0),

    ('FUND_DOCUMENT_STATUS', 1, NULL, 'PENDING', '待确认', '资金单据待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_STATUS', 1, NULL, 'CONFIRMED', '已确认', '资金单据已确认', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('FUND_DOCUMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '资金单据已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('COUNTERPARTY_TYPE', 1, NULL, 'CUSTOMER', '客户', '客户往来方', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('COUNTERPARTY_TYPE', 1, NULL, 'SUPPLIER', '供应商', '供应商往来方', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('COUNTERPARTY_TYPE', 1, NULL, 'OTHER', '其他', '其他往来方', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;
