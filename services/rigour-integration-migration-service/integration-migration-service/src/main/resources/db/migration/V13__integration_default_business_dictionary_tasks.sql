-- Integration V13：为已有启用连接器补齐字典/枚举映射同步目标。
-- 字典同步仍归同步中心统一编排，ERP/CRM/Order 业务页不直接展示订货宝技术字段。
INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_BUSINESS_DICTIONARY_DEFAULT',
       'BUSINESS_DICTIONARY', 'IDLE', NULL, 1,
       UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id = c.tenant_id
   AND t.connector_id = c.id
   AND t.object_type = 'BUSINESS_DICTIONARY'
   AND t.deleted_at IS NULL
 WHERE c.status = 'ACTIVE'
   AND c.deleted_at IS NULL
   AND t.id IS NULL;
