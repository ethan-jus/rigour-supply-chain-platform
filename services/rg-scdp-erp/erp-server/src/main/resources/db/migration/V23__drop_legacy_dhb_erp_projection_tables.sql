-- 删除旧订货宝导向的 ERP 投影表。
-- 新方案以 ERP 自研业务表为主：商品、规格、供应商、仓库、采购、采购退货、出入库、库存均使用 V19+ 新表。
-- 统一同步审计和来源绑定表仍保留，用于幂等、对账和来源追溯。
-- 旧表物理删除由 docs/LEGACY_DHB_TABLE_CLEANUP.sql 在确认备份和切流后使用 DBA 账号清理。
-- 日常 Flyway 迁移账号不授予 DROP 权限，自动迁移只记录新模型切换完成。
SELECT 1 AS legacy_erp_projection_cleanup_deferred;
