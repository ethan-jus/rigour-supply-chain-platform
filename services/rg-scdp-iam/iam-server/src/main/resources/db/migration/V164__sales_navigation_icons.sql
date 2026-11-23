-- 公共导航和菜单管理读取同一租户菜单。默认目录用于新建租户，已配置的自定义图标保留。
CREATE TEMPORARY TABLE scm_sales_icon (
    resource_code VARCHAR(128) PRIMARY KEY,
    icon_key VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO scm_sales_icon VALUES
    ('SUPPLY_CHAIN.MENU.SALES', 'Route'),
    ('SUPPLY_CHAIN.PAGE.SALES_OVERVIEW', 'TrendLine'),
    ('SUPPLY_CHAIN.PAGE.SALES_CUSTOMER_FOLLOWUPS', 'Storefront'),
    ('SUPPLY_CHAIN.PAGE.SALES_RISK_CASES', 'ShieldAlert'),
    ('SUPPLY_CHAIN.PAGE.SALES_VISIT_PLAN', 'CalendarOutline'),
    ('SUPPLY_CHAIN.TABS.SALES.FIELDWORK', 'Route'),
    ('SUPPLY_CHAIN.PAGE.SALES_VISITS_RECORDS', 'FileText'),
    ('SUPPLY_CHAIN.PAGE.SALES_VISIT_REVIEW', 'Inbox'),
    ('SUPPLY_CHAIN.PAGE.SALES_POLICY_VISIT', 'Sliders'),
    ('SUPPLY_CHAIN.PAGE.SALES_PERFORMANCE', 'Target'),
    ('SUPPLY_CHAIN.PAGE.SALES_PAY_STATEMENTS', 'Receipt');

START TRANSACTION;
-- 先使用旧目录图标识别沿用默认配置的租户菜单，再更新目录。
UPDATE iam_app_menu_node n
JOIN iam_resource r ON r.id=n.resource_id AND r.application_id=n.application_id
JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN' AND a.deleted_at IS NULL
JOIN iam_resource_ui ui ON ui.resource_id=r.id
JOIN scm_sales_icon i ON CAST(i.resource_code AS BINARY)=CAST(r.resource_code AS BINARY)
LEFT JOIN iam_app_settings s ON s.tenant_id=n.tenant_id AND s.application_id=n.application_id
SET n.icon_key=i.icon_key,n.version=n.version+1,n.updated_at=UTC_TIMESTAMP(6),
    s.version=s.version+1,s.updated_at=UTC_TIMESTAMP(6)
WHERE n.deleted_at IS NULL AND r.deleted_at IS NULL
    AND (n.icon_key IS NULL OR CAST(n.icon_key AS BINARY)=CAST(ui.icon_key AS BINARY))
    AND NOT (CAST(n.icon_key AS BINARY) <=> CAST(i.icon_key AS BINARY));

UPDATE iam_resource_ui ui
JOIN iam_resource r ON r.id=ui.resource_id
JOIN iam_application a ON a.id=r.application_id AND a.app_code='SUPPLY_CHAIN' AND a.deleted_at IS NULL
JOIN scm_sales_icon i ON CAST(i.resource_code AS BINARY)=CAST(r.resource_code AS BINARY)
SET ui.icon_key=i.icon_key,ui.updated_at=UTC_TIMESTAMP(6)
WHERE r.deleted_at IS NULL AND NOT (CAST(ui.icon_key AS BINARY) <=> CAST(i.icon_key AS BINARY));
COMMIT;

DROP TEMPORARY TABLE scm_sales_icon;
