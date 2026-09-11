-- 订货宝分段手动同步前，恢复同步目标可被手动编排器发现。
-- 执行前必须确认 Integration 定时编排已关闭：
-- RIGOUR_DHB_SYNC_ORCHESTRATION_ENABLED=false，或 Nacos 同名配置为 false。

UPDATE rigour_integration.integration_sync_task task
JOIN rigour_integration.integration_dhb_connector connector
  ON connector.tenant_id = task.tenant_id
 AND connector.id = task.connector_id
 AND connector.deleted_at IS NULL
   SET task.enabled = 1,
       task.task_status = 'IDLE',
       task.schedule_type = 'MANUAL',
       task.next_run_at = NULL,
       task.updated_at = NOW(6)
 WHERE task.deleted_at IS NULL
   AND connector.status = 'ACTIVE'
   AND task.object_type IN (
       'BUSINESS_DICTIONARY',
       'PRODUCT_MASTER_DATA',
       'CRM_MASTER_DATA',
       'SUPPLY_CHAIN_DATA',
       'ORDER'
   );

SELECT
    'manual_sync_target_status' AS section,
    BIN_TO_UUID(task.tenant_id) AS tenant_id,
    BIN_TO_UUID(task.connector_id) AS connector_id,
    task.task_code,
    task.object_type,
    task.task_status,
    task.schedule_type,
    task.enabled,
    task.next_run_at,
    task.updated_at
FROM rigour_integration.integration_sync_task task
JOIN rigour_integration.integration_dhb_connector connector
  ON connector.tenant_id = task.tenant_id
 AND connector.id = task.connector_id
 AND connector.deleted_at IS NULL
WHERE task.deleted_at IS NULL
  AND connector.status = 'ACTIVE'
  AND task.object_type IN (
      'BUSINESS_DICTIONARY',
      'PRODUCT_MASTER_DATA',
      'CRM_MASTER_DATA',
      'SUPPLY_CHAIN_DATA',
      'ORDER'
  )
ORDER BY task.object_type, task.task_code;
