-- 门头照证据闭环：保留 V1 证据表，新增现场相机、位置、媒体与客户端幂等事实。
-- 已执行迁移不改写；历史证据允许新增字段为空，新上传门头照必须由应用层完整填写。

ALTER TABLE sales_visit_evidence
    ADD COLUMN client_evidence_id VARCHAR(128) NULL AFTER visit_id,
    ADD COLUMN evidence_role VARCHAR(32) NULL AFTER evidence_type,
    ADD COLUMN capture_source VARCHAR(32) NULL AFTER evidence_role,
    ADD COLUMN captured_at DATETIME(6) NULL AFTER capture_source,
    ADD COLUMN media_type VARCHAR(128) NULL AFTER object_key,
    ADD COLUMN object_size_bytes BIGINT UNSIGNED NULL AFTER media_type,
    ADD COLUMN longitude DECIMAL(10,7) NULL AFTER content_hash,
    ADD COLUMN latitude DECIMAL(10,7) NULL AFTER longitude,
    ADD COLUMN accuracy_meters DECIMAL(8,2) NULL AFTER latitude,
    ADD COLUMN distance_to_target_meters DECIMAL(10,2) NULL AFTER accuracy_meters,
    ADD CONSTRAINT uk_sales_visit_evidence_client
        UNIQUE (tenant_id, visit_id, client_evidence_id);

-- 产品规则已冻结：每次拜访至少要求一张现场门头照。
UPDATE sales_visit_policy_version
   SET required_photo_count = 1
 WHERE required_photo_count < 1;

ALTER TABLE sales_visit_policy_version
    ADD CONSTRAINT ck_sales_visit_policy_required_photo
        CHECK (required_photo_count >= 1);
