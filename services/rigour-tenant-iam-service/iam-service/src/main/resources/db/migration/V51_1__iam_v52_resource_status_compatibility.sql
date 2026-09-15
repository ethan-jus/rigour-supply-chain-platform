-- 历史 V52 将旧页面写为 INACTIVE，但 V1 的资源约束只有 ACTIVE/DISABLED。
-- 不改写已执行的 V52；仅在回放 V52 的迁移窗口接受旧停用值。
-- V52.1 会将旧值归一化为 DISABLED 并恢复原约束，业务运行期间不保留兼容逻辑。
-- 已越过 V52 的环境须在备份和版本清单核对后，一次性 outOfOrder 应用 51.1/52.1。
ALTER TABLE iam_resource
    DROP CHECK ck_iam_resource_status,
    ADD CONSTRAINT ck_iam_resource_status CHECK (status IN ('ACTIVE', 'DISABLED', 'INACTIVE'));
