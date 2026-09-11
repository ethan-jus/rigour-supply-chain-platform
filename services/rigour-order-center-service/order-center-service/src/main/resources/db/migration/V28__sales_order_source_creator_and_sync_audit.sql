-- Order V28：销售订单保留订货宝制单人来源字段，并统一历史同步审计口径。
--
-- 业务口径：
-- 1. source_creator_* 表示来源平台的业务制单人，可匹配到我方员工编码。
-- 2. created_by/updated_by 表示谁写入了我方系统；订货宝同步统一写 SYSTEM，前端枚举展示为“系统同步”。

ALTER TABLE order_sales_order
    ADD COLUMN source_creator_id VARCHAR(80) NULL COMMENT '来源平台制单人ID，如订货宝员工账号ID' AFTER source_status_code,
    ADD COLUMN source_creator_staff_code VARCHAR(50) NULL COMMENT '来源制单人匹配到的我方员工编码' AFTER source_creator_id,
    ADD COLUMN source_creator_name VARCHAR(100) NULL COMMENT '来源制单人名称或我方员工姓名快照' AFTER source_creator_staff_code,
    ADD KEY idx_order_sales_source_creator_staff (tenant_id, source_creator_staff_code, order_date);

UPDATE order_sales_order
SET created_by = CASE
        WHEN created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE created_by
    END,
    updated_by = CASE
        WHEN updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE updated_by
    END
WHERE source_system_code = 'DINGHUOBAO'
  AND (
      created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_sales_order_line l
JOIN order_sales_order o ON o.tenant_id = l.tenant_id AND o.id = l.order_id
SET l.created_by = CASE
        WHEN l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.created_by
    END,
    l.updated_by = CASE
        WHEN l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.updated_by
    END
WHERE o.source_system_code = 'DINGHUOBAO'
  AND (
      l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_payment_record p
JOIN order_sales_order o ON o.tenant_id = p.tenant_id AND o.id = p.order_id
SET p.created_by = CASE
        WHEN p.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE p.created_by
    END,
    p.updated_by = CASE
        WHEN p.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE p.updated_by
    END
WHERE o.source_system_code = 'DINGHUOBAO'
  AND (
      p.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR p.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_refund_record r
JOIN order_sales_order o ON o.tenant_id = r.tenant_id AND o.id = r.order_id
SET r.created_by = CASE
        WHEN r.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE r.created_by
    END,
    r.updated_by = CASE
        WHEN r.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE r.updated_by
    END
WHERE o.source_system_code = 'DINGHUOBAO'
  AND (
      r.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR r.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_sales_shipment s
LEFT JOIN order_sales_order o ON o.tenant_id = s.tenant_id AND o.id = s.sales_order_id
SET s.created_by = CASE
        WHEN s.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE s.created_by
    END,
    s.updated_by = CASE
        WHEN s.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE s.updated_by
    END
WHERE (o.source_system_code = 'DINGHUOBAO'
        OR s.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
        OR s.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system'))
  AND (
      s.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR s.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_sales_shipment_line l
JOIN order_sales_shipment s ON s.tenant_id = l.tenant_id AND s.id = l.shipment_id
LEFT JOIN order_sales_order o ON o.tenant_id = s.tenant_id AND o.id = s.sales_order_id
SET l.created_by = CASE
        WHEN l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.created_by
    END,
    l.updated_by = CASE
        WHEN l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE l.updated_by
    END
WHERE (o.source_system_code = 'DINGHUOBAO'
        OR s.created_by = 'SYSTEM'
        OR s.updated_by = 'SYSTEM')
  AND (
      l.created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR l.updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );

UPDATE order_fund_document
SET created_by = CASE
        WHEN created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE created_by
    END,
    updated_by = CASE
        WHEN updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system') THEN 'SYSTEM'
        ELSE updated_by
    END
WHERE (source_document_no IS NOT NULL OR source_order_no IS NOT NULL)
  AND (
      created_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
      OR updated_by IN ('019fb700-0000-7000-8000-00000000d0b0', 'DHB_SYNC', 'system')
  );
