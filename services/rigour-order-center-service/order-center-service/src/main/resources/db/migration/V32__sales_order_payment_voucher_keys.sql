ALTER TABLE order_sales_order
    ADD COLUMN payment_voucher_keys_json JSON NULL COMMENT '订单级付款凭证COS key数组，来自飞书付款凭证字段';
