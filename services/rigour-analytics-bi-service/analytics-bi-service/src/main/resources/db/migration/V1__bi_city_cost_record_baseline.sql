-- Analytics BI：城市端成本看板快照表。
--
-- 当前 city-operations 服务尚未落城市成本主业务表，本表先作为 BI 读模型/快照承接成本看板。
-- 它不作为城市运营业务主表；后续城市运营主数据或导入链路完成后，应通过 ETL/同步写入本表。

CREATE TABLE bi_city_cost_record (
    id                  BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)    NOT NULL COMMENT '租户ID',
    region_code         VARCHAR(64)    NOT NULL COMMENT '城市/区域编码，关联REGION字典项',
    region_name         VARCHAR(120)   NULL COMMENT '城市/区域名称快照',
    cost_type_code      VARCHAR(64)    NOT NULL COMMENT '成本类型编码，如MARKETING/MATERIAL/LOGISTICS/OPERATION',
    cost_type_name      VARCHAR(120)   NULL COMMENT '成本类型名称快照',
    cost_date           DATETIME(6)    NOT NULL COMMENT '成本发生或归属时间',
    cost_amount         DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '成本金额',
    budget_amount       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '预算金额',
    source_system_code  VARCHAR(32)    NULL COMMENT '来源系统编码，如FEISHU_IMPORT/CITY_OPERATION',
    source_record_id    VARCHAR(120)   NULL COMMENT '来源记录ID，用于导入去重和追溯',
    remark              VARCHAR(1000)  NULL COMMENT '备注',
    revision            INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by          VARCHAR(50)    NULL COMMENT '创建人',
    created_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)    NULL COMMENT '更新人',
    updated_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted             INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_city_cost_source (tenant_id, source_system_code, source_record_id),
    KEY idx_bi_city_cost_region_date (tenant_id, region_code, cost_date),
    KEY idx_bi_city_cost_type_date (tenant_id, cost_type_code, cost_date),
    KEY idx_bi_city_cost_updated (tenant_id, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI城市端成本快照表';
