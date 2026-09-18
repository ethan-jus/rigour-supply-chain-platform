-- 订单中心V10：本地开发租户的getWaitShips物流演示数据。
-- V9先建表，本迁移再写入演示快照；生产环境可按环境策略关闭演示数据迁移。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-05 10:00:00.000000');

INSERT INTO order_dhb_shipment_logistics (
    id, tenant_id, source_system, order_no, shipment_no, source_status, logistics_name,
    logistics_code, tracking_no, shipment_at, stock_up_at, warehouse_no, warehouse_name,
    shipped_count, wait_stock_count, raw_json, payload_hash, synced_at, created_at, updated_at
) VALUES
    (
        '019fd030-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260803.0002',
        'DH.DEMO.SHIP.0001', 'receivedin', '顺丰速运', 'SF', 'SF-DEMO-0001',
        '2026-08-05 09:30:00.000000', '2026-08-05 09:20:00.000000', 'WH-DEMO-001', '成都中心仓',
        1, 1,
        '{"shipped":[{"ships_num":"DH.DEMO.SHIP.0001","status":"receivedin"}],"wait_stock":[{"orders_list_id":"DH.DEMO.ORDER.LINE.0002","wait_stock_number":"2"}]}',
        '7777777777777777777777777777777777777777777777777777777777777777', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd030-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260801.0003',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'WH-DEMO-002', '德阳分仓',
        0, 1,
        '{"shipped":[],"wait_stock":[{"orders_list_id":"DH.DEMO.ORDER.LINE.0003","wait_stock_number":"1"}]}',
        '8888888888888888888888888888888888888888888888888888888888888888', @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_shipment_logistics_line (
    id, logistics_id, line_type, shipment_no, source_line_id, order_line_id, product_id, sku_no,
    list_type, product_code, product_name, specification, unit, container_unit, conversion_number,
    quantity, ordered_quantity, stocked_quantity, real_stock, wait_quantity, warehouse_no,
    warehouse_name, remark, created_at, updated_at
) VALUES
    (
        '019fd031-0000-7000-8000-000000000001', '019fd030-0000-7000-8000-000000000001',
        'SHIPPED', 'DH.DEMO.SHIP.0001', 'DH.DEMO.SHIP.LINE.0001', 'DH.DEMO.ORDER.LINE.0001',
        'DHB-PRODUCT-DEMO-003', 'SKU-DEMO-003', 'buy', 'DEMO-003', '演示调味品C', '原味', '桶', '箱', 10,
        5, NULL, NULL, NULL, NULL, 'WH-DEMO-001', '成都中心仓', '已发货物流演示数据', @seed_at, @seed_at
    ),
    (
        '019fd031-0000-7000-8000-000000000002', '019fd030-0000-7000-8000-000000000001',
        'WAIT_STOCK', '', 'DH.DEMO.ORDER.LINE.0002', NULL, 'DHB-PRODUCT-DEMO-004', 'SKU-DEMO-004',
        'buy', 'DEMO-004', '演示粮油D', '标准', '桶', '箱', 10,
        NULL, 3, 1, 5, 2, 'WH-DEMO-001', '成都中心仓', '待出库物流演示数据', @seed_at, @seed_at
    ),
    (
        '019fd031-0000-7000-8000-000000000003', '019fd030-0000-7000-8000-000000000002',
        'WAIT_STOCK', '', 'DH.DEMO.ORDER.LINE.0003', NULL, 'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005',
        'buy', 'DEMO-005', '演示饮料E', '整箱', '箱', '托', 12,
        NULL, 2, 1, 0, 1, 'WH-DEMO-002', '德阳分仓', '待出库物流演示数据', @seed_at, @seed_at
    );
