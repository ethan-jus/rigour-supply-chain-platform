-- 历史 V52 已完成；停用语义不变，将旧值归一化后恢复原始严格约束。
UPDATE iam_resource SET status = 'DISABLED' WHERE status = 'INACTIVE';
ALTER TABLE iam_resource
    DROP CHECK ck_iam_resource_status,
    ADD CONSTRAINT ck_iam_resource_status CHECK (status IN ('ACTIVE', 'DISABLED'));
