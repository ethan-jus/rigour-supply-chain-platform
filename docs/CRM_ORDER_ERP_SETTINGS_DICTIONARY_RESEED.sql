-- CRM / Order / ERP / Business Settings 字典基线重建脚本。
--
-- 适用场景：已执行 CRM_ORDER_ERP_DHB_RESYNC_RESET.sql 清空 data_dictionary_item / data_dictionary 后，
-- 立即按当前代码迁移基线恢复自研业务字典和已验证订货宝来源字典。
--
-- 本脚本只重建 data_dictionary / data_dictionary_item 数据，不修改 flyway_schema_history。
-- 内容机械复制自 business-settings-service 当前 V8、V9、V11-V24 字典种子迁移。

USE rigour_settings;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V8__seed_internal_business_dictionaries.sql
-- 自研业务基线字典。
-- 表名、字段名统一 lower_snake_case；字典编码和字典项编码统一 UPPER_SNAKE_CASE。
-- 业务表只保存 dictionary_item_code，不保存订货宝状态值；订货宝映射后续放在 Integration。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', '商品单位', 'COMMON', '商品订货、采购、库存展示单位', 1, 'SYSTEM', 'SYSTEM', 0),
    ('REGION', '业务地区', 'COMMON', '客户归属地区与仓库地区匹配', 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SUBMIT_STATUS', '商品提交状态', 'ERP', '商品保存草稿与提交校验状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SALE_TYPE', '商品售卖类型', 'ERP', '商品现货、预售、停售', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SHELF_STATUS', '商品上架状态', 'ERP', '商品上架与下架', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_TAG_TYPE', '商品标签类型', 'ERP', '新品、推荐、热销等商品标签', 1, 'SYSTEM', 'SYSTEM', 0),
    ('SUPPLIER_STATUS', '供应商状态', 'ERP', '供应商启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_STATUS', '仓库状态', 'ERP', '仓库启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', '采购订单状态', 'ERP', '采购订单创建、提交、入库状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_TYPE', '入库类型', 'ERP', '采购入库、调拨入库、退货入库', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', '入库单状态', 'ERP', '入库单保存、确认、取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', '出库类型', 'ERP', '销售出库、调拨出库', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', '出库单状态', 'ERP', '出库单保存、确认、取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', '调拨单状态', 'ERP', '调拨单出库与入库确认状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_STATUS', '客户状态', 'CRM', '客户启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_SETTLEMENT_TYPE', '客户结算类型', 'CRM', 'A类商家、B类商家等结算分类', 1, 'SYSTEM', 'SYSTEM', 0),

    ('ORDER_TYPE', '销售订单类型', 'ORDER', '新拓、复购等销售订单类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', '付款方式', 'ORDER', '全款、月结等付款方式', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', '收款状态', 'ORDER', '销售订单回款状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', '出库状态', 'ORDER', '销售订单出库状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'BUCKET', '桶', '泡面等桶装商品单位', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'BOX', '箱', '箱装商品单位', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'PORTION', '份', '按份售卖商品单位', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'SET', '套', '套装商品单位', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SUBMIT_STATUS', 1, NULL, 'DRAFT', '草稿', '保存，允许必填项暂不完整', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SUBMIT_STATUS', 1, NULL, 'SUBMITTED', '已提交', '提交，服务端校验必填项完整', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SALE_TYPE', 1, NULL, 'SPOT', '现货', '有货或可正常销售', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SALE_TYPE', 1, NULL, 'PRE_SALE', '预售', '允许预售下单', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SALE_TYPE', 1, NULL, 'STOP_SALE', '停售', '停止销售但保留历史数据', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_SHELF_STATUS', 1, NULL, 'ON_SHELF', '上架', '前台可见可售', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SHELF_STATUS', 1, NULL, 'OFF_SHELF', '下架', '前台不可售', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PRODUCT_TAG_TYPE', 1, NULL, 'NEW', '新品', '新品标签', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_TAG_TYPE', 1, NULL, 'RECOMMENDED', '推荐', '推荐标签', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_TAG_TYPE', 1, NULL, 'HOT', '热销', '热销标签', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('SUPPLIER_STATUS', 1, NULL, 'ACTIVE', '启用', '供应商可用于采购下单', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SUPPLIER_STATUS', 1, NULL, 'DISABLED', '停用', '供应商暂不可用于新采购', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('WAREHOUSE_STATUS', 1, NULL, 'ACTIVE', '启用', '仓库可用于入库、出库和调拨', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('WAREHOUSE_STATUS', 1, NULL, 'DISABLED', '停用', '仓库暂不可用于新库存业务', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_STATUS', 1, NULL, 'ACTIVE', '启用', '客户可用于销售下单', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_STATUS', 1, NULL, 'DISABLED', '停用', '客户暂不可用于新业务', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_SETTLEMENT_TYPE', 1, NULL, 'A', 'A类商家', 'A类客户结算分类', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_SETTLEMENT_TYPE', 1, NULL, 'B', 'B类商家', 'B类客户结算分类', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('ORDER_TYPE', 1, NULL, 'NEW', '新拓', '新客户首单或拓展订单', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ORDER_TYPE', 1, NULL, 'REPEAT', '复购', '老客户复购订单', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PAYMENT_METHOD', 1, NULL, 'FULL', '全款', '下单或出库前付清', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', 1, NULL, 'MONTHLY', '月结', '按账期结算', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PAYMENT_STATUS', 1, NULL, 'UNPAID', '未付款', '订单尚未回款', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', 1, NULL, 'PARTIAL_PAID', '部分付款', '订单已有部分回款', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', 1, NULL, 'PAID', '已结清', '订单已收清款项', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '销售订单已取消，不再形成待收款', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('OUTBOUND_STATUS', 1, NULL, 'PENDING', '待出库', '订单尚未完成出库', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'PARTIAL', '部分出库', '订单已有部分商品出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'COMPLETED', '已出库', '订单已全部出库', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'SHORTAGE', '缺货', '当前库存不足，需要采购或调拨', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('PURCHASE_STATUS', 1, NULL, 'DRAFT', '草稿', '采购单保存未提交', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'SUBMITTED', '已提交', '采购单已提交供应商或内部确认', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'PARTIAL_IN', '部分入库', '采购商品部分到货入库', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'COMPLETED', '已入库', '采购商品已全部入库', 40, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_IN_TYPE', 1, NULL, 'PURCHASE', '采购入库', '采购到货后确认入库', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_TYPE', 1, NULL, 'TRANSFER', '调拨入库', '调拨到货后确认入库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_TYPE', 1, NULL, 'RETURN', '退货入库', '订货宝退货产生的入库凭证', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_IN_STATUS', 1, NULL, 'DRAFT', '草稿', '入库单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', 1, NULL, 'CONFIRMED', '已入库', '入库单已确认并写库存', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', 1, NULL, 'CANCELLED', '已取消', '入库单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_OUT_TYPE', 1, NULL, 'SALES', '销售出库', '销售订单确认出库', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'TRANSFER', '调拨出库', '调拨单确认出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),

    ('STOCK_OUT_STATUS', 1, NULL, 'DRAFT', '草稿', '出库单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', 1, NULL, 'CONFIRMED', '已出库', '出库单已确认并写库存', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', 1, NULL, 'CANCELLED', '已取消', '出库单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0),

    ('TRANSFER_STATUS', 1, NULL, 'DRAFT', '草稿', '调拨单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', 1, NULL, 'OUT_CONFIRMED', '已确认出库', '来源仓库已确认出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', 1, NULL, 'IN_CONFIRMED', '已确认入库', '目标仓库已确认入库', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', 1, NULL, 'CANCELLED', '已取消', '调拨单已取消', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V9__seed_dhb_source_dictionaries_for_new_model.sql
-- 补齐新字典模型中的订货宝来源字典。
-- V1-V6 属于旧 biz_dict 模型；V7/V8 切换到 data_dictionary 后，需要把仍被 ERP/CRM/Order
-- 同步白名单使用的来源字典定义迁入新表。这里不回写历史迁移，也不把来源值写入业务主表。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('DHB_UNIT', '订货宝计量单位', 'COMMON', 'ERP、CRM、Order共用的订货宝来源计量单位；按来源值精确同步', 1, 'SYSTEM', 'SYSTEM', 0),

    ('DHB_PRODUCT_STATUS', '订货宝商品状态', 'ERP', '商品来源报文status原值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PRODUCT_PUTAWAY', '订货宝商品上下架状态', 'ERP', '商品putaway来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PURCHASE_ORDER_STATUS', '订货宝采购单状态', 'ERP', '采购单来源状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PURCHASE_PAYMENT_STATUS', '订货宝采购付款状态', 'ERP', '采购单付款状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PURCHASE_RETURN_STATUS', '订货宝采购退货状态', 'ERP', '采购退货单来源状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_WAREHOUSING_STATUS', '订货宝入库单状态', 'ERP', '入库单来源状态及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_WAREHOUSING_TYPE', '订货宝入库类型', 'ERP', '入库单类型ID及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_WAREHOUSE_STATUS', '订货宝仓库状态', 'ERP', '仓库来源状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('DHB_CUSTOMER_STATUS', '订货宝客户状态', 'CRM', '客户来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_CUSTOMER_CLEARING_FORM', '订货宝客户结算方式', 'CRM', '客户clientClearingForm明确字段', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_STAFF_STATUS', '订货宝职员状态', 'CRM', '职员来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_STAFF_TYPE', '订货宝员工类型', 'CRM', '员工staff_type原值', 1, 'SYSTEM', 'SYSTEM', 0),

    ('DHB_ORDER_STATUS', '订货宝订单状态', 'ORDER', '订单来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_PAYMENT_STATUS', '订货宝订单收款状态', 'ORDER', '订单PayStatus来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_TYPE', '订货宝订单类型', 'ORDER', '订单类型来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_API_STATUS', '订货宝订单下载状态', 'ORDER', '订单API下载状态来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_EXCEPTION_STATUS', '订货宝订单异常标记', 'ORDER', '订单异常标记来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_ADMIN_FLAG', '订货宝管理员订单标记', 'ORDER', '管理员订单来源标记', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_SPLIT_TYPE', '订货宝拆单类型', 'ORDER', '拆单类型及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_SETTLEMENT_METHOD', '订货宝结算方式', 'ORDER', '订单结算方式来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_INVOICE_TYPE', '订货宝发票类型', 'ORDER', '订单发票类型来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_ORDER_LINE_TYPE', '订货宝订单明细商品类型', 'ORDER', '订单明细contentType原值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_SHIPMENT_STATUS', '订货宝出库发货状态', 'ORDER', '发货单和物流快照来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_SHIPMENT_TYPE', '订货宝出库类型', 'ORDER', '发货单出库类型ID及来源名称', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_GOODS_LIST_TYPE', '订货宝商品明细类型', 'ORDER', '出库及待出库明细listType原值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_RETURN_STATUS', '订货宝销售退货状态', 'ORDER', '销售退货单来源状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_RETURN_TYPE', '订货宝销售退货类型', 'ORDER', '退货退款类型来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_FINANCIAL_DOCUMENT_TYPE', '订货宝收付款单类型', 'ORDER', '收款单和付款单类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_FINANCIAL_BUSINESS_TYPE', '订货宝收付款业务类型', 'ORDER', '收付款IncexpId来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_PAYMENT_METHOD', '订货宝支付方式', 'ORDER', '收付款TypeId来源值', 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_FINANCIAL_STATUS', '订货宝收付款状态', 'ORDER', '收付款单来源状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
SELECT m.dict_code, 1, NULL,
       CASE
           WHEN REGEXP_LIKE(UPPER(TRIM(m.source_value)), '^[A-Z][A-Z0-9_]{0,49}$')
               THEN UPPER(TRIM(m.source_value))
           ELSE CONCAT('AUTO_', UPPER(SUBSTRING(SHA2(CONCAT(m.dict_code, CHAR(0), m.source_value), 256), 1, 45)))
       END AS item_code,
       m.display_name,
       CONCAT('外部来源值：', m.source_value),
       m.sort_no,
       1,
       'SYSTEM',
       'SYSTEM',
       0
FROM (
    VALUES
    ROW('DHB_PRODUCT_STATUS', 'T', '正常', 10),
    ROW('DHB_PRODUCT_STATUS', 'F', '回收站', 20),
    ROW('DHB_PRODUCT_STATUS', 'A', '待审', 30),
    ROW('DHB_PRODUCT_STATUS', 'N', '未通过', 40),
    ROW('DHB_PRODUCT_STATUS', 'R', '撤销', 50),
    ROW('DHB_PRODUCT_PUTAWAY', 'T', '上架', 10),
    ROW('DHB_PRODUCT_PUTAWAY', 'F', '下架', 20),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'pending', '待审核', 10),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'wh_up', '待入库', 20),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'wh_half', '部分入库', 30),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_PURCHASE_ORDER_STATUS', 'finished', '已完成', 50),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'oblig', '待付款', 10),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'uncollect', '部分付款', 20),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'paided', '已付款', 30),
    ROW('DHB_PURCHASE_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'stock_up', '待出库', 10),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'cancelled', '已取消', 20),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'refunds', '待退款', 30),
    ROW('DHB_PURCHASE_RETURN_STATUS', 'finished', '已完成', 40),
    ROW('DHB_WAREHOUSING_STATUS', 'throughed', '已审核', 10),
    ROW('DHB_WAREHOUSING_TYPE', '-1', '退货入库', 10),
    ROW('DHB_WAREHOUSING_TYPE', '1', '采购入库', 20),
    ROW('DHB_WAREHOUSING_TYPE', '3', '盘盈入库', 30),
    ROW('DHB_WAREHOUSING_TYPE', '8', '调拨入库', 40),
    ROW('DHB_WAREHOUSING_TYPE', '20', '运营入库', 50),
    ROW('DHB_WAREHOUSING_TYPE', '21', '红冲销售出库', 60),
    ROW('DHB_WAREHOUSING_TYPE', '7', '其他入库', 70),
    ROW('DHB_WAREHOUSING_TYPE', '9', '联营入库', 80),
    ROW('DHB_WAREHOUSE_STATUS', 'T', '正常', 10),
    ROW('DHB_WAREHOUSE_STATUS', 'F', '停用', 20),

    ROW('DHB_CUSTOMER_STATUS', 'T', '正常', 10),
    ROW('DHB_CUSTOMER_STATUS', 'F', '停用', 20),
    ROW('DHB_CUSTOMER_STATUS', 'A', '待激活', 30),
    ROW('DHB_CUSTOMER_STATUS', 'C', '待审核', 40),
    ROW('DHB_CUSTOMER_CLEARING_FORM', 'prepaid', '预付', 10),
    ROW('DHB_CUSTOMER_CLEARING_FORM', 'forward', '现付', 20),
    ROW('DHB_CUSTOMER_CLEARING_FORM', 'postpaid', '后付', 30),
    ROW('DHB_STAFF_STATUS', 'T', '启用', 10),
    ROW('DHB_STAFF_STATUS', 'F', '停用', 20),
    ROW('DHB_STAFF_TYPE', 'salesman', '业务员', 10),
    ROW('DHB_STAFF_TYPE', 'boss', '老板', 20),
    ROW('DHB_STAFF_TYPE', 'indoorwork', '内勤', 30),
    ROW('DHB_STAFF_TYPE', 'driver', '司机', 40),

    ROW('DHB_ORDER_STATUS', 'pricing', '待核价', 10),
    ROW('DHB_ORDER_STATUS', 'pending', '待审核', 20),
    ROW('DHB_ORDER_STATUS', 'stock_up', '待出库', 30),
    ROW('DHB_ORDER_STATUS', 'stockup', '待出库', 40),
    ROW('DHB_ORDER_STATUS', 'shipped', '待发货', 50),
    ROW('DHB_ORDER_STATUS', 'received', '待收货', 60),
    ROW('DHB_ORDER_STATUS', 'finished', '已完成', 70),
    ROW('DHB_ORDER_STATUS', 'forcedone', '强制完成', 80),
    ROW('DHB_ORDER_STATUS', 'cancelled', '已取消', 90),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'oblig', '待收款', 10),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'uncollect', '部分收款', 20),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'paided', '已收款', 30),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'wait', '待确认', 50),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'part', '部分确认', 60),
    ROW('DHB_ORDER_PAYMENT_STATUS', 'unoblig', '待确认付款', 70),
    ROW('DHB_ORDER_TYPE', 'normal', '普通订单', 10),
    ROW('DHB_ORDER_TYPE', 'C', '经销商提交', 20),
    ROW('DHB_ORDER_TYPE', 'M', '管理端代提交', 30),
    ROW('DHB_ORDER_TYPE', 'S', '业务员代提交', 40),
    ROW('DHB_ORDER_API_STATUS', 'F', '未下载', 10),
    ROW('DHB_ORDER_API_STATUS', 'T', '已下载', 20),
    ROW('DHB_ORDER_EXCEPTION_STATUS', 'F', '正常', 10),
    ROW('DHB_ORDER_EXCEPTION_STATUS', 'T', '异常', 20),
    ROW('DHB_ORDER_ADMIN_FLAG', 'T', '管理端下单', 10),
    ROW('DHB_ORDER_ADMIN_FLAG', 'F', '订货端下单', 20),
    ROW('DHB_SETTLEMENT_METHOD', 'prepaid', '预付', 10),
    ROW('DHB_SETTLEMENT_METHOD', 'forward', '现付', 20),
    ROW('DHB_SETTLEMENT_METHOD', 'postpaid', '后付', 30),
    ROW('DHB_INVOICE_TYPE', 'P', '普通发票', 10),
    ROW('DHB_INVOICE_TYPE', 'Z', '增值税发票', 20),
    ROW('DHB_INVOICE_TYPE', 'F', '无发票', 30),
    ROW('DHB_ORDER_LINE_TYPE', 'c', '正常售卖', 10),
    ROW('DHB_ORDER_LINE_TYPE', 'g', '赠品', 20),
    ROW('DHB_GOODS_LIST_TYPE', 'buy', '买', 10),
    ROW('DHB_GOODS_LIST_TYPE', 'gift', '赠', 20),
    ROW('DHB_SHIPMENT_STATUS', 'shipped', '待发货', 10),
    ROW('DHB_SHIPMENT_STATUS', 'receivedin', '待收货', 20),
    ROW('DHB_SHIPMENT_STATUS', 'received', '已收货', 30),
    ROW('DHB_SHIPMENT_STATUS', 'cancelled', '已取消', 40),
    ROW('DHB_SHIPMENT_TYPE', '-2', '采购退货', 10),
    ROW('DHB_SHIPMENT_TYPE', '10', '销售出库', 20),
    ROW('DHB_SHIPMENT_TYPE', '11', '盘亏出库', 30),
    ROW('DHB_SHIPMENT_TYPE', '17', '其他出库', 40),
    ROW('DHB_SHIPMENT_TYPE', '18', '调拨出库', 50),
    ROW('DHB_SHIPMENT_TYPE', '19', '联营出库', 60),
    ROW('DHB_RETURN_STATUS', 'return_audit', '待退货审核', 10),
    ROW('DHB_RETURN_STATUS', 'shipp_cust', '待客户发货', 20),
    ROW('DHB_RETURN_STATUS', 'shipped', '待收货', 30),
    ROW('DHB_RETURN_STATUS', 'refunded', '待退款', 40),
    ROW('DHB_RETURN_STATUS', 'finished', '已完成', 50),
    ROW('DHB_RETURN_STATUS', 'cancelled', '已取消', 60),
    ROW('DHB_RETURN_TYPE', '0', '未确认', 10),
    ROW('DHB_RETURN_TYPE', '1', '退货退款', 20),
    ROW('DHB_RETURN_TYPE', '2', '仅退款', 30),
    ROW('DHB_FINANCIAL_DOCUMENT_TYPE', 'RECEIPT', '收款单', 10),
    ROW('DHB_FINANCIAL_DOCUMENT_TYPE', 'PAYMENT', '付款单', 20),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '1', '普通充值', 10),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '19', '预付款充值', 20),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '13', '订单收款', 30),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '8', '期初充值', 40),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '2', '退货退款', 50),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '10', '退款失败回冲', 60),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '9', '退款红冲', 70),
    ROW('DHB_FINANCIAL_BUSINESS_TYPE', '5', '预存款扣款', 80),
    ROW('DHB_FINANCIAL_STATUS', 'pend_receipt', '待确认', 10),
    ROW('DHB_FINANCIAL_STATUS', 'pend_receipted', '已确认', 20),
    ROW('DHB_FINANCIAL_STATUS', 'canceled', '已取消', 30),
    ROW('DHB_PAYMENT_METHOD', 'Alipay', '支付宝支付（原生）', 10),
    ROW('DHB_PAYMENT_METHOD', 'Quick', '快捷支付', 20),
    ROW('DHB_PAYMENT_METHOD', 'Micro', '微信支付（原生）', 30),
    ROW('DHB_PAYMENT_METHOD', 'Offline', '转账支付', 40),
    ROW('DHB_PAYMENT_METHOD', 'Deposit', '预存款支付', 50),
    ROW('DHB_PAYMENT_METHOD', 'Delivery', '货到付款', 60),
    ROW('DHB_PAYMENT_METHOD', 'Credit', '赊销支付', 70),
    ROW('DHB_PAYMENT_METHOD', 'Rebate', '返利支付', 80),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Alipay', '支付宝支付', 90),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Wechat', '微信支付', 100),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Quick', '银联快捷', 110),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Netbank', '网银支付', 120),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Wechat', 'iOS移动管理端微信支付', 130),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Ios_Zhongjin_Alipay', 'iOS移动管理端支付宝支付', 140),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Wechat', 'Android移动管理端微信支付', 150),
    ROW('DHB_PAYMENT_METHOD', 'App_Admin_Android_Zhongjin_Alipay', 'Android移动管理端支付宝支付', 160),
    ROW('DHB_PAYMENT_METHOD', 'Zhongjin_Account_Bank_Transfer', '中金来账识别', 170),
    ROW('DHB_PAYMENT_METHOD', 'Mybank_Cloud', '支付宝云资金', 180),
    ROW('DHB_PAYMENT_METHOD', 'Refund_Fail_Recharge', '退款失败回充', 190),
    ROW('DHB_PAYMENT_METHOD', 'Mse', '微企付支付', 200),
    ROW('DHB_PAYMENT_METHOD', 'Hht', '品牌资金账户', 210),
    ROW('DHB_PAYMENT_METHOD', 'Alipay_Transfer', '支付宝转账', 220),
    ROW('DHB_PAYMENT_METHOD', 'WECHAT_B2B_DIRECT', '微信B2B支付', 230),
    ROW('DHB_PAYMENT_METHOD', 'MYBANK_BALANCE', '云资金余额支付', 240),
    ROW('DHB_PAYMENT_METHOD', 'Refund_Red_Reversal', '退款红冲', 250),
    ROW('DHB_PAYMENT_METHOD', 'Yw_Pay', '云闪付', 260),
    ROW('DHB_PAYMENT_METHOD', 'Tp_Pay', '通企付', 270),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_MINIAPP', '微信支付（通企付）', 280),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY', '支付宝支付（通企付）', 290),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_QR', '吱口令（通企付）', 300),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_YT_PAY', '云闪付（通企付）', 310),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_QUICK', '银联快捷（通企付）', 320),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_FRIEND', '微信好友代付（通企付）', 330),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_WX_SCAN_POS', '微信扫码支付（通企付）', 340),
    ROW('DHB_PAYMENT_METHOD', 'TPPAY_UNIFIED_ALIPAY_SCAN_POS', '支付宝扫码支付（通企付）', 350)
) AS m(dict_code, source_value, display_name, sort_no)
JOIN data_dictionary d ON d.dictionary_code = m.dict_code AND d.deleted = 0
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary d
SET d.revision = d.revision + 1,
    d.updated_by = 'SYSTEM'
