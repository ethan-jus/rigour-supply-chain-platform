-- 临时销售打卡数据治理：清理飞书导入时误带入的日期前缀，并为“本临时系统内”拜访次序提供索引。
-- 清理语句只匹配 YYYY-MM-DD- / YYYY-MM-DD<空格> 前缀，重复执行不会再改写已清理名称。

UPDATE temp_sales_checkin_salesperson
   SET name = TRIM(REGEXP_REPLACE(
           name,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       )),
       updated_at = UTC_TIMESTAMP(6)
 WHERE name REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+'
   AND CHAR_LENGTH(TRIM(REGEXP_REPLACE(
           name,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       ))) > 0;

UPDATE temp_sales_checkin_submission
   SET salesperson_name_snapshot = TRIM(REGEXP_REPLACE(
           salesperson_name_snapshot,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       )),
       updated_at = UTC_TIMESTAMP(6)
 WHERE salesperson_name_snapshot REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+'
   AND CHAR_LENGTH(TRIM(REGEXP_REPLACE(
           salesperson_name_snapshot,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       ))) > 0;

ALTER TABLE temp_sales_checkin_store
    ADD CONSTRAINT uk_temp_sales_checkin_store_source_poi
        UNIQUE (tenant_id, source_poi_id);

CREATE INDEX idx_temp_sales_checkin_submission_visit_rank
    ON temp_sales_checkin_submission (
        tenant_id, status, salesperson_id, store_id, submitted_at, id
    );
