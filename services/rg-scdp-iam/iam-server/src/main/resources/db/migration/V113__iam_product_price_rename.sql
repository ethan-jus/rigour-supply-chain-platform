-- 商品价格页面命名调整：原「客户类型等级价」页面统一改名为「商品价格」，资源编码与路由 key/路径同步更新。
-- V112 已执行且不可改写，本迁移负责重命名；脚本可重复执行。
SET @page=UUID_TO_BIN('019facf2-0000-7000-8000-000000000303');
SET @price_read=UUID_TO_BIN('019facf2-0000-7000-8000-000000000304');
SET @price_write=UUID_TO_BIN('019facf2-0000-7000-8000-000000000305');

UPDATE iam_resource
   SET resource_code='SUPPLY_CHAIN.PAGE.ERP_PRODUCT_PRICES',
       display_name='商品价格',
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@page;

UPDATE iam_resource
   SET display_name='查询商品等级价',
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@price_read;

UPDATE iam_resource
   SET display_name='维护商品等级价',
       updated_at=UTC_TIMESTAMP(6)
 WHERE id=@price_write;

UPDATE iam_resource_ui
   SET route_key='supply.erp.master-data.prices',
       route_path='/supply-chain/erp/master-data/prices',
       updated_at=UTC_TIMESTAMP(6)
 WHERE resource_id=@page;

UPDATE iam_app_menu_node
   SET display_name='商品价格',
       updated_at=UTC_TIMESTAMP(6)
 WHERE resource_id=@page
   AND deleted_at IS NULL;

UPDATE iam_tenant
   SET policy_version=policy_version+1,
       version=version+1,
       updated_at=UTC_TIMESTAMP(6)
 WHERE status='ACTIVE'
   AND deleted_at IS NULL;
