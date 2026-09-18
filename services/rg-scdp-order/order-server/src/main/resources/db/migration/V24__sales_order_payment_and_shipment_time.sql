ALTER TABLE order_sales_order
    ADD COLUMN payment_time DATETIME(6) NULL COMMENT '付款时间，来自关联资金付款单',
    ADD COLUMN shipment_time DATETIME(6) NULL COMMENT '发货时间，来自关联销售发货单或销售出库确认',
    ADD KEY idx_order_sales_payment_time (tenant_id, payment_time),
    ADD KEY idx_order_sales_shipment_time (tenant_id, shipment_time);

UPDATE order_sales_order o
JOIN (
    SELECT tenant_id, related_order_id, MAX(occurred_time) AS payment_time
    FROM order_fund_document
    WHERE deleted = 0
      AND direction_code = 'PAYMENT'
      AND related_order_id IS NOT NULL
      AND document_status_code <> 'CANCELLED'
    GROUP BY tenant_id, related_order_id
) p ON p.tenant_id = o.tenant_id AND p.related_order_id = o.id
SET o.payment_time = p.payment_time
WHERE o.deleted = 0;

UPDATE order_sales_order o
JOIN (
    SELECT tenant_id, sales_order_id, MAX(ship_time) AS shipment_time
    FROM order_sales_shipment
    WHERE deleted = 0
      AND sales_order_id IS NOT NULL
      AND ship_time IS NOT NULL
      AND shipment_status_code <> 'CANCELLED'
    GROUP BY tenant_id, sales_order_id
) s ON s.tenant_id = o.tenant_id AND s.sales_order_id = o.id
SET o.shipment_time = s.shipment_time
WHERE o.deleted = 0;
