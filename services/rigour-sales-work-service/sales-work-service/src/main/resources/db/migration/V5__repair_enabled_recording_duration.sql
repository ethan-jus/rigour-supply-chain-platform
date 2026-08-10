-- 修复历史上“启用录音但要求0秒”导致0/0被判完成的无效规则。
-- 10分钟是当前已确认的有效拜访硬证据下限；迁移后数据库约束阻止同类配置再次写入。
UPDATE sales_visit_policy_version
   SET minimum_recording_seconds = 600
 WHERE recording_enabled = 1
   AND minimum_recording_seconds = 0;

ALTER TABLE sales_visit_policy_version
    ADD CONSTRAINT ck_sales_visit_policy_recording_duration
    CHECK (recording_enabled = 0 OR minimum_recording_seconds > 0);
