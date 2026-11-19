-- 历史批次、抓取存档和业务订单保留原始来源。只清理已退役导入器的配置。
START TRANSACTION;
DELETE FROM integration_feishu_import_template_dependency WHERE source_system='FEISHU';
DELETE FROM integration_feishu_import_template WHERE source_system='FEISHU';
COMMIT;
