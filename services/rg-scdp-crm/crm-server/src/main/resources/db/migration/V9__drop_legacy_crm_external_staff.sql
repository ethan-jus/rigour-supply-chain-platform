-- CRM V9：删除旧订货宝外部员工投影。
--
-- 新方案口径：
-- 1. 员工、岗位、角色、组织统一归 IAM 员工中心管理。
-- 2. CRM 客户归属只保存 iam_staff_code、iam_staff_name_snapshot 和 source_staff_id 追溯字段。
-- 3. 订货宝 staff_id/accounts_id 等来源字段进入 IAM iam_external_staff_binding，不再进入 CRM 员工表。

SET @fk_exists = (
    SELECT COUNT(*)
      FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'crm_sales_assignment'
       AND constraint_name = 'fk_crm_sales_assignment_external_staff'
       AND constraint_type = 'FOREIGN KEY'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE crm_sales_assignment DROP FOREIGN KEY fk_crm_sales_assignment_external_staff',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'crm_sales_assignment'
       AND index_name = 'idx_crm_sales_assignment_external'
);
SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE crm_sales_assignment DROP INDEX idx_crm_sales_assignment_external',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'crm_sales_assignment'
       AND column_name = 'external_staff_id'
);
SET @sql = IF(@column_exists > 0,
    'ALTER TABLE crm_sales_assignment DROP COLUMN external_staff_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE crm_sales_assignment
    MODIFY COLUMN assignee_type VARCHAR(32) NOT NULL
        COMMENT '归属对象类型：IAM_STAFF员工中心人员，SOURCE_STAFF来源人员待解析，SALES_TEAM销售团队';

-- crm_external_staff 旧表物理删除由 docs/LEGACY_DHB_TABLE_CLEANUP.sql
-- 在确认备份和切流后使用 DBA 账号清理。日常 Flyway 迁移账号不授予 DROP 权限。
SELECT 1 AS legacy_crm_external_staff_cleanup_deferred;
