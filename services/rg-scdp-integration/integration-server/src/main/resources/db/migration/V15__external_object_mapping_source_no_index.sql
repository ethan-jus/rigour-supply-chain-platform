-- 为外部对象编号兜底查询补索引。
-- 主链路仍优先使用 source_object_id；订单同步在订货宝只返回客户/商品编号时按 source_object_no 兜底查映射。

CREATE INDEX idx_integration_external_source_no
    ON integration_external_object_mapping (
        tenant_id, connector_id, source_system, source_object_type, source_object_no, mapping_status, deleted_at
    );
