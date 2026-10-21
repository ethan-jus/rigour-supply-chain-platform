-- 保留现有会话，仅允许原生 App 按真实客户端类型记录新会话。
ALTER TABLE iam_auth_session
    DROP CHECK ck_iam_auth_session_client,
    ADD CONSTRAINT ck_iam_auth_session_client CHECK (client_type IN ('WEB', 'FEISHU_H5', 'NATIVE_APP'));
