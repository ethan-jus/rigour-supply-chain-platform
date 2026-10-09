-- 仅用于本次 DEV 的 V125 失败恢复，执行前备份该失败行。
-- 已确认权限变更已回滚；修正后的 V125 仍由下次 IAM 启动时的 Flyway 正常执行。
-- 不修改成功迁移，不更新校验和，不关闭 validate。
START TRANSACTION;
SELECT * FROM flyway_schema_history
 WHERE DATABASE()='rigour_iam' AND installed_rank=129 AND version='125'
   AND checksum=985544145 AND success=0 FOR UPDATE;
DELETE FROM flyway_schema_history
 WHERE DATABASE()='rigour_iam' AND installed_rank=129 AND version='125'
   AND script='V125__hr_target_permissions.sql' AND checksum=985544145 AND success=0;
SELECT ROW_COUNT() AS removed_failed_migration;
COMMIT;
