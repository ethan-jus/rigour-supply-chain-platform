-- 发票页面已有授权独立获得查看能力，不新增角色授权，也不授予订单或开票写权限。
SET @invoice_page=UUID_TO_BIN('e1582a53-c489-423e-8b58-40a6bbc04b37');
SET @invoice_app=(SELECT application_id FROM iam_resource WHERE id=@invoice_page);
UPDATE iam_resource SET permission_code='order:invoice:read',updated_at=UTC_TIMESTAMP(6)
 WHERE id=@invoice_page AND resource_code='SUPPLY_CHAIN.PAGE.ORDER_INVOICES'
 AND resource_type='PAGE' AND status='ACTIVE' AND deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE application_id=@invoice_app;
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
 WHERE id IN (SELECT tenant_id FROM iam_app_settings WHERE application_id=@invoice_app);
