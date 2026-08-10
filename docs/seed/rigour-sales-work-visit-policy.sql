-- Dev 手工种子：销售拜访默认规则（仅本机联调库）。
-- 不放入 Flyway：这是开发租户业务数据，不是库结构。生产/正式环境由规则配置页发布。
-- 适用：tenant 019FBAF9CFB5740DB347739D29765D8E（租户超级管理员联调）。
-- 半径 500 米；停留时长 0（方便随时签退测试）；现场录音至少 10 分钟；允许拜访新门店。

INSERT INTO sales_visit_policy
    (id, tenant_id, policy_code, policy_name, status, version, created_at, updated_at)
VALUES
    (UUID_TO_BIN('3C3C0000000000000000000000000005'),
     UUID_TO_BIN('019FBAF9CFB5740DB347739D29765D8E'),
     'VISIT-DEFAULT', '默认拜访规则', 'ACTIVE', 0,
     UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE policy_name=VALUES(policy_name), status='ACTIVE', updated_at=UTC_TIMESTAMP(6);

INSERT INTO sales_visit_policy_version
    (id, tenant_id, policy_id, version_no, publish_status,
     require_assigned_target, allow_prospect_target, check_in_radius_meters,
     minimum_dwell_minutes, required_photo_count, recording_enabled,
     minimum_recording_seconds, maximum_clip_gap_seconds,
     ai_asr_enabled, ai_relevance_enabled, ai_duplicate_enabled, ai_auto_confirm_threshold,
     effective_from, effective_to, approved_by, approved_at, created_by, created_at)
VALUES
    (UUID_TO_BIN('3C3C0000000000000000000000000006'),
     UUID_TO_BIN('019FBAF9CFB5740DB347739D29765D8E'),
     UUID_TO_BIN('3C3C0000000000000000000000000005'), 1, 'PUBLISHED',
     1, 1, 500,
     0, 0, 1,
     600, 30,
     1, 1, 1, NULL,
     NULL, NULL, NULL, NULL, UUID_TO_BIN('019FBC5796817C68BC7B96C4A7F456C3'), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
    publish_status='PUBLISHED',
    recording_enabled=1,
    minimum_recording_seconds=600;
