-- Analytics BI：销售订单行毛利估算字段。
--
-- 成本口径为 ERP 商品规格采购参考价估算，不等同于真实出库成本。

ALTER TABLE bi_sales_order_line_fact
    ADD COLUMN brand_id BIGINT(20) NULL COMMENT '商品品牌ID快照' AFTER product_category_name,
    ADD COLUMN brand_code VARCHAR(50) NULL COMMENT '商品品牌编码快照' AFTER brand_id,
    ADD COLUMN brand_name VARCHAR(120) NULL COMMENT '商品品牌名称快照' AFTER brand_code,
    ADD COLUMN refund_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '订单级退款按订单行金额比例分摊金额' AFTER line_amount,
    ADD COLUMN sales_net_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '销售净收入：订单行金额-退款分摊金额' AFTER refund_amount,
    ADD COLUMN estimated_unit_cost DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '估算单位成本：ERP规格采购参考价' AFTER sales_net_amount,
    ADD COLUMN estimated_cost_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '估算销售成本：数量*估算单位成本' AFTER estimated_unit_cost,
    ADD COLUMN estimated_gross_profit_amount DECIMAL(24,6) NOT NULL DEFAULT 0 COMMENT '估算毛利：销售净收入-估算销售成本' AFTER estimated_cost_amount,
    ADD COLUMN estimated_gross_profit_rate DECIMAL(10,4) NOT NULL DEFAULT 0 COMMENT '估算毛利率' AFTER estimated_gross_profit_amount,
    ADD COLUMN cost_covered TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否存在采购参考价：0否，1是' AFTER estimated_gross_profit_rate,
    ADD KEY idx_bi_sales_order_line_fact_brand (tenant_id, brand_id, order_date),
    ADD KEY idx_bi_sales_order_line_fact_profit (tenant_id, order_date, estimated_gross_profit_amount);
