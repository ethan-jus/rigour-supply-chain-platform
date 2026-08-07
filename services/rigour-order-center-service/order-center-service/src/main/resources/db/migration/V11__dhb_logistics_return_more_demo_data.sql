-- 订单中心V11：补充本地开发租户的出库/发货物流、退货单及明细演示数据。
-- 覆盖已发货、已收货、待出库，以及待客户发货、待收货、已取消等状态筛选场景。

SET @tenant_id = '019fb000-0000-7000-8000-000000000002';
SET @seed_at = TIMESTAMP('2026-08-06 09:30:00.000000');

INSERT INTO order_dhb_shipment_logistics (
    id, tenant_id, source_system, order_no, shipment_no, source_status, logistics_name,
    logistics_code, tracking_no, shipment_at, stock_up_at, warehouse_no, warehouse_name,
    shipped_count, wait_stock_count, raw_json, payload_hash, synced_at, created_at, updated_at
) VALUES
    (
        '019fd040-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260803.0001',
        'DH.DEMO.SHIP.0003', 'shipped', '京东物流', 'JD', 'JD-DEMO-0003',
        '2026-08-06 08:40:00.000000', '2026-08-06 08:20:00.000000', 'WH-DEMO-003', '深圳前置仓',
        1, 0,
        '{"shipped":[{"ships_num":"DH.DEMO.SHIP.0003","status":"shipped","express_num":"JD-DEMO-0003"}],"wait_stock":[]}',
        '9999999999999999999999999999999999999999999999999999999999999999', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd040-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260803.0004',
        'DH.DEMO.SHIP.0004', 'received', '圆通速递', 'YTO', 'YTO-DEMO-0004',
        '2026-08-04 16:30:00.000000', '2026-08-04 16:00:00.000000', 'WH-DEMO-004', '深圳中心仓',
        1, 0,
        '{"shipped":[{"ships_num":"DH.DEMO.SHIP.0004","status":"received","express_num":"YTO-DEMO-0004"}],"wait_stock":[]}',
        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd040-0000-7000-8000-000000000003', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.20260802.0005',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'WH-DEMO-005', '西安分仓',
        0, 2,
        '{"shipped":[],"wait_stock":[{"orders_list_id":"DH.DEMO.ORDER.LINE.0005A","wait_stock_number":"4"},{"orders_list_id":"DH.DEMO.ORDER.LINE.0005B","wait_stock_number":"2"}]}',
        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_shipment_logistics_line (
    id, logistics_id, line_type, shipment_no, source_line_id, order_line_id, product_id, sku_no,
    list_type, product_code, product_name, specification, unit, container_unit, conversion_number,
    quantity, ordered_quantity, stocked_quantity, real_stock, wait_quantity, warehouse_no,
    warehouse_name, remark, created_at, updated_at
) VALUES
    (
        '019fd041-0000-7000-8000-000000000001', '019fd040-0000-7000-8000-000000000001',
        'SHIPPED', 'DH.DEMO.SHIP.0003', 'DHB.DEMO.SHIP.LINE.0003', 'DHB.DEMO.ORDER.LINE.0001',
        'DHB-PRODUCT-DEMO-001', 'SKU-DEMO-001', 'buy', 'DEMO-001', '演示饮料A', '整箱', '箱', '托', 12,
        3, NULL, NULL, NULL, NULL, 'WH-DEMO-003', '深圳前置仓', '已发货物流测试数据', @seed_at, @seed_at
    ),
    (
        '019fd041-0000-7000-8000-000000000002', '019fd040-0000-7000-8000-000000000002',
        'SHIPPED', 'DH.DEMO.SHIP.0004', 'DHB.DEMO.SHIP.LINE.0004', 'DHB.DEMO.ORDER.LINE.0005',
        'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'buy', 'DEMO-005', '演示纸品E', '标准', '箱', '托', 10,
        2, NULL, NULL, NULL, NULL, 'WH-DEMO-004', '深圳中心仓', '已收货物流测试数据', @seed_at, @seed_at
    ),
    (
        '019fd041-0000-7000-8000-000000000003', '019fd040-0000-7000-8000-000000000003',
        'WAIT_STOCK', '', 'DHB.DEMO.ORDER.LINE.0005A', NULL,
        'DHB-PRODUCT-DEMO-006', 'SKU-DEMO-006', 'buy', 'DEMO-006', '演示日用品F', '标准', '件', '箱', 24,
        NULL, 4, 2, 1, 2, 'WH-DEMO-005', '西安分仓', '待出库物流测试数据', @seed_at, @seed_at
    ),
    (
        '019fd041-0000-7000-8000-000000000004', '019fd040-0000-7000-8000-000000000003',
        'WAIT_STOCK', '', 'DHB.DEMO.ORDER.LINE.0005B', NULL,
        'DHB-PRODUCT-DEMO-007', 'SKU-DEMO-007', 'buy', 'DEMO-007', '演示清洁用品G', '补充装', '件', '箱', 20,
        NULL, 2, 0, 0, 2, 'WH-DEMO-005', '西安分仓', '待出库物流测试数据', @seed_at, @seed_at
    );

INSERT INTO order_dhb_return (
    id, tenant_id, source_system, return_no, order_no, source_status, staff_name, return_amount,
    settlement_amount, returned_at, source_updated_at, reason, customer_no, customer_guid, consignee,
    phone, address, logistics_company, logistics_no, return_type, delivery_mode, raw_json, payload_hash,
    detail_available, synced_at, created_at, updated_at
) VALUES
    (
        '019fd050-0000-7000-8000-000000000001', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0003',
        'DH.DEMO.20260803.0004', 'shipp_cust', '演示经办人-赵六', 88.00, NULL,
        '2026-08-05 14:20:00.000000', '2026-08-05 14:30:00.000000', '客户拒收',
        'C-DEMO-004', 'DHB-CUSTOMER-DEMO-004', '赵六', '13800000004', '深圳市南山区演示路4号',
        NULL, NULL, '1', '上门取件',
        '{"ReturnsSN":"DH.DEMO.RETURN.0003","OrdersNum":"DH.DEMO.20260803.0004","Status":"shipp_cust"}',
        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd050-0000-7000-8000-000000000002', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0004',
        'DH.DEMO.20260802.0005', 'shipped', '演示经办人-孙七', 45.00, NULL,
        '2026-08-04 10:15:00.000000', '2026-08-05 08:10:00.000000', '商品质量问题',
        'C-DEMO-005', 'DHB-CUSTOMER-DEMO-005', '孙七', '13800000005', '陕西省西安市演示路5号',
        '顺丰速运', 'SF-RETURN-DEMO-0004', '1', '快递寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0004","OrdersNum":"DH.DEMO.20260802.0005","Status":"shipped"}',
        'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', 1, @seed_at, @seed_at, @seed_at
    ),
    (
        '019fd050-0000-7000-8000-000000000003', @tenant_id, 'DINGHUOBAO', 'DH.DEMO.RETURN.0005',
        'DH.DEMO.20260802.0005', 'cancelled', '演示经办人-周八', 760.00, 0.00,
        '2026-08-02 09:00:00.000000', '2026-08-02 09:20:00.000000', '客户取消退货',
        'C-DEMO-005', 'DHB-CUSTOMER-DEMO-005', '周八', '13800000006', '陕西省西安市演示路6号',
        NULL, NULL, '1', '无需寄回',
        '{"ReturnsSN":"DH.DEMO.RETURN.0005","OrdersNum":"DH.DEMO.20260802.0005","Status":"cancelled"}',
        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee', 1, @seed_at, @seed_at, @seed_at
    );

INSERT INTO order_dhb_return_line (
    id, return_id, source_line_id, source_product_guid, sku_no, product_code, product_name,
    quantity, confirmed_quantity, unit_price, confirmed_price, unit_name, warehouse_no, warehouse_name,
    remark, created_at, updated_at
) VALUES
    (
        '019fd051-0000-7000-8000-000000000001', '019fd050-0000-7000-8000-000000000001',
        'DHB.DEMO.RETURN.LINE.0003', 'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'DEMO-005', '演示纸品E',
        1, NULL, 88.00, NULL, '箱', 'WH-DEMO-004', '深圳中心仓', '待客户发货退货测试数据', @seed_at, @seed_at
    ),
    (
        '019fd051-0000-7000-8000-000000000002', '019fd050-0000-7000-8000-000000000002',
        'DHB.DEMO.RETURN.LINE.0004', 'DHB-PRODUCT-DEMO-006', 'SKU-DEMO-006', 'DEMO-006', '演示日用品F',
        2, 1, 45.00, 45.00, '件', 'WH-DEMO-005', '西安分仓', '待收货退货测试数据', @seed_at, @seed_at
    ),
    (
        '019fd051-0000-7000-8000-000000000003', '019fd050-0000-7000-8000-000000000003',
        'DHB.DEMO.RETURN.LINE.0005', 'DHB-PRODUCT-DEMO-005', 'SKU-DEMO-005', 'DEMO-005', '演示纸品E',
        5, 0, 152.00, 0.00, '箱', 'WH-DEMO-005', '西安分仓', '已取消退货测试数据', @seed_at, @seed_at
    );
