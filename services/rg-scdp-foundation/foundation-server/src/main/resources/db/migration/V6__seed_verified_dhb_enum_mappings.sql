-- 只补齐订货宝官方接口文档已明确给出“原值 -> 中文含义”的有限枚举。
-- V5 已在共享 DEV 执行，本迁移负责向前修复，禁止回写或删除历史迁移。
-- 使用 MySQL 8 的表值构造器，不要求迁移账号拥有 CREATE TEMPORARY TABLES 权限。
INSERT INTO biz_dict_item
    (id, dict_id, parent_id, level_no, code, name, value, sort_no, status,
     extra_json, version, created_by, updated_by, created_at, updated_at)
SELECT UUID(), d.id, NULL, 1,
       CONCAT('SEED_', UPPER(SUBSTRING(SHA2(CONCAT(m.module_code, CHAR(0), m.dict_code,
           CHAR(0), m.source_value), 256), 1, 59))),
       m.display_name, m.source_value, m.sort_no, 'ACTIVE', NULL, 0, NULL, NULL,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM (
    VALUES
    -- ERP：getGoodsList、getPurchaseList、getPurchaseReturnList、getWarehousingList、getStockInfo。
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'T', '正常', 10),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'F', '回收站', 20),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'A', '待审', 30),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'N', '未通过', 40),
    ROW('ERP', 'DHB_PRODUCT_STATUS', 'R', '撤销', 50),
    ROW('ERP', 'DHB_PRODUCT_PUTAWAY', 'T', '上架', 10),
    ROW('ERP', 'DHB_PRODUCT_PUTAWAY', 'F', '下架', 20),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'pending', '待审核', 10),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'wh_up', '待入库', 20),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'wh_half', '部分入库', 30),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'cancelled', '已取消', 40),
    ROW('ERP', 'DHB_PURCHASE_ORDER_STATUS', 'finished', '已完成', 50),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'oblig', '待付款', 10),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'uncollect', '部分付款', 20),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'paided', '已付款', 30),
    ROW('ERP', 'DHB_PURCHASE_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'stock_up', '待出库', 10),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'cancelled', '已取消', 20),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'refunds', '待退款', 30),
    ROW('ERP', 'DHB_PURCHASE_RETURN_STATUS', 'finished', '已完成', 40),
    ROW('ERP', 'DHB_WAREHOUSING_STATUS', 'throughed', '已审核', 10),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '-1', '退货入库', 10),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '1', '采购入库', 20),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '3', '盘盈入库', 30),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '8', '调拨入库', 40),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '20', '运营入库', 50),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '21', '红冲销售出库', 60),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '7', '其他入库', 70),
    ROW('ERP', 'DHB_WAREHOUSING_TYPE', '9', '联营入库', 80),
    ROW('ERP', 'DHB_WAREHOUSE_STATUS', 'T', '正常', 10),
    ROW('ERP', 'DHB_WAREHOUSE_STATUS', 'F', '停用', 20),

    -- CRM：getDealersList、getStaffList。
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'T', '正常', 10),
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'F', '停用', 20),
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'A', '待激活', 30),
    ROW('CRM', 'DHB_CUSTOMER_STATUS', 'C', '待审核', 40),
    ROW('CRM', 'DHB_CUSTOMER_CLEARING_FORM', 'prepaid', '预付', 10),
    ROW('CRM', 'DHB_CUSTOMER_CLEARING_FORM', 'forward', '现付', 20),
    ROW('CRM', 'DHB_CUSTOMER_CLEARING_FORM', 'postpaid', '后付', 30),
    ROW('CRM', 'DHB_STAFF_STATUS', 'T', '启用', 10),
    ROW('CRM', 'DHB_STAFF_STATUS', 'F', '停用', 20),
    ROW('CRM', 'DHB_STAFF_TYPE', 'salesman', '业务员', 10),
    ROW('CRM', 'DHB_STAFF_TYPE', 'boss', '老板', 20),
    ROW('CRM', 'DHB_STAFF_TYPE', 'indoorwork', '内勤', 30),
    ROW('CRM', 'DHB_STAFF_TYPE', 'driver', '司机', 40),

    -- Order：getOrderList、getOrderContent。
    ROW('ORDER', 'DHB_ORDER_STATUS', 'pricing', '待核价', 10),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'pending', '待审核', 20),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'stock_up', '待出库', 30),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'stockup', '待出库', 40),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'shipped', '待发货', 50),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'received', '待收货', 60),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'finished', '已完成', 70),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'forcedone', '强制完成', 80),
    ROW('ORDER', 'DHB_ORDER_STATUS', 'cancelled', '已取消', 90),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'oblig', '待收款', 10),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'uncollect', '部分收款', 20),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'paided', '已收款', 30),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'wait', '待确认', 50),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'part', '部分确认', 60),
    ROW('ORDER', 'DHB_ORDER_PAYMENT_STATUS', 'unoblig', '待确认付款', 70),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'normal', '普通订单', 10),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'C', '经销商提交', 20),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'M', '管理端代提交', 30),
    ROW('ORDER', 'DHB_ORDER_TYPE', 'S', '业务员代提交', 40),
    ROW('ORDER', 'DHB_ORDER_API_STATUS', 'F', '未下载', 10),
    ROW('ORDER', 'DHB_ORDER_API_STATUS', 'T', '已下载', 20),
    ROW('ORDER', 'DHB_ORDER_EXCEPTION_STATUS', 'F', '正常', 10),
    ROW('ORDER', 'DHB_ORDER_EXCEPTION_STATUS', 'T', '异常', 20),
    ROW('ORDER', 'DHB_ORDER_ADMIN_FLAG', 'T', '管理端下单', 10),
    ROW('ORDER', 'DHB_ORDER_ADMIN_FLAG', 'F', '订货端下单', 20),
    ROW('ORDER', 'DHB_SETTLEMENT_METHOD', 'prepaid', '预付', 10),
    ROW('ORDER', 'DHB_SETTLEMENT_METHOD', 'forward', '现付', 20),
    ROW('ORDER', 'DHB_SETTLEMENT_METHOD', 'postpaid', '后付', 30),
    ROW('ORDER', 'DHB_INVOICE_TYPE', 'P', '普通发票', 10),
    ROW('ORDER', 'DHB_INVOICE_TYPE', 'Z', '增值税发票', 20),
    ROW('ORDER', 'DHB_INVOICE_TYPE', 'F', '无发票', 30),
    ROW('ORDER', 'DHB_ORDER_LINE_TYPE', 'c', '正常售卖', 10),
    ROW('ORDER', 'DHB_ORDER_LINE_TYPE', 'g', '赠品', 20),

    -- Order：getWaitShips、getShipsList、getReturnsList。
    ROW('ORDER', 'DHB_GOODS_LIST_TYPE', 'buy', '买', 10),
    ROW('ORDER', 'DHB_GOODS_LIST_TYPE', 'gift', '赠', 20),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'shipped', '待发货', 10),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'receivedin', '待收货', 20),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'received', '已收货', 30),
    ROW('ORDER', 'DHB_SHIPMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '-2', '采购退货', 10),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '10', '销售出库', 20),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '11', '盘亏出库', 30),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '17', '其他出库', 40),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '18', '调拨出库', 50),
    ROW('ORDER', 'DHB_SHIPMENT_TYPE', '19', '联营出库', 60),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'return_audit', '待退货审核', 10),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'shipp_cust', '待客户发货', 20),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'shipped', '待收货', 30),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'refunded', '待退款', 40),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'finished', '已完成', 50),
    ROW('ORDER', 'DHB_RETURN_STATUS', 'cancelled', '已取消', 60),
    ROW('ORDER', 'DHB_RETURN_TYPE', '0', '未确认', 10),
    ROW('ORDER', 'DHB_RETURN_TYPE', '1', '退货退款', 20),
    ROW('ORDER', 'DHB_RETURN_TYPE', '2', '仅退款', 30),

    -- Order：getReceiptsList、getPaymentList；RECEIPT/PAYMENT 是平台稳定归一化值。
    ROW('ORDER', 'DHB_FINANCIAL_DOCUMENT_TYPE', 'RECEIPT', '收款单', 10),
    ROW('ORDER', 'DHB_FINANCIAL_DOCUMENT_TYPE', 'PAYMENT', '付款单', 20),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '1', '普通充值', 10),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '19', '预付款充值', 20),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '13', '订单收款', 30),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '8', '期初充值', 40),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '2', '退货退款', 50),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '10', '退款失败回冲', 60),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '9', '退款红冲', 70),
    ROW('ORDER', 'DHB_FINANCIAL_BUSINESS_TYPE', '5', '预存款扣款', 80),
    ROW('ORDER', 'DHB_FINANCIAL_STATUS', 'pend_receipt', '待确认', 10),
    ROW('ORDER', 'DHB_FINANCIAL_STATUS', 'pend_receipted', '已确认', 20),
    ROW('ORDER', 'DHB_FINANCIAL_STATUS', 'canceled', '已取消', 30),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Alipay', '支付宝支付（原生）', 10),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Quick', '快捷支付', 20),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Micro', '微信支付（原生）', 30),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Offline', '转账支付', 40),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Deposit', '预存款支付', 50),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Delivery', '货到付款', 60),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Credit', '赊销支付', 70),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Rebate', '返利支付', 80),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Alipay', '支付宝支付', 90),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Wechat', '微信支付', 100),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Quick', '银联快捷', 110),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Netbank', '网银支付', 120),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Wechat', 'iOS移动管理端微信支付', 130),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Alipay', 'iOS移动管理端支付宝支付', 140),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Wechat', 'Android移动管理端微信支付', 150),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Alipay', 'Android移动管理端支付宝支付', 160),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Zhongjin_Account_Bank_Transfer', '中金来账识别', 170),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Mybank_Cloud', '支付宝云资金', 180),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Refund_Fail_Recharge', '退款失败回充', 190),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Mse', '微企付支付', 200),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Hht', '品牌资金账户', 210),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Alipay_Transfer', '支付宝转账', 220),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'WECHAT_B2B_DIRECT', '微信B2B支付', 230),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'MYBANK_BALANCE', '云资金余额支付', 240),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Refund_Red_Reversal', '退款红冲', 250),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Yw_Pay', '云闪付', 260),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'Tp_Pay', '通企付', 270),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_MINIAPP', '微信支付（通企付）', 280),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY', '支付宝支付（通企付）', 290),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_QR', '吱口令（通企付）', 300),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_YT_PAY', '云闪付（通企付）', 310),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_QUICK', '银联快捷（通企付）', 320),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_FRIEND', '微信好友代付（通企付）', 330),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_SCAN_POS', '微信扫码支付（通企付）', 340),
    ROW('ORDER', 'DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_SCAN_POS', '支付宝扫码支付（通企付）', 350)
) AS m(module_code, dict_code, source_value, display_name, sort_no)
JOIN biz_dict d
  ON d.scope_type = 'MODULE'
 AND d.scope_id = m.module_code
 AND d.module_code = m.module_code
 AND d.code = m.dict_code
