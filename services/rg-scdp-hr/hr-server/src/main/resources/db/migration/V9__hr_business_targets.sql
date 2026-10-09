-- HR 为城市及销售目标的唯一写者；未保存的指标由服务返回初始值。
CREATE TABLE hr_business_target (
    id                  BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)    NOT NULL COMMENT '租户ID',
    target_month        DATE           NOT NULL COMMENT '目标月份，取当月1日',
    dimension_type      VARCHAR(32)    NOT NULL COMMENT '目标维度：CITY/SALES_OWNER',
    dimension_code      VARCHAR(64)    NOT NULL COMMENT '维度编码',
    dimension_name      VARCHAR(160)   NULL COMMENT '维度名称快照',
    metric_code         VARCHAR(64)    NOT NULL COMMENT '指标编码：SALES_AMOUNT/RECEIPT_AMOUNT/NEW_CUSTOMER/REPEAT_CUSTOMER',
    target_value        DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '目标值',
    remark              VARCHAR(1000)  NULL COMMENT '备注',
    revision            INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by          VARCHAR(50)    NULL COMMENT '创建人',
    created_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)    NULL COMMENT '更新人',
    updated_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_business_target_metric (
        tenant_id, target_month, dimension_type, dimension_code, metric_code
    ),
    KEY idx_hr_business_target_dimension (tenant_id, dimension_type, dimension_code, target_month),
    KEY idx_hr_business_target_metric (tenant_id, metric_code, target_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR城市/销售经营目标配置';

CREATE TABLE hr_business_target_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    revision INT NOT NULL,
    target_value DECIMAL(24,6) NOT NULL,
    dimension_name VARCHAR(160) NULL,
    remark VARCHAR(1000) NULL,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_target_event_revision (tenant_id, target_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR经营目标修订记录';

