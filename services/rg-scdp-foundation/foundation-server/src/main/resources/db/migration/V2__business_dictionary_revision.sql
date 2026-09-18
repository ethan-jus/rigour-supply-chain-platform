-- 公共业务字典整本内容版本。
-- 字典定义或任一条目变化时递增，供消费者做批次快照审计和缓存失效判断。
ALTER TABLE biz_dict
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0 COMMENT '整本字典内容版本，定义或任一条目变化时递增'
        AFTER version;
