CREATE TABLE order_logistics_import_order (
    id VARCHAR(36) NOT NULL COMMENT '导入记录ID',
    tenant_id VARCHAR(36) NOT NULL COMMENT '租户ID',
    enterprise_id VARCHAR(80) NOT NULL DEFAULT '' COMMENT '货拉拉企业账号ID，原报表提供时保留，精简模板无需填写',
    carrier_order_no VARCHAR(80) NOT NULL COMMENT '货拉拉订单号，完整文本保存',
    file_name VARCHAR(200) NOT NULL COMMENT '首次导入报表名称',
    file_hash VARCHAR(64) NOT NULL COMMENT '原始报表SHA256',
    source_json JSON NOT NULL COMMENT '货拉拉原始字段及规范化金额和日期',
    state VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED' COMMENT 'UNMATCHED待关联、GENERATED已生成待确认费用',
    cost_id VARCHAR(36) NULL COMMENT '生成的物流成本记录ID',
    revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by VARCHAR(50) NOT NULL COMMENT '导入人',
    created_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '导入时间',
    updated_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_logistics_import_order (tenant_id,carrier_order_no),
    KEY ix_logistics_import_actor (tenant_id,created_by,state,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='货拉拉报表订单待关联记录';
