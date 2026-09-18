-- IAM V11：再次收敛门户应用启动地址，修复旧环境残留的含糊 /admin。
--
-- V8 已经定义了正确边界；本迁移用于共享DEV/历史环境可能未完整执行V8时的幂等纠偏。
-- 不修改已执行的历史迁移文件，平台管理和租户系统管理必须保持不同入口。

UPDATE iam_application
   SET target_uri = '/platform-admin',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'PLATFORM_ADMIN'
   AND app_scope = 'PLATFORM'
   AND launch_mode = 'INTERNAL_ROUTE'
   AND target_uri <> '/platform-admin'
   AND deleted_at IS NULL;

UPDATE iam_application
   SET target_uri = '/system-admin',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'SYSTEM_ADMIN'
   AND app_scope = 'TENANT'
   AND launch_mode = 'INTERNAL_ROUTE'
   AND target_uri <> '/system-admin'
   AND deleted_at IS NULL;

UPDATE iam_application
   SET target_uri = '/supply-chain',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'SUPPLY_CHAIN'
   AND app_scope = 'TENANT'
   AND launch_mode = 'INTERNAL_ROUTE'
   AND target_uri <> '/supply-chain'
   AND deleted_at IS NULL;
