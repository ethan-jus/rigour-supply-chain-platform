-- 业务依赖补取失败后转人工补录；只保留失败项，不改变已保存物流或已确认成本。
ALTER TABLE integration_dhb_incremental_work
    ADD COLUMN retry_policy VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'AUTO' AFTER applied;
