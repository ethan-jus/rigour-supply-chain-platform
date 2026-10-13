-- 提成设置统一承载商品提成规则与城市总提成归属，仅调整默认展示名称。
SET @commission_renamed_at = UTC_TIMESTAMP(6);
SET @commission_page = (
    SELECT r.id FROM iam_resource r
    JOIN iam_resource_ui ui ON ui.resource_id=r.id
    JOIN iam_application app ON app.id=r.application_id
    WHERE app.app_code='SUPPLY_CHAIN' AND ui.route_key='supply.hr.commission-rules'
);

UPDATE iam_resource
SET display_name='提成设置',version=version+1,updated_at=@commission_renamed_at
WHERE id=@commission_page AND display_name='商品提成设置';

UPDATE iam_app_menu_node
SET display_name='提成设置',version=version+1,updated_at=@commission_renamed_at
WHERE resource_id=@commission_page AND deleted_at IS NULL AND display_name='商品提成设置';

UPDATE iam_tenant_menu_config
SET display_name_override='提成设置',version=version+1,updated_at=@commission_renamed_at
WHERE resource_id=@commission_page AND display_name_override='商品提成设置';

UPDATE iam_app_settings s
JOIN iam_application app ON app.id=s.application_id AND app.app_code='SUPPLY_CHAIN'
SET s.version=s.version+1,s.updated_at=@commission_renamed_at
WHERE EXISTS (
    SELECT 1 FROM iam_app_menu_node n
    WHERE n.tenant_id=s.tenant_id AND n.application_id=s.application_id
      AND n.resource_id=@commission_page AND n.deleted_at IS NULL
);

UPDATE iam_tenant t
SET t.policy_version=t.policy_version+1,t.version=t.version+1,t.updated_at=@commission_renamed_at
WHERE t.deleted_at IS NULL AND EXISTS (
    SELECT 1 FROM iam_app_menu_node n
    WHERE n.tenant_id=t.id AND n.resource_id=@commission_page AND n.deleted_at IS NULL
);
