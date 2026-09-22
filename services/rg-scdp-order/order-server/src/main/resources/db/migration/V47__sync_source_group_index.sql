-- Order V47：来源订单按关联组查询的索引。
-- 订单列表「订货宝订单号」口径需要从历史关联组反查来源单（order_history_member -> order_sync_source.group_id）。
-- V40 已执行，这里只做增量。

CREATE INDEX idx_sync_source_group ON order_sync_source(tenant_id, group_id);
