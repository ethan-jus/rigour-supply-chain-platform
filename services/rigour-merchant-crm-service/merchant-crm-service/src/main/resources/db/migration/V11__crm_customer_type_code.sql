-- CRM V11：自研客户主表补客户类型编码。
--
-- 业务口径：
-- 1. customer_type_code 保存我们自己的客户类型编码，关联 crm_customer_type.type_code。
-- 2. 订货宝客户类型仅作为来源同步映射，不直接成为客户主表的外部字段。
-- 3. 列表、订单等跨服务引用只传业务编码，展示名称由 CRM 主数据或前端缓存转换。

ALTER TABLE crm_customer
    ADD COLUMN customer_type_code VARCHAR(64) NULL COMMENT '客户类型编码，关联CRM客户类型主数据type_code'
        AFTER contact_phone;

CREATE INDEX idx_crm_customer_type
    ON crm_customer (tenant_id, customer_type_code);
