-- Analytics BI：飞书旧看板退场归档元信息。
--
-- 本表只登记归档文件和核对报告元信息，不保存飞书明细数据，不参与正式 BI 趋势。

CREATE TABLE bi_feishu_legacy_archive (
    id                         BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                  VARCHAR(64)   NOT NULL COMMENT '租户ID',
    archive_code               VARCHAR(64)   NOT NULL COMMENT '归档批次编码',
    table_id                   VARCHAR(120)  NOT NULL COMMENT '飞书多维表格tableId',
    view_id                    VARCHAR(120)  NULL COMMENT '飞书视图ID',
    table_name                 VARCHAR(120)  NOT NULL COMMENT '飞书表名称快照',
    file_name                  VARCHAR(255)  NOT NULL COMMENT '导出文件名',
    file_format                VARCHAR(16)   NOT NULL COMMENT '导出格式：CSV/XLSX',
    exported_by                VARCHAR(100)  NOT NULL COMMENT '导出人',
    exported_time              DATETIME(6)   NOT NULL COMMENT '导出时间',
    frozen_time                DATETIME(6)   NOT NULL COMMENT '飞书冻结时间',
    record_count               BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '导出记录数',
    checksum_sha256            CHAR(64)      NOT NULL COMMENT '导出文件SHA-256',
    storage_uri                VARCHAR(500)  NULL COMMENT '归档文件存储位置',
    field_mapping_uri          VARCHAR(500)  NULL COMMENT '字段映射说明位置',
    reconciliation_report_uri  VARCHAR(500)  NULL COMMENT '一次性核对报告位置',
    archive_status_code        VARCHAR(32)   NOT NULL DEFAULT 'ARCHIVED' COMMENT '状态：ARCHIVED/VERIFIED/REJECTED',
    remark                     VARCHAR(1000) NULL COMMENT '备注',
    created_time               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_time               DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                    INT           NOT NULL DEFAULT 0 COMMENT '删除标识',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bi_feishu_legacy_archive_code (tenant_id, archive_code),
    UNIQUE KEY uk_bi_feishu_legacy_archive_file (tenant_id, table_id, view_id, checksum_sha256),
    KEY idx_bi_feishu_legacy_archive_exported (tenant_id, exported_time, deleted),
    KEY idx_bi_feishu_legacy_archive_status (tenant_id, archive_status_code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='BI飞书旧看板归档元信息';
