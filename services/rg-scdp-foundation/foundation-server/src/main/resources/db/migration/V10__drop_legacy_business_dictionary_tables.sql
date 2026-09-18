-- 字典服务已切换到 data_dictionary / data_dictionary_item。
-- 旧模型表由 docs/LEGACY_DHB_TABLE_CLEANUP.sql 在确认备份和切流后使用 DBA 账号清理。
-- 日常 Flyway 迁移账号不授予 DROP 权限，自动迁移只记录新模型切换完成。
SELECT 1 AS legacy_business_dictionary_cleanup_deferred;
