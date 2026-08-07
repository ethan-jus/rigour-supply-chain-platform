-- 飞书销售工作台是销售人员移动H5入口，不是供应链销售管理后台。
-- 保留Portal受控启动页，由启动页打开独立Workbench；真实飞书地址通过前端环境配置注入。
SET @seed_at = CURRENT_TIMESTAMP(6);

UPDATE iam_application
   SET app_type = 'EXTERNAL',
       launch_mode = 'FEISHU_DEEPLINK',
       target_uri = '/sales-workbench',
       version = version + 1,
       updated_at = @seed_at
 WHERE app_code = 'FEISHU_SALES'
   AND deleted_at IS NULL;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @seed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
