-- 接通 SCDP 浏览器的标准 refresh_token 授权；保留 15 分钟访问令牌及现有会话期限。
-- 不修改历史迁移，也不开放其他客户端；旧刷新令牌在轮换后由存储层撤销。
INSERT INTO iam_oauth_client_grant (client_id, grant_type, created_at)
SELECT c.id, 'refresh_token', UTC_TIMESTAMP(6)
FROM iam_oauth_client c
WHERE c.client_id LIKE 'rigour-scdp-%'
  AND c.client_type = 'PUBLIC' AND c.require_pkce = 1 AND c.reuse_refresh_tokens = 0
  AND EXISTS (SELECT 1 FROM iam_oauth_client_grant g WHERE g.client_id = c.id AND g.grant_type = 'authorization_code')
  AND NOT EXISTS (SELECT 1 FROM iam_oauth_client_grant g WHERE g.client_id = c.id AND g.grant_type = 'refresh_token');
