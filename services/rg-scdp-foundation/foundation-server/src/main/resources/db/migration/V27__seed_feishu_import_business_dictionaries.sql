-- 飞书全量导入需要的业务字典定义。
-- 同步接口只补齐已存在字典下的来源值，因此这里先创建稳定业务枚举定义。

INSERT INTO data_dictionary
    (dictionary_code, dictionary_name, dictionary_type, remark, revision, created_by, updated_by, deleted)
VALUES
    ('EMPLOYEE_STATUS', '员工状态', 'HR', '员工在职、离职等状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('CUSTOMER_SOURCE', '客户来源', 'CRM', '飞书商家来源、渠道来源等客户来源', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_CATEGORY', '客户类目', 'CRM', '飞书商家类目、门店属性等客户分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', '门店状态', 'CRM', '门店合作、暂停、关闭等状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_ATTRIBUTE', '门店属性', 'CRM', '门店属性，如台球、游泳馆、网球等', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_BUSINESS_TYPE', '门店经营类型', 'CRM', '门店经营类型，如竞技赛事、商业娱乐、综合经营', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', '门店规模', 'CRM', '门店面积或规模分层', 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_TAG', '门店标签', 'CRM', '飞书门店标签原文', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_RISK_LEVEL', '风控等级', 'CRM', '商家或门店风控等级', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', '合作等级', 'CRM', '商家合作等级', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_STATUS', '合作状态', 'CRM', '商家签约、合作、暂未合作等状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', '合作意向', 'CRM', '招商留资或拜访门店合作意向', 1, 'SYSTEM', 'SYSTEM', 0),
    ('LEAD_SUBJECT_IDENTITY', '留资主体身份', 'CRM', '招商留资主体身份', 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_FORM', '活动形式', 'CRM', '活动申请形式，如试吃活动、游戏活动', 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', '活动预热周期', 'CRM', '活动申请预热周期', 1, 'SYSTEM', 'SYSTEM', 0),

    ('BUSINESS_LINE', '业务线', 'ERP', '商品、客户和订单关联的业务线', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_INDUSTRY', '商品行业', 'ERP', '飞书产品行业分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_CATEGORY_SOURCE', '来源商品品类', 'ERP', '飞书产品品类原文，用于后续匹配ERP分类', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SOURCE_STATUS', '来源商品状态', 'ERP', '飞书产品状态原文', 1, 'SYSTEM', 'SYSTEM', 0),
    ('MATERIAL_REQUEST_STATUS', '物料申请状态', 'ERP', '飞书物料申请处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('PURCHASE_ORDER_STATUS', '采购订单状态', 'ERP', '飞书采购订单状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('INVENTORY_LOCATION_TYPE', '库存地点类型', 'ERP', '飞书库存盘点地点类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_ISSUE_TYPE', '品控问题类型', 'ERP', '飞书品控反馈问题类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_SEVERITY', '品控严重程度', 'ERP', '飞书品控反馈严重程度', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_DISCOVERY_CHANNEL', '品控发现渠道', 'ERP', '飞书品控问题发现渠道', 1, 'SYSTEM', 'SYSTEM', 0),
    ('QUALITY_PROCESS_STATUS', '品控处理状态', 'ERP', '飞书品控城市处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('MANUFACTURER_COMPENSATION_STATUS', '厂家赔付状态', 'ERP', '飞书品控厂家赔付状态', 1, 'SYSTEM', 'SYSTEM', 0),

    ('INVOICE_STATUS', '发票状态', 'ORDER', '飞书发票处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('SAMPLE_REQUEST_TYPE', '样品申请类型', 'ORDER', '飞书样品申请类型', 1, 'SYSTEM', 'SYSTEM', 0),
    ('SAMPLE_REQUEST_STATUS', '样品申请状态', 'ORDER', '飞书样品申请处理状态', 1, 'SYSTEM', 'SYSTEM', 0),
    ('FELT_PICKUP_PAYMENT_STATUS', '台呢提货付款状态', 'ORDER', '台呢提货单付款状态', 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_name = VALUES(dictionary_name),
    dictionary_type = VALUES(dictionary_type),
    remark = VALUES(remark),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);

INSERT INTO data_dictionary_item (
    dictionary_code, dictionary_item_level, parent_dictionary_item_code,
    dictionary_item_code, dictionary_item_name, remark,
    ordinal, revision, created_by, updated_by, deleted
)
VALUES
    ('EMPLOYEE_STATUS', 1, NULL, 'ACTIVE', '在职', '员工当前在职', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'LEFT', '离职', '员工已离职', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('EMPLOYEE_STATUS', 1, NULL, 'INACTIVE', '停用', '员工暂不参与业务', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SOURCE_STATUS', 1, NULL, 'ON_SALE', '在售', '来源商品在售', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('PRODUCT_SOURCE_STATUS', 1, NULL, 'STOP_SALE', '停售', '来源商品停售', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'ACTIVE', '营业中', '来源门店正常营业', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'PAUSED', '暂停营业', '来源门店暂停营业', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'CLOSED', '倒闭', '来源门店已关闭', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_STATUS', 1, NULL, 'RENOVATING', '装修中', '来源门店装修中', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'HIGH', '高意向', '来源客户高合作意向', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'MEDIUM', '中意向', '来源客户中等合作意向', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'LOW', '低意向', '来源客户低合作意向', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_INTENTION_LEVEL', 1, NULL, 'NONE', '无意向', '来源客户暂无合作意向', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'UNDER_100', '100平米以下', '来源门店面积分层', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'BETWEEN_100_300', '100-300平米', '来源门店面积分层', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'BETWEEN_300_600', '300-600平米', '来源门店面积分层', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('STORE_SCALE', 1, NULL, 'OVER_600', '600平米以上', '来源门店面积分层', 40, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', 1, NULL, 'A', 'A类', '来源客户或门店A类等级', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', 1, NULL, 'B', 'B类', '来源客户或门店B类等级', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('CUSTOMER_COOP_LEVEL', 1, NULL, 'C', 'C类', '来源客户或门店C类等级', 30, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', 1, NULL, 'DAYS_1_3', '1-3天', '活动预热周期', 10, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', 1, NULL, 'DAYS_3_5', '3-5天', '活动预热周期', 20, 1, 'SYSTEM', 'SYSTEM', 0),
    ('ACTIVITY_PREHEAT_PERIOD', 1, NULL, 'DAYS_5_7', '5-7天', '活动预热周期', 30, 1, 'SYSTEM', 'SYSTEM', 0)
ON DUPLICATE KEY UPDATE
    dictionary_item_level = VALUES(dictionary_item_level),
    parent_dictionary_item_code = VALUES(parent_dictionary_item_code),
    dictionary_item_name = VALUES(dictionary_item_name),
    remark = VALUES(remark),
    ordinal = VALUES(ordinal),
    updated_by = 'SYSTEM',
    deleted = 0,
    updated_time = UTC_TIMESTAMP(6);
