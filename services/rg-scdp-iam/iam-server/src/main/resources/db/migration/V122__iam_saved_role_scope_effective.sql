-- 供应链仅使用页面已保存的角色授权；数据范围缺省按产品规则为全部数据。
UPDATE iam_app_role SET data_scope_mode='ALL',data_scope_departments=JSON_ARRAY(),version=version+1
WHERE data_scope_mode IS NULL;
ALTER TABLE iam_app_role MODIFY COLUMN data_scope_mode VARCHAR(16) NOT NULL DEFAULT 'ALL';
UPDATE iam_app_role SET data_scope_departments=JSON_ARRAY() WHERE data_scope_departments IS NULL;
ALTER TABLE iam_app_settings ALTER COLUMN authorization_mode SET DEFAULT 'ACTIVE';
UPDATE iam_app_settings SET authorization_mode='ACTIVE',version=version+1,updated_at=UTC_TIMESTAMP(6);

DELETE FROM iam_app_member_limit_reference;
DELETE FROM iam_app_member_scope_limit;

DELETE FROM iam_app_member_role_scope;

DELETE FROM iam_app_scope_reference;
DELETE FROM iam_app_scope_rule;
