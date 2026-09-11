-- IAM V80：人员中心外部来源允许飞书导入，保留订货宝同步来源不变。

ALTER TABLE iam_staff_profile
    DROP CHECK ck_iam_staff_profile_origin,
    ADD CONSTRAINT ck_iam_staff_profile_origin
        CHECK (record_origin IN ('MANUAL', 'DINGHUOBAO', 'FEISHU', 'IMPORT'));

ALTER TABLE iam_external_staff_binding
    DROP CHECK ck_iam_external_staff_binding_system,
    ADD CONSTRAINT ck_iam_external_staff_binding_system
        CHECK (source_system IN ('DINGHUOBAO', 'FEISHU'));
