-- 订单中心V3：本地开发租户的只读演示订单。
--
-- 这些数据用于验证 Portal 点击“订货单”后调用 GET /api/v1/orders/dhb 的分页查询链路。
-- 数据直接落在内部订单模型和来源报文表，不写入 order_outbox_event，避免演示数据触发ERP、库存、客户或BI下游事件。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-03 09:00:00.000000');

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
        '019fc000-0000-7000-8000-000000000001', @tenant_id, 'DH.DEMO.20260803.0001', 'DINGHUOBAO',
        'DH.DEMO.20260803.0001', 'PENDING_CONFIRMATION', 'pending', 'uncollect', 'normal', 268.00,
        '2026-08-03 08:35:00.000000', '2026-08-03 08:36:10.000000', '2026-08-03 08:36:10', '2026-08-05',
        '本地开发演示订单-待审核', 'C-DEMO-001', 'DHB-CUSTOMER-DEMO-001', '成都演示客户', '张三',
        '成都演示门店', '13800000001', '四川省成都市高新区演示路1号', '四川省', '成都市', '高新区',
        'T', 'F', '快递', '2026-08-03 08:35:00', 'demo-portal', 'F', '0', '不拆单',
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', NULL, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000002', @tenant_id, 'DH.DEMO.20260803.0002', 'DINGHUOBAO',
        'DH.DEMO.20260803.0002', 'ALLOCATING', 'stock_up', 'paided', 'normal', 512.50,
        '2026-08-02 14:20:00.000000', '2026-08-02 15:02:00.000000', '2026-08-02 15:02:00', '2026-08-04',
        '本地开发演示订单-备货中', 'C-DEMO-002', 'DHB-CUSTOMER-DEMO-002', '重庆演示客户', '李四',
        '重庆演示门店', '13800000002', '重庆市渝中区演示街2号', '重庆市', '重庆市', '渝中区',
        'T', 'F', '物流', '2026-08-02 14:20:00', 'demo-portal', 'F', '0', '不拆单',
        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', @seed_at, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    ),
    (
        '019fc000-0000-7000-8000-000000000003', @tenant_id, 'DH.DEMO.20260801.0003', 'DINGHUOBAO',
        'DH.DEMO.20260801.0003', 'SHIPPED', 'shipped', 'part', 'normal', 1199.90,
        '2026-08-01 10:05:00.000000', '2026-08-01 18:30:00.000000', '2026-08-01 18:30:00', '2026-08-03',
        '本地开发演示订单-已发货', 'C-DEMO-003', 'DHB-CUSTOMER-DEMO-003', '德阳演示客户', '王五',
        '德阳演示门店', '13800000003', '四川省德阳市旌阳区演示路3号', '四川省', '德阳市', '旌阳区',
        'T', 'F', '专车配送', '2026-08-01 10:05:00', 'demo-portal', 'F', '0', '不拆单',
        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', @seed_at, @seed_at, @seed_at,
        0, @seed_at, @seed_at
    );

INSERT INTO order_order_line (
    id, order_id, source_line_id, source_product_guid, sku_no, source_options_goods_no, source_barcode,
    product_name, product_code, specification_first, specification_second, specification_name,
    unit_price, quantity, line_amount, unit, remark, created_at, updated_at
) VALUES
    ('019fc001-0000-7000-8000-000000000001', '019fc000-0000-7000-8000-000000000001', 'DEMO-LINE-0001',
     'DHB-PRODUCT-DEMO-001', 'SKU-DEMO-001', 'GOODS-DEMO-001', '690000000001', '演示饮料A', 'DEMO-001',
     '500ml', '整箱', '500ml/整箱', 128.00, 2, 256.00, '箱', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000002', '019fc000-0000-7000-8000-000000000001', 'DEMO-LINE-0002',
     'DHB-PRODUCT-DEMO-002', 'SKU-DEMO-002', 'GOODS-DEMO-002', '690000000002', '演示零食B', 'DEMO-002',
     '100g', '袋装', '100g/袋装', 12.00, 1, 12.00, '袋', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000003', '019fc000-0000-7000-8000-000000000002', 'DEMO-LINE-0003',
     'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'GOODS-DEMO-003', '690000000003', '演示调味品C', 'DEMO-003',
     '1kg', '桶装', '1kg/桶装', 102.50, 5, 512.50, '桶', '测试明细', @seed_at, @seed_at),
    ('019fc001-0000-7000-8000-000000000004', '019fc000-0000-7000-8000-000000000003', 'DEMO-LINE-0004',
     'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004', 'GOODS-DEMO-004', '690000000004', '演示粮油D', 'DEMO-004',
     '5L', '桶装', '5L/桶装', 239.98, 5, 1199.90, '桶', '测试明细', @seed_at, @seed_at);

INSERT INTO order_order_shipment (
    id, order_id, source_shipment_no, status, shipment_date, stock_up_time, created_at, updated_at
) VALUES
    ('019fc002-0000-7000-8000-000000000001', '019fc000-0000-7000-8000-000000000003',
     'DH-DEMO-SHIP-0003', 'SHIPPED', '2026-08-01 19:10:00', '2026-08-01 18:40:00', @seed_at, @seed_at);

INSERT INTO order_source_record (
    id, tenant_id, order_id, source_system, source_order_no, payload_type, payload_json, payload_hash, received_at
) VALUES
    ('019fc003-0000-7000-8000-000000000001', @tenant_id, '019fc000-0000-7000-8000-000000000001',
     'DINGHUOBAO', 'DH.DEMO.20260803.0001', 'LIST',
     '{"OrderSN":"DH.DEMO.20260803.0001","OrderStatus":"pending","OrderTotal":"268.00","ClientName":"成都演示客户"}',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', @seed_at),
    ('019fc003-0000-7000-8000-000000000002', @tenant_id, '019fc000-0000-7000-8000-000000000002',
     'DINGHUOBAO', 'DH.DEMO.20260803.0002', 'LIST',
     '{"OrderSN":"DH.DEMO.20260803.0002","OrderStatus":"stock_up","OrderTotal":"512.50","ClientName":"重庆演示客户"}',
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', @seed_at),
    ('019fc003-0000-7000-8000-000000000003', @tenant_id, '019fc000-0000-7000-8000-000000000003',
     'DINGHUOBAO', 'DH.DEMO.20260801.0003', 'LIST',
     '{"OrderSN":"DH.DEMO.20260801.0003","OrderStatus":"shipped","OrderTotal":"1199.90","ClientName":"德阳演示客户"}',
     'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', @seed_at);
