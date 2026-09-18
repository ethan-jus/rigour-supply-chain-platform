-- 订货宝连接器级分布式租约：统一约束ERP、CRM、Order及多实例对同一连接器的并发访问。
CREATE TABLE integration_connector_sync_lease (
    id            BINARY(16)   NOT NULL COMMENT '租约主键UUID',
    tenant_id     BINARY(16)   NOT NULL COMMENT '租户UUID',
    connector_id  BINARY(16)   NOT NULL COMMENT '订货宝连接器UUID',
    lease_token   CHAR(36)     NOT NULL COMMENT '本次持有者随机令牌，释放和续租必须精确匹配',
    owner_id      VARCHAR(128) NOT NULL COMMENT '持有租约的领域服务标识，仅用于审计',
    acquired_at   DATETIME(6)  NOT NULL COMMENT 'UTC首次获取时间',
    heartbeat_at  DATETIME(6)  NOT NULL COMMENT 'UTC最近续租时间',
    expires_at    DATETIME(6)  NOT NULL COMMENT 'UTC租约过期时间；进程退出后可自动回收',
    PRIMARY KEY (id),
    CONSTRAINT uk_integration_connector_sync_lease UNIQUE (tenant_id, connector_id),
    KEY idx_integration_connector_sync_lease_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Integration订货宝连接器同步分布式租约';
