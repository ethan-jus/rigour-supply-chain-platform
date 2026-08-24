-- 订货宝订单同步已收口到 Integration 编排器：Integration 负责 Raw Landing、映射和对账，
-- Order 只保留自研销售订单业务表。删除旧 Order 内部订货宝快照、导入和审计表。
-- 旧表物理删除由 docs/LEGACY_DHB_TABLE_CLEANUP.sql 在确认备份和切流后使用 DBA 账号清理。
-- 日常 Flyway 迁移账号不授予 DROP 权限，自动迁移只记录新模型切换完成。
SELECT 1 AS legacy_order_projection_cleanup_deferred;
