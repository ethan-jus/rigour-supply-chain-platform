-- IAM V10：将订货宝商城恢复为外部应用，取消订货宝同步卡片对供应链内部路由的错误复用。
-- 订货宝数据同步仍属于SUPPLY_CHAIN应用下的菜单和页面，不是独立门户应用。

SET @app_dinghuobao = UUID_TO_BIN('019facf1-0000-7000-8000-000000000004');
SET @app_dinghuobao_integration = UUID_TO_BIN('019facf1-0000-7000-8000-000000000006');

-- 订货宝商城卡片：统一门户负责直达第三方管理端；当前先使用官方测试入口。
UPDATE iam_application
   SET app_name='订货宝商城系统',
       launch_mode='EXTERNAL_URL',
       target_uri='https://pc.dhb168.com',
       status='ACTIVE',
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@app_dinghuobao
   AND app_code='DINGHUOBAO'
   AND app_scope='TENANT'
   AND app_type='EXTERNAL'
   AND deleted_at IS NULL;

UPDATE iam_resource
   SET display_name='订货宝商城系统',
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE application_id=@app_dinghuobao
   AND resource_code='DINGHUOBAO.ROOT'
   AND deleted_at IS NULL;

-- 订货宝同步能力仍由供应链菜单提供，不能再次作为门户卡片出现。
UPDATE iam_application
   SET status='DISABLED',
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@app_dinghuobao_integration
   AND app_code='DINGHUOBAO_INTEGRATION'
   AND deleted_at IS NULL;
