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
    ('STOCK_IN_TYPE', '入库类型', 'ERP', '采购入库、调拨入库', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_IN_STATUS', '入库单状态', 'ERP', '入库单保存、确认、取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_TYPE', '出库类型', 'ERP', '销售出库、调拨出库', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STOCK_OUT_STATUS', '出库单状态', 'ERP', '出库单保存、确认、取消状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('TRANSFER_STATUS', '调拨单状态', 'ERP', '调拨单出库与入库确认状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_STATUS', '客户状态', 'CRM', '客户启用、停用状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_SETTLEMENT_TYPE', '客户结算类型', 'CRM', 'A类商家、B类商家等结算分类', 1, 'SYSTEM', 'SYSTEM', 0),

    ('ORDER_TYPE', '销售订单类型', 'ORDER', '新拓、复购等销售订单类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_METHOD', '付款方式', 'ORDER', '全款、月结等付款方式', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PAYMENT_STATUS', '付款状态', 'ORDER', '销售订单回款状态', 1, 'SYSTEM', 'SYSTEM', 0),
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
