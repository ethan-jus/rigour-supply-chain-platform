CREATE TABLE hr_target_completion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    target_month DATE NOT NULL,
    dimension_type VARCHAR(32) NOT NULL,
    dimension_code VARCHAR(64) NOT NULL,
    metric_code VARCHAR(64) NOT NULL,
    completed_value DECIMAL(24,6) NULL COMMENT 'NULL恢复自动计算；0为手工完成零',
    completed_through DATE NOT NULL COMMENT '完成值截止日期',
    revision INT NOT NULL,
    updated_by VARCHAR(50) NOT NULL,
    updated_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_completion (tenant_id,target_month,dimension_type,dimension_code,metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='月度指标手工完成值';

CREATE TABLE hr_target_completion_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    completion_id BIGINT NOT NULL,
    revision INT NOT NULL,
    completed_value DECIMAL(24,6) NULL,
    completed_through DATE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_completion_event (tenant_id,completion_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标完成值修订记录';
