-- Integration Schema V8：支持跨历史图片任务复用成功快照时的高选择性查询。
CREATE INDEX idx_integration_product_media_item_reuse
    ON integration_product_media_item (
        tenant_id, connector_id, source_product_id, source_resource_id,
        status, updated_at, id
    );
