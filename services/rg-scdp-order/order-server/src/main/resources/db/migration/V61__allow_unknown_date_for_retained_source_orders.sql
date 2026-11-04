-- 来源删除通知可能只保留订单号，不伪造下单日期；有效订单仍由服务校验日期。
ALTER TABLE order_sales_order MODIFY order_date DATETIME(6) NULL COMMENT '销售日期，失效来源缺失时为空';