ON DUPLICATE KEY UPDATE
    updated_at = IF(biz_dict_item.name = biz_dict_item.value, UTC_TIMESTAMP(6), biz_dict_item.updated_at),
    version = IF(biz_dict_item.name = biz_dict_item.value, biz_dict_item.version + 1, biz_dict_item.version),
    name = IF(biz_dict_item.name = biz_dict_item.value, VALUES(name), biz_dict_item.name);

UPDATE biz_dict d
SET d.revision = d.revision + 1,
    d.updated_at = UTC_TIMESTAMP(6)
WHERE EXISTS (
    SELECT 1
    FROM biz_dict_item i
    WHERE i.dict_id = d.id
      AND i.code LIKE 'SEED\_%' ESCAPE '\\'
);

-- 官方文档未给出固定取值映射，或字段允许任意业务文本；这些定义不能冒充枚举。
UPDATE biz_dict
SET status = 'DISABLED',
    remark = CASE code
        WHEN 'DHB_PURCHASE_RETURN_DEVICE' THEN '官方接口未给出完整设备枚举，不自动猜测映射'
        WHEN 'DHB_WAREHOUSING_SPLIT_TYPE' THEN '官方接口仅返回split_type原值，未给出固定值含义'
        WHEN 'DHB_ORDER_SEND_TYPE' THEN '发货方式为业务配置文本，不是固定编码枚举'
        WHEN 'DHB_ORDER_DEVICE' THEN '官方接口仅说明下单渠道，未给出完整编码枚举'
        WHEN 'DHB_RETURN_DELIVERY_MODE' THEN '退单配送方式为快递、物流等业务文本，不是固定编码枚举'
        ELSE remark
    END,
    version = version + 1,
    revision = revision + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE scope_type = 'MODULE'
  AND ((module_code = 'ERP' AND code IN ('DHB_PURCHASE_RETURN_DEVICE', 'DHB_WAREHOUSING_SPLIT_TYPE'))
    OR (module_code = 'ORDER' AND code IN ('DHB_ORDER_SEND_TYPE', 'DHB_ORDER_DEVICE',
                                           'DHB_RETURN_DELIVERY_MODE')))
  AND status <> 'DISABLED';

-- orders_units 的 base_units/middle_units/container_units 是单位层级代码，不是计量单位名称。
UPDATE biz_dict_item i
JOIN biz_dict d ON d.id = i.dict_id
SET i.status = 'DISABLED',
    i.version = i.version + 1,
    i.updated_at = UTC_TIMESTAMP(6)
WHERE d.scope_type = 'MODULE'
  AND d.module_code = 'COMMON'
  AND d.code = 'DHB_UNIT'
  AND i.value IN ('base_units', 'middle_units', 'container_units', 'big_units')
  AND i.status <> 'DISABLED';

UPDATE biz_dict
SET revision = revision + 1,
    updated_at = UTC_TIMESTAMP(6)
WHERE scope_type = 'MODULE'
  AND module_code = 'COMMON'
  AND code = 'DHB_UNIT';
