-- 飞书历史订单允许先落为草稿，缺失的内部映射由业务人员在销售订单页面补齐。

ALTER TABLE order_sales_order
    ADD COLUMN data_quality_status_code VARCHAR(64) NOT NULL DEFAULT 'COMPLETE'
        COMMENT '数据质量状态：COMPLETE完整，NEEDS_REVIEW待业务补齐' AFTER source_creator_name,
    ADD COLUMN data_quality_message VARCHAR(1000) NULL
        COMMENT '数据质量说明，记录导入后待补齐字段' AFTER data_quality_status_code,
    MODIFY COLUMN customer_id BIGINT(20) NULL COMMENT 'CRM客户ID，跨服务引用；飞书导入草稿允许待补齐',
    MODIFY COLUMN customer_name_snapshot VARCHAR(200) NULL COMMENT '下单时客户名称快照；飞书导入草稿允许待补齐';

ALTER TABLE order_sales_order_line
    MODIFY COLUMN product_id BIGINT(20) NULL COMMENT 'ERP商品ID，跨服务引用；飞书导入草稿允许待补齐',
    MODIFY COLUMN product_variant_id BIGINT(20) NULL COMMENT 'ERP商品规格ID，跨服务引用；飞书导入草稿允许待补齐',
    MODIFY COLUMN product_name_snapshot VARCHAR(200) NULL COMMENT '商品名称快照；飞书导入草稿允许待补齐',
    MODIFY COLUMN unit_code VARCHAR(64) NULL COMMENT '订货单位，关联 PRODUCT_UNIT 字典项；飞书导入草稿允许待补齐';

CREATE INDEX idx_order_sales_data_quality
    ON order_sales_order (tenant_id, data_quality_status_code, order_date);

UPDATE order_sales_order target
SET target.data_quality_status_code = CASE
        WHEN target.customer_id IS NULL
            OR target.customer_name_snapshot IS NULL
            OR target.customer_name_snapshot = ''
            OR EXISTS (
                SELECT 1
                FROM order_sales_order_line line
                WHERE line.tenant_id = target.tenant_id
                  AND line.order_id = target.id
                  AND line.deleted = 0
                  AND (
                      line.product_id IS NULL
                      OR line.product_variant_id IS NULL
                      OR line.unit_code IS NULL
                      OR line.unit_code = ''
                      OR line.product_name_snapshot IS NULL
                      OR line.product_name_snapshot = ''
                  )
            )
        THEN 'NEEDS_REVIEW'
        ELSE 'COMPLETE'
    END,
    target.data_quality_message = CASE
        WHEN target.customer_id IS NULL
            OR target.customer_name_snapshot IS NULL
            OR target.customer_name_snapshot = ''
            OR EXISTS (
                SELECT 1
                FROM order_sales_order_line line
                WHERE line.tenant_id = target.tenant_id
                  AND line.order_id = target.id
                  AND line.deleted = 0
                  AND (
                      line.product_id IS NULL
                      OR line.product_variant_id IS NULL
                      OR line.unit_code IS NULL
                      OR line.unit_code = ''
                      OR line.product_name_snapshot IS NULL
                      OR line.product_name_snapshot = ''
                  )
            )
        THEN '历史订单存在缺失引用，请在销售订单页面补齐客户、商品、规格或单位'
        ELSE NULL
    END
WHERE target.deleted = 0;
