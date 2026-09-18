-- 订货宝订单域增量同步游标；只在Order Center完成业务幂等落库后推进。
CREATE TABLE order_dhb_sync_checkpoint (
    id                 CHAR(36)     NOT NULL COMMENT '同步游标主键，UUID',
    tenant_id          VARCHAR(64)  NOT NULL COMMENT '租户ID，来自可信调用上下文',
    connector_id       CHAR(36)     NOT NULL COMMENT 'Integration中的订货宝连接器UUID',
    object_type        VARCHAR(32)  NOT NULL COMMENT '同步对象：ORDER；后续可扩展SHIPMENT、RETURN、RECEIPT、PAYMENT',
    last_success_at    DATETIME(6)  NULL COMMENT '最近一次完整业务落库成功的窗口结束时间，UTC；首次同步为空',
    last_run_id        CHAR(36)     NULL COMMENT '最近一次同步运行ID，用于跨服务追踪',
    sync_status        VARCHAR(16)  NOT NULL COMMENT '最近运行状态：IDLE、SUCCEEDED、FAILED',
    last_run_at        DATETIME(6)  NULL COMMENT '最近一次运行时间，UTC',
    last_error         VARCHAR(1000) NULL COMMENT '最近一次失败信息；不得保存凭据和Token',
    created_at         DATETIME(6)  NOT NULL COMMENT '本地创建时间，UTC',
    updated_at         DATETIME(6)  NOT NULL COMMENT '本地最后更新时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_dhb_checkpoint (tenant_id, connector_id, object_type),
    KEY idx_order_dhb_checkpoint_status (sync_status, last_run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='订货宝订单域增量同步游标和运行状态';
