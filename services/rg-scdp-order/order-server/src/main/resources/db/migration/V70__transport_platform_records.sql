ALTER TABLE order_logistics_import_order
 ADD COLUMN business_json JSON NULL COMMENT '人工维护运输类型、业务关联、负责人及备注，与平台事实分离',
 ADD COLUMN bill_history_json JSON NULL COMMENT '成功账单变化前快照，确认依据可追溯';
CREATE TABLE order_logistics_transport_link (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户', source_id VARCHAR(36) NOT NULL COMMENT '平台来源记录',
 order_type VARCHAR(32) NOT NULL COMMENT '运输类型编码', order_code VARCHAR(100) NOT NULL COMMENT '业务单号',
 updated_by VARCHAR(50) NOT NULL COMMENT '维护人', updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '维护时间',
 PRIMARY KEY(tenant_id,source_id,order_type,order_code), KEY ix_transport_link_order(tenant_id,order_type,order_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='平台运输与业务单据关联，独立于费用确认';
