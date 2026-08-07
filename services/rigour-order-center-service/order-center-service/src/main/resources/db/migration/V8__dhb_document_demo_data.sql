-- 订单中心V8：本地开发租户的单据演示数据。
--
-- 仅用于验证 Portal 的出库单、发货单、退货单、收款单和付款单只读查询链路；
-- 数据使用 demo-portal 语义，不写入 Outbox，也不触发下游业务事件。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-05 10:00:00.000000');

INSERT INTO order_dhb_shipment (
    id, tenant_id, source_system, source_shipment_id, shipment_no, order_no, source_status,
    source_status_name, source_type_id, source_type_name, customer_no, customer_name, customer_guid,
    warehouse_no, warehouse_name, warehouse_guid, shipment_at, logistics_name, tracking_no, remark,
    source_created_at, source_updated_at, raw_json, payload_hash, detail_available, synced_at, created_at, updated_at
) VALUES
    (
        '019fd000-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DHB-SHIP-ID-0001',
        'DH.DEMO.SHIP.0001', 'DH.DEMO.20260803.0002', 'shipped', '待发货', '10', '销售出库',
        'C-DEMO-002', '重庆演示客户', 'DHB-CUSTOMER-DEMO-002', 'WH-DEMO-001', '成都中心仓',
        'DHB-WH-DEMO-001', NULL, '顺丰速运', NULL, '出库单演示数据-待发货',
        '2026-08-05 09:20:00.000000', '2026-08-05 09:25:00.000000',
        '{"ships_num":"DH.DEMO.SHIP.0001","orders_num":"DH.DEMO.20260803.0002","status":"shipped"}',
        '1111111111111111111111111111111111111111111111111111111111111111', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd000-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DHB-SHIP-ID-0002',
        'DH.DEMO.SHIP.0002', 'DH.DEMO.20260801.0003', 'received', '已收货', '10', '销售出库',
        'C-DEMO-003', '德阳演示客户', 'DHB-CUSTOMER-DEMO-003', 'WH-DEMO-002', '德阳分仓',
        'DHB-WH-DEMO-002', '2026-08-04 15:20:00.000000', '中通快递', 'ZTO-DEMO-0002', '出库单演示数据-已收货',
        '2026-08-03 09:20:00.000000', '2026-08-04 15:20:00.000000',
        '{"ships_num":"DH.DEMO.SHIP.0002","orders_num":"DH.DEMO.20260801.0003","status":"received"}',
        '2222222222222222222222222222222222222222222222222222222222222222', 1, @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_shipment_line (
    id, shipment_id, source_line_id, source_product_guid, sku_no, product_code, product_name,
    quantity, unit_price, line_amount, unit_name, warehouse_no, remark, created_at, updated_at
) VALUES
    (
        '019fd001-0000-7000-8000-000000000001', '019fd000-0000-7000-8000-000000000001', 'DHB-SHIP-LINE-0001',
        'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'DEMO-003', '演示调味品C', 5, 102.50, 512.50,
        '桶', 'WH-DEMO-001', '出库明细演示数据', @seed_at, @seed_at
    ),
    (
        '019fd001-0000-7000-8000-000000000002', '019fd000-0000-7000-8000-000000000002', 'DHB-SHIP-LINE-0002',
        'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004', 'DEMO-004', '演示粮油D', 5, 239.98, 1199.90,
        '桶', 'WH-DEMO-002', '出库明细演示数据', @seed_at, @seed_at
    );

INSERT INTO order_dhb_return (
    id, tenant_id, source_system, return_no, order_no, source_status, staff_name, return_amount,
    settlement_amount, returned_at, source_updated_at, reason, customer_no, customer_guid, consignee,
    phone, address, logistics_company, logistics_no, return_type, delivery_mode, raw_json, payload_hash,
    detail_available, synced_at, created_at, updated_at
) VALUES
    (
        '019fd010-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0001',
        'DH.DEMO.20260803.0002', 'return_audit', '演示经办人', 102.50, NULL, '2026-08-05 09:40:00.000000',
        '2026-08-05 09:45:00.000000', '商品破损', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '李四',
        '13800000002', '重庆市渝中区演示街2号', NULL, NULL, '1', '快递寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0001","OrdersNum":"DH.DEMO.20260803.0002","Status":"return_audit"}',
        '3333333333333333333333333333333333333333333333333333333333333333', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd010-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0002',
        'DH.DEMO.20260801.0003', 'finished', '演示经办人', 239.98, 239.98, '2026-08-04 11:10:00.000000',
        '2026-08-04 18:00:00.000000', '客户误下单', 'C-DEMO-003', 'DHB-CUSTOMER-DEMO-003', '王五',
        '13800000003', '四川省德阳市旌阳区演示路3号', '中通快递', 'ZTO-RETURN-DEMO-0002', '1', '快递寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0002","OrdersNum":"DH.DEMO.20260801.0003","Status":"finished"}',
        '4444444444444444444444444444444444444444444444444444444444444444', 1, @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_return_line (
    id, return_id, source_line_id, source_product_guid, sku_no, product_code, product_name,
    quantity, confirmed_quantity, unit_price, confirmed_price, unit_name, warehouse_no, warehouse_name,
    remark, created_at, updated_at
) VALUES
    (
        '019fd011-0000-7000-8000-000000000001', '019fd010-0000-7000-8000-000000000001', 'DHB-RETURN-LINE-0001',
        'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'DEMO-003', '演示调味品C', 1, NULL, 102.50, NULL,
        '桶', 'WH-DEMO-001', '成都中心仓', '退货明细演示数据', @seed_at, @seed_at
    ),
    (
        '019fd011-0000-7000-8000-000000000002', '019fd010-0000-7000-8000-000000000002', 'DHB-RETURN-LINE-0002',
        'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004', 'DEMO-004', '演示粮油D', 1, 1, 239.98, 239.98,
        '桶', 'WH-DEMO-002', '德阳分仓', '退货明细演示数据', @seed_at, @seed_at
    );

INSERT INTO order_dhb_financial_document (
    id, tenant_id, source_system, document_type, document_no, related_document_no, order_no, customer_no,
    customer_guid, business_type, payment_method, amount, source_status, transaction_at, source_created_at,
    source_updated_at, serial_number, account_name, bank_name, account_number, remark, raw_json, payload_hash,
    synced_at, created_at, updated_at
) VALUES
    (
        '019fd020-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'RECEIPT', 'DH.DEMO.RECEIPT.0001',
        NULL, 'DH.DEMO.20260803.0002', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '13', '银行转账', 512.50,
        'pend_receipted', '2026-08-05 09:50:00.000000', '2026-08-05 09:50:00.000000', '2026-08-05 09:55:00.000000',
        'DEMO-RECEIPT-SERIAL-0001', '成都演示供应链', '演示银行', '622200000000000001', '收款单演示数据',
        '{"ReceiptsNum":"DH.DEMO.RECEIPT.0001","OrdersNum":"DH.DEMO.20260803.0002","Amount":"512.50"}',
        '5555555555555555555555555555555555555555555555555555555555555555', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd020-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'PAYMENT', 'DH.DEMO.PAYMENT.0001',
        'DH.DEMO.RECEIPT.0001', 'DH.DEMO.20260803.0002', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '2', '原路退款', 100.00,
        'pend_receipt', '2026-08-05 10:00:00.000000', '2026-08-05 10:00:00.000000', '2026-08-05 10:05:00.000000',
        'DEMO-PAYMENT-SERIAL-0001', '成都演示供应链', '演示银行', '622200000000000001', '付款单演示数据',
        '{"PaymentNum":"DH.DEMO.PAYMENT.0001","ReceiptsNum":"DH.DEMO.RECEIPT.0001","Amount":"100.00"}',
        '6666666666666666666666666666666666666666666666666666666666666666', @seed_at, @seed_at, @seed_at
    );
