-- Order 客户资金流水页面命名修正；V64 已在共享 DEV 以“资金收付款”执行，本迁移做后续演进。

SET @changed_at = CURRENT_TIMESTAMP(6);
SET @fund_document_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000342');

UPDATE iam_resource
   SET display_name = '客户资金流水',
       sort_order = 38,
       status = 'ACTIVE',
       version = version + 1,
       updated_at = @changed_at
 WHERE id = @fund_document_page
   AND (
       display_name <> '客户资金流水'
       OR sort_order <> 38
       OR status <> 'ACTIVE'
   );

UPDATE iam_resource_ui
   SET route_key = 'supply.order.fund-documents',
       route_path = '/supply-chain/order/fund-documents',
       icon_key = 'Wallet',
       visible = 1,
       keep_alive = 0,
       updated_at = @changed_at
 WHERE resource_id = @fund_document_page
   AND (
       route_key <> 'supply.order.fund-documents'
       OR route_path <> '/supply-chain/order/fund-documents'
       OR icon_key <> 'Wallet'
       OR visible <> 1
       OR keep_alive <> 0
   );

UPDATE iam_tenant tenant_record
   SET tenant_record.policy_version = tenant_record.policy_version + 1,
       tenant_record.version = tenant_record.version + 1,
       tenant_record.updated_at = @changed_at
 WHERE tenant_record.status = 'ACTIVE'
   AND tenant_record.deleted_at IS NULL
   AND EXISTS (
       SELECT 1
         FROM iam_tenant_menu_config menu_config
        WHERE menu_config.tenant_id = tenant_record.id
          AND menu_config.resource_id = @fund_document_page
          AND menu_config.visible = 1
   );
