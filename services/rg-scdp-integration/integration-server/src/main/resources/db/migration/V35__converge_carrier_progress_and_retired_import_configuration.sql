-- 两条 V34 历史各自保留；仅补齐尚未执行的另一分支，不改写历史记录。
SET @add_carrier_progress = IF(
    EXISTS(SELECT 1 FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='integration_dictionary_mapping'
          AND column_name='carrier_backfill_revision'),
    'DO 0',
    'ALTER TABLE integration_dictionary_mapping ADD COLUMN carrier_backfill_revision INT NOT NULL DEFAULT 0 COMMENT ''最近完成历史运输回填的映射版本'''
);
PREPARE carrier_progress FROM @add_carrier_progress;
EXECUTE carrier_progress;
DEALLOCATE PREPARE carrier_progress;

-- 历史批次、抓取存档和业务订单保留原始来源。只清理已退役导入器的配置。
START TRANSACTION;
DELETE FROM integration_feishu_import_template_dependency WHERE source_system='FEISHU';
DELETE FROM integration_feishu_import_template WHERE source_system='FEISHU';
COMMIT;
