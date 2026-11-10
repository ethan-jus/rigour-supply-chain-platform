ALTER TABLE integration_sync_run
    ADD COLUMN hll_stages_json JSON NULL COMMENT '货拉拉本批订单列表已保存、详情已保存、详情待补及失败数量；旧批次未知';
