-- Analytics BI：补充业务维度名称快照。
--
-- 城市、客户类型等编码用于过滤和幂等，业务看板展示优先使用名称快照。

ALTER TABLE bi_customer_dim
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

ALTER TABLE bi_sales_order_fact
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

ALTER TABLE bi_sales_payment_fact
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;

ALTER TABLE bi_sales_order_line_fact
    ADD COLUMN region_name VARCHAR(160) NULL COMMENT '城市/区域名称快照' AFTER region_code,
    ADD COLUMN customer_type_name VARCHAR(160) NULL COMMENT '客户类型名称快照' AFTER customer_type_code;