WHERE EXISTS (
    SELECT 1
      FROM data_dictionary_item i
     WHERE i.dictionary_code = d.dictionary_code
       AND i.updated_by = 'SYSTEM'
       AND i.deleted = 0
)
  AND d.dictionary_code LIKE 'DHB\_%' ESCAPE '\\';

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V11__extend_erp_purchase_return_dictionaries.sql
-- 补齐 ERP 新方案运行所需字典项。
-- 业务表只存我方字典项编码；订货宝来源状态仍保留在来源字典和同步绑定中。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('PURCHASE_RETURN_STATUS', '采购退货状态', 'ERP', '采购退货单创建、出库、退款、完成与取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'PIECE', '件', '通用件装/单件商品单位', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_STATUS', 1, NULL, 'CANCELLED', '已取消', '采购单已取消', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'DRAFT', '草稿', '采购退货单保存未确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'WAIT_STOCK_OUT', '待出库', '采购退货单等待退货出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'REFUND_PENDING', '待退款', '采购退货单等待供应商退款或冲账', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'COMPLETED', '已完成', '采购退货单已完成', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_RETURN_STATUS', 1, NULL, 'CANCELLED', '已取消', '采购退货单已取消', 50, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V12__extend_order_shipment_dictionaries.sql
-- 补齐 Order 发货单新方案所需字典。
-- 业务表只保存我方字典项编码；订货宝发货状态在同步层映射后再写入业务表。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_SHIPMENT_STATUS', '销售发货单状态', 'ORDER', '销售发货单创建、发货、签收、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'CREATED', '已创建', '发货单已生成，等待实际发货', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'SHIPPED', '已发货', '发货单已交付物流或配送人员', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'SIGNED', '已签收', '客户已签收', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_SHIPMENT_STATUS', 1, NULL, 'CANCELLED', '已取消', '发货单已取消', 40, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V13__extend_order_payment_method_items.sql
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

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V14__extend_order_refund_dictionaries.sql
-- Business Settings V14：销售退款状态字典。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_REFUND_STATUS', '销售退款状态', 'ORDER', '销售退款待确认、已确认、已取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
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
    ('SALES_REFUND_STATUS', 1, NULL, 'PENDING', '待确认', '退款记录已生成，等待确认', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_REFUND_STATUS', 1, NULL, 'CONFIRMED', '已确认', '退款已确认并影响订单实收金额', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_REFUND_STATUS', 1, NULL, 'CANCELLED', '已取消', '退款已取消，不影响订单实收金额', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V15__extend_order_fund_document_dictionaries.sql
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

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V16__extend_product_unit_bed.sql
-- 补齐订货宝真实来源单位“床”，供商品和订单同步落我方 PRODUCT_UNIT。

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'BED', '床', '台呢、底布等按床计量的商品单位', 60, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;


-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V17__extend_product_unit_pair.sql
-- 补齐订货宝真实来源单位“副”，供商品和订单同步落我方 PRODUCT_UNIT。

INSERT INTO data_dictionary_item
    (dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
     dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'PAIR', '副', '球杆皮头等按副计量的商品单位', 70, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V18__extend_erp_stock_out_type_items.sql
-- 扩展 ERP 统一出库单类型，支持订货宝 getShipsList 除销售/调拨外的出库类型先落出库单。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('STOCK_OUT_TYPE', 1, NULL, 'PURCHASE_RETURN', '采购退货出库',
     '订货宝采购退货出库，暂先落ERP统一出库单', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'INVENTORY_LOSS', '盘亏出库',
     '订货宝盘亏出库，暂先落ERP统一出库单', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'OTHER', '其他出库',
     '订货宝其他出库，暂先落ERP统一出库单', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', 1, NULL, 'JOINT_OPERATION', '联营出库',
     '订货宝联营出库，暂先落ERP统一出库单', 60, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_name = VALUES(dictionary_item_name),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_level = VALUES(dictionary_item_level),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V19__correct_crm_customer_status_dictionary.sql
-- CRM 自研客户状态与 merchant-crm 服务枚举对齐：停用统一使用 INACTIVE。
-- V8 曾将 CUSTOMER_STATUS 停用项写成 DISABLED，导致 Portal 筛选发出的状态码无法被 CRM 服务接受。

UPDATE data_dictionary_item
SET dictionary_item_name = '停用',
    remark = '客户暂不可用于新业务',
    ordinal = 20,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'CUSTOMER_STATUS'
  AND dictionary_item_code = 'INACTIVE';

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('CUSTOMER_STATUS', 1, NULL, 'INACTIVE', '停用', '客户暂不可用于新业务', 20, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary_item
SET dictionary_item_level = 1,
    parent_dictionary_item_code = NULL,
    dictionary_item_name = '停用',
    remark = '已迁移为 CUSTOMER_STATUS/INACTIVE',
    ordinal = 20,
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'CUSTOMER_STATUS'
  AND dictionary_item_code = 'DISABLED';

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V20__seed_verified_dhb_unit_dictionary_items.sql
-- 补齐订货宝来源计量单位原始值映射。
-- base_units/middle_units/container_units 是单位层级字段名，不是计量单位名称，不在此处建项。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('DHB_UNIT', 1, NULL, 'PIECE', '件', '订货宝来源单位原值：件', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BOX', '箱', '订货宝来源单位原值：箱', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BUCKET', '桶', '订货宝来源单位原值：桶', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'PORTION', '份', '订货宝来源单位原值：份', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'SET', '套', '订货宝来源单位原值：套', 50, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BED', '床', '订货宝来源单位原值：床', 60, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'PAIR', '副', '订货宝来源单位原值：副', 70, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'BOTTLE', '瓶', '订货宝来源单位原值：瓶', 80, 1, 'SYSTEM', 'SYSTEM', 0),
    ('DHB_UNIT', 1, NULL, 'STRIP', '条', '订货宝来源单位原值：条', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'DHB_UNIT';

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V21__extend_product_unit_bottle_and_strip.sql
-- 补齐订货宝真实来源单位“瓶”“条”，供商品、订单和出入库同步落我方 PRODUCT_UNIT。

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code, dictionary_item_code,
    dictionary_item_name, remark, ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('PRODUCT_UNIT', 1, NULL, 'BOTTLE', '瓶', '瓶装商品单位', 80, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_UNIT', 1, NULL, 'STRIP', '条', '条状或条目计量商品单位', 90, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'PRODUCT_UNIT';

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V22__extend_erp_stock_in_return_type.sql
-- 增加 ERP 入库类型“退货入库”，避免订货宝 type_id=-1 被归入采购入库。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('STOCK_IN_TYPE', '入库类型', 'ERP', '采购入库、调拨入库、退货入库', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES ('STOCK_IN_TYPE', 1, NULL, 'RETURN', '退货入库', '订货宝退货产生的入库凭证', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'STOCK_IN_TYPE';

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V23__extend_order_sales_order_status_dictionary.sql
-- 增加 Order 销售订单状态字典，避免内部状态码在业务页面直接展示。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('SALES_ORDER_STATUS', '销售订单状态', 'ORDER', '销售订单草稿、提交、取消状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('SALES_ORDER_STATUS', 1, NULL, 'DRAFT', '草稿', '销售订单保存未提交', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_ORDER_STATUS', 1, NULL, 'SUBMITTED', '已提交', '销售订单已提交，可进入出库流程', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('SALES_ORDER_STATUS', 1, NULL, 'CANCELLED', '已取消', '销售订单已取消', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'SALES_ORDER_STATUS';

-- BEGIN services/rigour-business-settings-service/business-settings-service/src/main/resources/db/migration/V24__correct_order_outbound_status_dictionary.sql
-- 修正 Order 销售订单出库状态字典编码，与后端 SalesOrderOutboundStatus 枚举保持一致。
-- V8 曾写入 PARTIAL/COMPLETED/SHORTAGE，但当前订单服务只接受 PENDING/PARTIAL_OUT/OUT_CONFIRMED。
INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('OUTBOUND_STATUS', '出库状态', 'ORDER', '销售订单出库状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0;

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('OUTBOUND_STATUS', 1, NULL, 'PENDING', '待出库', '销售订单尚未生成出库结果', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'PARTIAL_OUT', '部分出库', '销售订单已有部分商品出库', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('OUTBOUND_STATUS', 1, NULL, 'OUT_CONFIRMED', '已出库', '销售订单已完成出库确认', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0;

UPDATE data_dictionary_item
SET remark = '已迁移为 OUTBOUND_STATUS/PARTIAL_OUT',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'PARTIAL';

UPDATE data_dictionary_item
SET remark = '已迁移为 OUTBOUND_STATUS/OUT_CONFIRMED',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'COMPLETED';

UPDATE data_dictionary_item
SET remark = '当前销售订单出库状态机未使用该状态',
    updated_by = 'SYSTEM',
    deleted = 1
WHERE dictionary_code = 'OUTBOUND_STATUS'
  AND dictionary_item_code = 'SHORTAGE';

UPDATE data_dictionary
SET revision = revision + 1,
    updated_by = 'SYSTEM',
    deleted = 0
WHERE dictionary_code = 'OUTBOUND_STATUS';

SELECT 'DICTIONARY_AFTER_RESEED' AS marker, COUNT(*) AS dictionary_count FROM data_dictionary WHERE deleted = 0;
SELECT 'DICTIONARY_ITEM_AFTER_RESEED' AS marker, COUNT(*) AS item_count FROM data_dictionary_item WHERE deleted = 0;
