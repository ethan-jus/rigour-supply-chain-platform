-- 采购业务改为租户+编码关联；保留旧列供存量入库、同步和分析契约渐进兼容。
ALTER TABLE erp_procurement_order
    ADD COLUMN confirmation_status_code VARCHAR(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'UNCONFIRMED' COMMENT '采购确认状态：UNCONFIRMED未确认、CONFIRMED已确认，确认后不可编辑或删除',
    ADD COLUMN supplier_code VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '供应商编码，关联同租户供应商档案',
    ADD COLUMN target_warehouse_code VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '计划入库仓库编码，关联同租户仓库',
    ADD COLUMN purchase_date DATE NULL COMMENT '采购日期，新单由应用默认当天',
    ADD COLUMN freight_amount DECIMAL(24,2) NULL COMMENT '约定或预估运费；NULL待定，0明确零运费',
    ADD COLUMN freight_bearer_code VARCHAR(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'BUYER' COMMENT '运费承担编码：BUYER本公司、SUPPLIER供应商、INCLUDED货价已含',
    ADD COLUMN contract_no VARCHAR(100) COLLATE utf8mb4_general_ci NULL COMMENT '采购合同编号',
    ADD COLUMN contract_file_key VARCHAR(500) COLLATE utf8mb4_general_ci NULL COMMENT '当前采购合同附件COS文件键',
    ADD COLUMN receiver_name VARCHAR(100) COLLATE utf8mb4_general_ci NULL COMMENT '本单收货人，新采购必填，可从仓库带入后修改',
    ADD COLUMN receiver_phone VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '本单收货联系电话，新采购必填',
    ADD COLUMN receiving_address VARCHAR(1000) COLLATE utf8mb4_general_ci NULL COMMENT '本单收货地址，新采购必填，随单保存',
    MODIFY COLUMN supplier_id BIGINT(20) NULL COMMENT '旧供应商ID，仅兼容存量数据，新采购按编码关联',
    MODIFY COLUMN target_warehouse_id BIGINT(20) NULL COMMENT '旧仓库ID，仅兼容存量数据，新采购按编码关联',
    ADD KEY idx_erp_procurement_supplier_code (tenant_id, supplier_code, purchase_date),
    ADD KEY idx_erp_procurement_warehouse_code (tenant_id, target_warehouse_code, deleted);

UPDATE erp_procurement_order d
LEFT JOIN erp_supplier_profile s ON s.tenant_id=d.tenant_id AND s.id=d.supplier_id
LEFT JOIN erp_inventory_warehouse w ON w.tenant_id=d.tenant_id AND w.id=d.target_warehouse_id
SET d.supplier_code=s.supplier_code, d.target_warehouse_code=w.warehouse_code;
UPDATE erp_procurement_order SET confirmation_status_code='CONFIRMED'
WHERE status_code IN ('SUBMITTED','PARTIAL_IN','COMPLETED');
-- 不从创建时间补造历史业务日期，不用当前商品换算配置回填历史采购数量。

ALTER TABLE erp_procurement_order_line
    ADD COLUMN procurement_no VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '采购订单号，关联同租户采购主表',
    ADD COLUMN product_code VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '商品编码，名称查询主档展示',
    ADD COLUMN variant_code VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT 'SKU编码，规格查询主档展示',
    ADD COLUMN base_unit_code VARCHAR(64) COLLATE utf8mb4_general_ci NULL COMMENT '下单时商品基本单位编码',
    ADD COLUMN conversion_rate DECIMAL(24,6) NULL COMMENT '下单时1采购单位对应的基础数量',
    MODIFY COLUMN procurement_order_id BIGINT(20) NULL COMMENT '旧采购订单ID，仅兼容存量关联',
    MODIFY COLUMN product_id BIGINT(20) NULL COMMENT '旧商品ID，仅兼容存量关联',
    MODIFY COLUMN product_variant_id BIGINT(20) NULL COMMENT '旧SKU ID，仅兼容存量关联',
    MODIFY COLUMN line_no INT NULL COMMENT '旧行号，新采购不再保存',
    MODIFY COLUMN product_name_snapshot VARCHAR(200) COLLATE utf8mb4_general_ci NULL COMMENT '旧商品名称快照，新采购不再保存',
    MODIFY COLUMN received_quantity DECIMAL(24,6) NULL DEFAULT NULL COMMENT '旧已入库累计值，新采购从有效入库记录查询',
    ADD KEY idx_erp_procurement_line_code (tenant_id, procurement_no, variant_code, deleted);

UPDATE erp_procurement_order_line l
LEFT JOIN erp_procurement_order d ON d.tenant_id=l.tenant_id AND d.id=l.procurement_order_id
LEFT JOIN erp_product p ON p.tenant_id=l.tenant_id AND p.id=l.product_id
LEFT JOIN erp_product_variant v ON v.tenant_id=l.tenant_id AND v.id=l.product_variant_id
SET l.procurement_no=d.procurement_no,
    l.product_code=COALESCE(p.product_code,l.product_code_snapshot),
    l.variant_code=COALESCE(v.variant_code,l.variant_code_snapshot);

ALTER TABLE erp_stock_in_order_line
    ADD COLUMN procurement_no VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '来源采购订单号，关联同租户采购单',
    ADD COLUMN procurement_variant_code VARCHAR(50) COLLATE utf8mb4_general_ci NULL COMMENT '来源采购SKU编码，与采购单号共同定位明细',
    ADD KEY idx_erp_stock_in_procurement_code (tenant_id, procurement_no, procurement_variant_code, deleted);

UPDATE erp_stock_in_order_line r
JOIN erp_procurement_order_line l ON l.tenant_id=r.tenant_id AND l.id=r.procurement_order_line_id
SET r.procurement_no=l.procurement_no, r.procurement_variant_code=l.variant_code;

CREATE INDEX idx_erp_procurement_confirmation ON erp_procurement_order (tenant_id,confirmation_status_code,purchase_date,deleted);
