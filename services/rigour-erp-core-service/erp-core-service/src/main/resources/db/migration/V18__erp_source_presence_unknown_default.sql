-- V15历史行被默认为PRESENT，但它们未经升级后的权威快照确认。
-- 仅降级PRESENT；保留SOURCE_ABSENT及其首次缺失时间作为核对证据。
ALTER TABLE erp_master_source_binding
    ALTER COLUMN source_presence SET DEFAULT 'UNKNOWN';

UPDATE erp_master_source_binding
   SET source_presence = 'UNKNOWN',
       source_absent_at = NULL
 WHERE source_presence = 'PRESENT';
