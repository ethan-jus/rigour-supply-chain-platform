-- 仅处理 V74.1 明确标记的过渡状态；保留原 ID、已有授权与租户配置，不留重复菜单。
SET @canonical_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000240');
SET @duplicate_sales = UUID_TO_BIN('019facf2-0000-7000-8000-000000000376');
SET @needs_sales_restore = (SELECT COUNT(*) FROM iam_resource
    WHERE id=@canonical_sales AND resource_code='SUPPLY_CHAIN.PAGE.BI_SALES_PRE_V75');

INSERT IGNORE INTO iam_package_resource (package_version_id,resource_id,created_at,created_by)
SELECT package_version_id,@canonical_sales,created_at,created_by FROM iam_package_resource
WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
INSERT IGNORE INTO iam_role_resource
    (tenant_id,role_id,resource_id,status,created_at,created_by,updated_at,updated_by)
SELECT tenant_id,role_id,@canonical_sales,status,created_at,created_by,updated_at,updated_by
FROM iam_role_resource WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
INSERT IGNORE INTO iam_tenant_menu_config
    (tenant_id,resource_id,display_name_override,icon_key_override,sort_order_override,parent_group_id,
     visible,version,created_at,created_by,updated_at,updated_by)
SELECT tenant_id,@canonical_sales,display_name_override,icon_key_override,sort_order_override,parent_group_id,
       visible,version,created_at,created_by,updated_at,updated_by
FROM iam_tenant_menu_config WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;

DELETE FROM iam_role_resource WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_package_resource WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_tenant_menu_config WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_resource_ui WHERE resource_id=@duplicate_sales AND @needs_sales_restore=1;
UPDATE iam_resource SET parent_id=@canonical_sales
WHERE parent_id=@duplicate_sales AND @needs_sales_restore=1;
DELETE FROM iam_resource WHERE id=@duplicate_sales AND @needs_sales_restore=1;
UPDATE iam_resource SET resource_code='SUPPLY_CHAIN.PAGE.BI_SALES'
WHERE id=@canonical_sales AND @needs_sales_restore=1;
