-- 来源映射更新后按版本处理历史运输。已有映射从版本 0 开始回填。
ALTER TABLE integration_dictionary_mapping
    ADD COLUMN carrier_backfill_revision INT NOT NULL DEFAULT 0
        COMMENT '最近完成历史运输回填的映射版本';
