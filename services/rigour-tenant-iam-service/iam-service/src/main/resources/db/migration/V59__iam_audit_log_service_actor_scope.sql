-- IAM V59：允许服务间可信调用写入审计日志。
--
-- 订货宝统一同步由 Integration 以 SERVICE 身份调用 IAM/ERP/CRM/Order 内部接口。
-- 旧审计约束只允许 SYSTEM，导致 IAM 人员同步完成后写审计失败，整批订货宝编排变为 FAILED。

ALTER TABLE iam_audit_log
    DROP CHECK ck_iam_audit_actor_scope;

ALTER TABLE iam_audit_log
    ADD CONSTRAINT ck_iam_audit_actor_scope
        CHECK (actor_scope IN ('PLATFORM', 'TENANT', 'ANONYMOUS', 'SYSTEM', 'SERVICE'));
