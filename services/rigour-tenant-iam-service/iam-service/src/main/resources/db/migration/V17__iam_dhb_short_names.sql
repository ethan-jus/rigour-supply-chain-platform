-- IAM V17：将订货宝运行时标识统一收缩为 DHB。
--
-- V6、V9、V10 等历史迁移文件和已执行的 Flyway 历史不改写；本迁移只更新运行时数据。
-- 业务显示名称仍使用中文“订货宝”，代码、权限和门户路由使用短标识 DHB。

UPDATE iam_application
   SET app_code = 'DHB',
       icon_key = 'app-dhb',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'DINGHUOBAO'
   AND deleted_at IS NULL;

UPDATE iam_application
   SET app_code = 'DHB_INTEGRATION',
       icon_key = 'app-dhb',
       target_uri = '/supply-chain/dhb',
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE app_code = 'DINGHUOBAO_INTEGRATION'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET resource_code = REPLACE(resource_code, 'DINGHUOBAO', 'DHB'),
       permission_code = CASE
           WHEN permission_code LIKE 'integration:dinghuobao:%'
               THEN REPLACE(permission_code, 'integration:dinghuobao:', 'integration:dhb:')
           ELSE permission_code
       END,
       version = version + 1,
       updated_at = UTC_TIMESTAMP(6)
 WHERE deleted_at IS NULL
   AND (resource_code LIKE 'DINGHUOBAO%'
        OR permission_code LIKE 'integration:dinghuobao:%');

UPDATE iam_resource_ui
   SET route_key = REPLACE(route_key, 'supply.dinghuobao', 'supply.dhb'),
       route_path = REPLACE(route_path, '/supply-chain/dinghuobao', '/supply-chain/dhb'),
       updated_at = UTC_TIMESTAMP(6)
 WHERE route_key LIKE 'supply.dinghuobao%'
    OR route_path LIKE '/supply-chain/dinghuobao%';
