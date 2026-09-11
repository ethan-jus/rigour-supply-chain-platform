-- Analytics BI：供应链看板对账快照。
--
-- 普通看板请求读取本表；跨源库对账 SQL 只在 BI 刷新任务中执行并写入本表。

CREATE TABLE bi_supply_reconciliation_current (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    subject_code          VARCHAR(64)    NOT NULL COMMENT '对账对象编码',
    subject_name          VARCHAR(120)   NOT NULL COMMENT '对账对象名称',
    scope_code            VARCHAR(64)    NOT NULL COMMENT '对账范围编码',
    scope_name            VARCHAR(120)   NOT NULL COMMENT '对账范围名称',
    from_time             DATETIME(6)    NOT NULL COMMENT '对账开始时间',
    to_time               DATETIME(6)    NOT NULL COMMENT '对账结束时间',
    source_row_count      BIGINT(20)     NOT NULL DEFAULT 0 COMMENT '来源Raw数量',
    business_row_count    BIGINT(20)     NOT NULL DEFAULT 0 COMMENT '业务主表数量',
    bi_row_count          BIGINT(20)     NOT NULL DEFAULT 0 COMMENT 'BI表数量',
    source_amount         DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '来源金额',
    business_amount       DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '业务主表金额',
    bi_amount             DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT 'BI金额',
    observed_time         DATETIME(6)    NOT NULL COMMENT '快照生成时间',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_supply_reconciliation_scope (tenant_id, subject_code, scope_code),
    KEY idx_bi_supply_reconciliation_observed (tenant_id, observed_time, deleted),
    KEY idx_bi_supply_reconciliation_status (tenant_id, subject_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI供应链当前对账快照';
