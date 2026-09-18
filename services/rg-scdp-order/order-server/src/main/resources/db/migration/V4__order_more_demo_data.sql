-- 订单中心V4：补充不同来源状态的本地演示订单，验证订货单列表分页和状态展示。
-- 仅写入内部订单、明细、发货快照和来源报文，不写入Transactional Outbox。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-03 09:30:00.000000');

INSERT INTO order_order (
    id, tenant_id, order_no, source_system, source_order_no, internal_status, source_status,
    payment_status, order_type, total_amount, ordered_at, source_updated_at, source_update_time,
    delivery_date, remark, source_customer_no, source_customer_guid, customer_name, receiver_name,
    receiver_company, receiver_phone, receiver_address, province, city, district, source_api_status,
    source_exception_status, source_send_type, source_last_order_at, source_device, source_admin_order,
    split_type, split_type_name, source_payload_hash, detail_synced_at, imported_at, synced_at,
    version, created_at, updated_at
) VALUES
    (
        '019fc000-0000-7000-8000-000000000004', @tenant_id, 'DH.DEMO.20260803.0004', 'DINGHUOBAO',
        'DH.DEMO.20260803.0004', 'PENDING_CONFIRMATION', 'pricing', 'uncollect', 'normal', 88.00,
        '2026-08-03 09:05:00.000000', '2026-08-03 09:08:00.000000', '2026-08-03 09:08:00', '2026-08-06',
        '本地开发演示订单-待报价', 'C-DEMO-004', 'DHB-CUSTOMER-DEMO-004', '深圳演示客户', '赵六',
        '深圳演示门店', '13800000004', '广东省深圳市南山区演示路4号', '广东省', '深圳市', '南山区',
        'T', 'F', '快递', '2026-08-03 09:05:00', 'demo-portal', 'F', '0', '不拆单',
        'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', NULL, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000005', @tenant_id, 'DH.DEMO.20260802.0005', 'DINGHUOBAO',
        'DH.DEMO.20260802.0005', 'COMPLETED', 'received', 'paided', 'normal', 760.00,
        '2026-08-02 11:10:00.000000', '2026-08-02 19:00:00.000000', '2026-08-02 19:00:00', '2026-08-04',
        '本地开发演示订单-已完成', 'C-DEMO-005', 'DHB-CUSTOMER-DEMO-005', '西安演示客户', '孙七',
        '西安演示门店', '13800000005', '陕西省西安市雁塔区演示路5号', '陕西省', '西安市', '雁塔区',
        'T', 'F', '物流', '2026-08-02 11:10:00', 'demo-portal', 'F', '0', '不拆单',
        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', @seed_at, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000006', @tenant_id, 'DH.DEMO.20260801.0006', 'DINGHUOBAO',
        'DH.DEMO.20260801.0006', 'CANCELLED', 'cancelled', 'uncollect', 'normal', 45.00,
        '2026-08-01 16:25:00.000000', '2026-08-01 16:40:00.000000', '2026-08-01 16:40:00', '2026-08-03',
        '本地开发演示订单-已取消', 'C-DEMO-006', 'DHB-CUSTOMER-DEMO-006', '绵阳演示客户', '周八',
        '绵阳演示门店', '13800000006', '四川省绵阳市涪城区演示路6号', '四川省', '绵阳市', '涪城区',
        'T', 'F', '快递', '2026-08-01 16:25:00', 'demo-portal', 'F', '0', '不拆单',
        'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', NULL, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    );

INSERT INTO order_order_line (
    id, order_id, source_line_id, source_product_guid, sku_no, source_options_goods_no, source_barcode,
    product_name, product_code, specification_first, specification_second, specification_name,
    unit_price, quantity, line_amount, unit, remark, created_at, updated_at
) VALUES
    ('019fc001-0000-7000-8000-000000000005', '019fc000-0000-7000-8000-000000000004', 'DEMO-LINE-0005',
     'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'GOODS-DEMO-005', '690000000005', '演示纸品E', 'DEMO-005',
     '标准装', '箱装', '标准装/箱装', 44.00, 2, 88.00, '箱', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000006', '019fc000-0000-7000-8000-000000000005', 'DEMO-LINE-0006',
     'DHB-PRODUCT-DEMO-006', 'SKU-DEMO-006', 'GOODS-DEMO-006', '690000000006', '演示日用品F', 'DEMO-006',
     '家庭装', '箱装', '家庭装/箱装', 76.00, 10, 760.00, '箱', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000007', '019fc000-0000-7000-8000-000000000006', 'DEMO-LINE-0007',
     'DHB-PRODUCT-DEMO-007', 'SKU-DEMO-007', 'GOODS-DEMO-007', '690000000007', '演示清洁用品G', 'DEMO-007',
     '小规格', '袋装', '小规格/袋装', 15.00, 3, 45.00, '袋', '测试明细', @seed_at, @seed_at);

INSERT INTO order_order_shipment (
    id, order_id, source_shipment_no, status, shipment_date, stock_up_time, created_at, updated_at
) VALUES
    ('019fc002-0000-7000-8000-000000000002', '019fc000-0000-7000-8000-000000000005',
     'DH-DEMO-SHIP-0005', 'COMPLETED', '2026-08-02 20:10:00', '2026-08-02 19:20:00', @seed_at, @seed_at);

INSERT INTO order_source_record (
    id, tenant_id, order_id, source_system, source_order_no, payload_type, payload_json, payload_hash, received_at
) VALUES
    ('019fc003-0000-7000-8000-000000000004', @tenant_id, '019fc000-0000-7000-8000-000000000004',
     'DINGHUOBAO', 'DH.DEMO.20260803.0004', 'LIST',
     '{"OrderSN":"DH.DEMO.20260803.0004","OrderStatus":"pricing","OrderTotal":"88.00","ClientName":"深圳演示客户"}',
     'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', @seed_at),
    ('019fc003-0000-7000-8000-000000000005', @tenant_id, '019fc000-0000-7000-8000-000000000005',
     'DINGHUOBAO', 'DH.DEMO.20260802.0005', 'LIST',
     '{"OrderSN":"DH.DEMO.20260802.0005","OrderStatus":"received","OrderTotal":"760.00","ClientName":"西安演示客户"}',
     'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', @seed_at),
    ('019fc003-0000-7000-8000-000000000006', @tenant_id, '019fc000-0000-7000-8000-000000000006',
     'DINGHUOBAO', 'DH.DEMO.20260801.0006', 'LIST',
     '{"OrderSN":"DH.DEMO.20260801.0006","OrderStatus":"cancelled","OrderTotal":"45.00","ClientName":"绵阳演示客户"}',
     'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff', @seed_at);
