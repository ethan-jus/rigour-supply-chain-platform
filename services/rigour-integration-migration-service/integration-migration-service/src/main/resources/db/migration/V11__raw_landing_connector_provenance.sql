-- integration_sync_task.connector_id 是可变当前状态，任务改绑后不能用于反推历史Raw来源。
-- 只有租户历史连接器记录总数始终为1时，connector_id才是可证明的唯一来源；否则保留NULL。
UPDATE integration_raw_landing raw
JOIN integration_dhb_connector connector
  ON connector.tenant_id = raw.tenant_id
LEFT JOIN integration_dhb_connector other
  ON other.tenant_id = connector.tenant_id AND other.id <> connector.id
SET raw.connector_id = connector.id
WHERE raw.connector_id IS NULL AND other.id IS NULL;

-- 回填结束后再重建唯一键，避免大表回填时逐行维护新六列索引。
-- 历史 connector_id 无法证明来源时保持 NULL；当前订货宝调用均必须携带连接器 UUID。
ALTER TABLE integration_raw_landing
    DROP INDEX uk_integration_raw_landing_revision,
    ADD CONSTRAINT uk_integration_raw_landing_revision UNIQUE (
        tenant_id, connector_id, source_system, source_object_type, source_id, payload_checksum
    );

-- 订单镜像和 Outbox 也必须延续相同来源身份，不能让第二个连接器覆盖第一个镜像。
ALTER TABLE integration_order_mirror
    ADD COLUMN connector_id BINARY(16) NULL AFTER tenant_id;

UPDATE integration_order_mirror mirror
JOIN integration_raw_landing raw
  ON raw.tenant_id = mirror.tenant_id AND raw.id = mirror.raw_landing_id
SET mirror.connector_id = raw.connector_id
WHERE mirror.connector_id IS NULL;

-- Raw也无法反推时，仅历史上始终只有一个连接器的租户可安全补齐；其余保持NULL并由API展示为未知来源。
UPDATE integration_order_mirror mirror
JOIN integration_dhb_connector connector
  ON connector.tenant_id = mirror.tenant_id
LEFT JOIN integration_dhb_connector other
  ON other.tenant_id = connector.tenant_id AND other.id <> connector.id
SET mirror.connector_id = connector.id
WHERE mirror.connector_id IS NULL AND other.id IS NULL;

ALTER TABLE integration_order_mirror
    DROP INDEX uk_integration_order_mirror_source,
    ADD CONSTRAINT uk_integration_order_mirror_source UNIQUE (
        tenant_id, connector_id, source_order_id
    ),
    ADD INDEX idx_integration_order_mirror_connector (
        tenant_id, connector_id, order_time
    );
