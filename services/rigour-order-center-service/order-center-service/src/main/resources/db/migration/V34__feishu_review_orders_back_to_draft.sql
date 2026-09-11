-- 飞书导入的待补充订单必须保持草稿状态，便于业务人员补录或删除。
-- 已取消单保留取消状态，避免重新开放已经被业务关闭的单据。

UPDATE order_sales_order
SET order_status_code = 'DRAFT',
    revision = revision + 1,
    updated_by = 'SYSTEM',
    updated_time = CURRENT_TIMESTAMP(6)
WHERE deleted = 0
  AND source_system_code = 'FEISHU'
  AND data_quality_status_code = 'NEEDS_REVIEW'
  AND order_status_code NOT IN ('DRAFT', 'CANCELLED');
