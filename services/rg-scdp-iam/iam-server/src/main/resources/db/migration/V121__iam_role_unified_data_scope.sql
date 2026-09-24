-- 旧角色保留原授权，必须显式选择统一范围后才转换，不能将未配置解释成全部数据。
ALTER TABLE iam_app_role
    ADD COLUMN data_scope_mode VARCHAR(16) NULL,
    ADD COLUMN data_scope_departments JSON NULL;
