-- Integration Schema V4：为已有启用连接器补齐默认订单同步任务。
-- 新连接器由 JdbcDhbIntegrationStore 在同一事务内自动创建默认任务；本迁移只负责存量数据。
-- 已存在任意未删除 ORDER 任务的连接器不重复创建，保留原有暂停/自定义配置。

INSERT INTO integration_sync_task
    (id, tenant_id, connector_id, task_code, object_type, task_status,
     next_run_at, enabled, created_at, created_by, updated_at, updated_by)
SELECT UUID_TO_BIN(UUID()), c.tenant_id, c.id, 'DHB_ORDER_DEFAULT', 'ORDER', 'IDLE',
       NULL, 1, UTC_TIMESTAMP(6), c.created_by, UTC_TIMESTAMP(6), c.updated_by
  FROM integration_dhb_connector c
  LEFT JOIN integration_sync_task t
    ON t.tenant_id = c.tenant_id
   AND t.connector_id = c.id
   AND t.object_type = 'ORDER'
   AND t.deleted_at IS NULL
 WHERE c.status = 'ACTIVE'
   AND c.deleted_at IS NULL
   AND t.id IS NULL;
