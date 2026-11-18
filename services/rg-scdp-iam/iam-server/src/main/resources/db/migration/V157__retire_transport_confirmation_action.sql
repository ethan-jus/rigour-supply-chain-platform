-- 运输资料完整度由系统判定，停用已取消的手工确认入口；保留历史授权记录。
SET @transport_confirmation_app=(SELECT id FROM iam_application WHERE app_code='SUPPLY_CHAIN' AND deleted_at IS NULL);
UPDATE iam_app_menu_node n JOIN iam_resource r ON r.id=n.resource_id
SET n.status='DISABLED',n.visible=FALSE,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6)
WHERE r.permission_code='order:logistics-cost:confirm' AND n.deleted_at IS NULL;
UPDATE iam_resource SET status='DISABLED',version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE permission_code='order:logistics-cost:confirm' AND deleted_at IS NULL;
UPDATE iam_app_settings SET version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE application_id=@transport_confirmation_app;
UPDATE iam_tenant SET policy_version=policy_version+1,version=version+1,updated_at=UTC_TIMESTAMP(6)
WHERE id IN (SELECT tenant_id FROM iam_app_settings WHERE application_id=@transport_confirmation_app);
