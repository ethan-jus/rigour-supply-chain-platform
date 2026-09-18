-- ERP 同步批次记录本次使用的字典内容版本和未映射来源枚举。
-- 未映射不阻断来源数据落库，批次使用 SUCCEEDED_WITH_WARNINGS 明确提示人工治理。
ALTER TABLE erp_master_data_sync_run
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'
        COMMENT '批次状态：RUNNING/SUCCEEDED/SUCCEEDED_WITH_WARNINGS/FAILED',
    ADD COLUMN unmapped_count BIGINT NOT NULL DEFAULT 0
        COMMENT '已落库但未找到唯一有效字典映射的来源枚举出现次数'
        AFTER rejected_count,
    ADD COLUMN dict_snapshot_json JSON NULL
        COMMENT '本批次使用的字典编码及整本内容版本'
        AFTER unmapped_count,
    ADD COLUMN mapping_issues_json JSON NULL
        COMMENT '按字典、字段和来源值聚合的未映射项，不含完整来源报文'
        AFTER dict_snapshot_json;
