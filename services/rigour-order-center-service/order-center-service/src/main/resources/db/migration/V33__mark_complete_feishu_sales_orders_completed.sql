-- 完整的飞书历史订单不需要业务人员再提交，只有待完善订单继续保留为草稿。
UPDATE order_sales_order
SET order_status_code = 'COMPLETED',
    updated_by = 'SYSTEM',
    updated_time = UTC_TIMESTAMP(6)
WHERE source_system_code = 'FEISHU'
  AND data_quality_status_code = 'COMPLETE'
  AND order_status_code = 'DRAFT'
  AND deleted = 0;
