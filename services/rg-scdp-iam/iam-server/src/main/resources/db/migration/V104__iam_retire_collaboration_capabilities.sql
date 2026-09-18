-- 暂停内部协同：只停用能力目录，保留角色引用、消息数据和已执行迁移历史。
SET @changed_at = UTC_TIMESTAMP(6);
UPDATE iam_resource
   SET status='DISABLED', version=version+1, updated_at=@changed_at
 WHERE resource_code IN ('WORKBENCH.PAGE.CHAT','WORKBENCH.API.COLLABORATION_IM','WORKBENCH.API.COLLABORATION_MEETING')
   AND status <> 'DISABLED';
UPDATE iam_tenant
   SET policy_version=policy_version+1, version=version+1, updated_at=@changed_at
 WHERE status='ACTIVE' AND deleted_at IS NULL;
