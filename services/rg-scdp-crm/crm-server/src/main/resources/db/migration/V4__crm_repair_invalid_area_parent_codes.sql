-- CRM V4：清理历史同步把字段名误写入地区父级编码的问题。
-- 订货宝未提供真实 parentID 时，父级关系应为空，而不是 literal "parentId"。
UPDATE crm_customer_area
   SET parent_area_code = NULL,
       updated_at = CURRENT_TIMESTAMP,
       version = version + 1
 WHERE LOWER(TRIM(parent_area_code)) IN ('parentid', 'parent_id', 'parent-id', 'null', 'undefined');
