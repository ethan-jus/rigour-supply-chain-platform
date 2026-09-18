-- Integration Schema V5：为已有启用连接器补齐默认商品主数据同步目标。
-- ERP 只通过该任务发现连接器；订货宝协议、凭据和 Raw Landing 仍归 Integration 所有。

INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_PRODUCT_MASTER_DEFAULT',
       'PRODUCT_MASTER_DATA', 'IDLE', NULL, 1,
       UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id = c.tenant_id
   AND t.connector_id = c.id
   AND t.object_type = 'PRODUCT_MASTER_DATA'
   AND t.deleted_at IS NULL
 WHERE c.status = 'ACTIVE'
   AND c.deleted_at IS NULL
   AND t.id IS NULL;
