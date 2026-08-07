-- IAM V26：将订单结算菜单调整为财务用户更容易理解的名称。
-- “对账差异”保留为财务对账后的异常结果页；复用既有资源和路由，不删除“回款状态”资源，避免影响既有授权和书签。

SET @changed_at = TIMESTAMP('2026-08-07 17:00:00.000000');
SET @app_supply_chain = UUID_TO_BIN('019facf1-0000-7000-8000-000000000003');

UPDATE iam_resource
   SET display_name = CASE resource_code
           WHEN 'SUPPLY_CHAIN.MENU.ORDER_SETTLEMENT' THEN '订单结算管理'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE' THEN '应收依据'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN '对账差异'
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN '收付记录'
           ELSE display_name
       END,
       sort_order = CASE resource_code
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE' THEN 10
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS' THEN 20
           WHEN 'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION' THEN 30
           ELSE sort_order
       END,
       version = version + 1,
       updated_at = @changed_at
 WHERE application_id = @app_supply_chain
   AND resource_code IN (
       'SUPPLY_CHAIN.MENU.ORDER_SETTLEMENT',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECEIVABLE',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_RECONCILIATION',
       'SUPPLY_CHAIN.PAGE.ORDER_SETTLEMENT_COLLECTIONS'
   );

-- 使已登录租户重新读取最新导航；租户自定义 display_name_override 不被覆盖。
UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
