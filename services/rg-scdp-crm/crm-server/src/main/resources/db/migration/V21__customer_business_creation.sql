-- 真实建档时间用于月度新增统计；系统 created_time 保留实际入库审计。
ALTER TABLE crm_customer
    ADD COLUMN business_created_at DATETIME(6) NULL COMMENT '客户原始建档时间 UTC，未知不猜测',
    ADD COLUMN business_created_by_id VARCHAR(128) NULL COMMENT '原始创建人来源标识',
    ADD COLUMN business_created_by_name VARCHAR(160) NULL COMMENT '原始创建人姓名快照',
    ADD COLUMN business_creation_source VARCHAR(32) NULL COMMENT 'FEISHU DINGHUOBAO INTERNAL，空表示待核实',
    ADD INDEX idx_customer_business_created (tenant_id, deleted, business_created_at);

-- 仅本系统人工新建记录可由入库审计还原；外部导入需核对原始客户记录。
UPDATE crm_customer SET business_created_at=created_time,
    business_created_by_id=created_by, business_creation_source='INTERNAL'
WHERE source_system_code IS NULL AND created_by NOT IN ('SYSTEM', 'system', 'DINGHUOBAO');
