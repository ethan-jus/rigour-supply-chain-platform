ALTER TABLE order_logistics_import_order
    ADD COLUMN source_channel VARCHAR(16) NOT NULL DEFAULT 'EXCEL' COMMENT '来源渠道EXCEL/API',
    ADD COLUMN latest_source_json JSON NULL COMMENT '最近一次平台来源，已生成费用时不覆盖确认依据',
    ADD COLUMN source_hash CHAR(64) NULL COMMENT '已接受来源摘要',
    ADD COLUMN latest_source_hash CHAR(64) NULL COMMENT '最新来源摘要',
    ADD COLUMN source_difference TINYINT NOT NULL DEFAULT 0 COMMENT '来源变化待核对',
    ADD COLUMN last_synced_time DATETIME(6) NULL COMMENT '最后成功同步时间',
    ADD COLUMN bill_verification_json JSON NULL COMMENT '人工核对净费用依据，来源金额变更后失效',
    ADD KEY ix_logistics_source_queue (tenant_id,source_difference,state,created_time);
