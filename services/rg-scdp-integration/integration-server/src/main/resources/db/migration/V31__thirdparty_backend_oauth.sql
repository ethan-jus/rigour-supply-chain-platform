-- 公共第三方平台后端授权与令牌管理；保留既有迁移历史。

CREATE TABLE integration_oauth_token (
    id BINARY(16) NOT NULL COMMENT '主键ID，由后端生成UUID',
    tenant_id BINARY(16) NOT NULL COMMENT '本系统租户ID',
    platform_code VARCHAR(32) NOT NULL COMMENT '第三方平台编码，货拉拉为HUOLALA',
    connector_id BINARY(16) NOT NULL COMMENT '平台连接ID，货拉拉关联既有连接表',
    app_key VARCHAR(128) NOT NULL COMMENT '令牌所属应用标识APP_KEY，不保存APP_SECRET',

    access_token TEXT NULL COMMENT '平台返回的访问令牌原值，仅后端读取',
    refresh_token TEXT NULL COMMENT '平台返回的刷新令牌原值，未提供时为空',
    token_type VARCHAR(50) NULL COMMENT '平台返回的令牌类型原值，未提供时为空',
    token_metadata_json JSON NULL COMMENT '平台返回的有效期及授权范围等元数据，保留原字段名，不重复保存令牌和密钥',
    token_received_at DATETIME(6) NULL COMMENT '后端收到当前令牌响应的时间UTC',
    access_token_expires_at DATETIME(6) NULL COMMENT '访问令牌到期时间UTC，根据平台明确的期限语义计算',
    refresh_token_expires_at DATETIME(6) NULL COMMENT '刷新令牌到期时间UTC，平台未提供时为空',
    next_refresh_at DATETIME(6) NULL COMMENT '下次计划刷新或失败重试时间UTC',

    auth_status VARCHAR(32) NOT NULL DEFAULT 'UNAUTHORIZED'
        COMMENT '授权状态UNAUTHORIZED未授权AUTHORIZED已授权REAUTHORIZE_REQUIRED需重新授权DISABLED已停用',
    last_authorized_at DATETIME(6) NULL COMMENT '最近一次授权成功时间UTC',
    last_refreshed_at DATETIME(6) NULL COMMENT '最近一次令牌刷新成功时间UTC',
    refresh_failure_count INT NOT NULL DEFAULT 0 COMMENT '连续刷新失败次数',
    last_error_code VARCHAR(100) NULL COMMENT '最近失败的错误编码，不含敏感内容',
    last_error_message VARCHAR(500) NULL COMMENT '最近失败的脱敏说明，不含令牌授权码及密钥',

    refresh_lease_id VARCHAR(36) NULL COMMENT '刷新任务租约标识，防止多实例并发刷新',
    refresh_lease_expires_at DATETIME(6) NULL COMMENT '刷新任务租约到期时间UTC',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by BINARY(16) NULL COMMENT '创建人员ID，系统任务为空',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间UTC',
    updated_by BINARY(16) NULL COMMENT '最近操作人员ID，系统任务为空',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间UTC',

    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth_token_connection (tenant_id, platform_code, connector_id),
    KEY ix_oauth_token_refresh (auth_status, next_refresh_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='第三方平台OAuth当前令牌与刷新控制';

CREATE TABLE integration_oauth_authorization_session (
    id BINARY(16) NOT NULL COMMENT '授权会话ID，由后端生成UUID',
    tenant_id BINARY(16) NOT NULL COMMENT '本系统租户ID，取发起授权时的可信上下文',
    platform_code VARCHAR(32) NOT NULL COMMENT '第三方平台编码，货拉拉为HUOLALA',
    connector_id BINARY(16) NOT NULL COMMENT '本次授权的平台连接ID',
    token_id BINARY(16) NOT NULL COMMENT '目标通用Token记录ID',
    actor_id BINARY(16) NOT NULL COMMENT '发起授权的系统人员ID',

    app_key VARCHAR(128) NOT NULL COMMENT '发起授权时的应用标识快照',
    configuration_fingerprint CHAR(64) NOT NULL COMMENT '应用平台环境回调及企业绑定配置的指纹，不保存环境明文字段和密钥',
    session_hash CHAR(64) NOT NULL COMMENT '一次性授权票据的SHA256摘要，不保存原始票据',
    browser_binding_hash CHAR(64) NULL COMMENT '后端授权浏览器Cookie绑定值的摘要',
    authorization_code_hash CHAR(64) NULL COMMENT '收到授权码的摘要用于去重，不保存原始授权码',
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED'
        COMMENT '会话状态CREATED待打开REDIRECTED授权中EXCHANGING换取中PENDING_VERIFICATION待核验SUCCEEDED成功FAILED失败UNCERTAIN结果不明SUPERSEDED已替代EXPIRED已过期',
    expires_at DATETIME(6) NOT NULL COMMENT '授权会话到期时间UTC',

    processing_lease_id VARCHAR(36) NULL COMMENT '回调处理租约标识',
    processing_lease_expires_at DATETIME(6) NULL COMMENT '回调处理租约到期时间UTC',
    exchange_attempted_at DATETIME(6) NULL COMMENT '最近一次提交授权码交换的时间UTC',
    candidate_token_json JSON NULL COMMENT '授权主体核验前短期保留的原值Token及期限，完成或过期后清理，仅后端读取',
    finished_at DATETIME(6) NULL COMMENT '会话结束时间UTC',
    error_code VARCHAR(100) NULL COMMENT '会话失败或待处理编码',
    error_message VARCHAR(500) NULL COMMENT '脱敏后的失败或待处理说明',
    revision BIGINT NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间UTC',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间UTC',

    PRIMARY KEY (id),
    UNIQUE KEY uk_oauth_session_hash (session_hash),
    KEY ix_oauth_session_connection (tenant_id, platform_code, connector_id, created_at),
    KEY ix_oauth_session_expiry (status, expires_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_general_ci
  COMMENT='第三方平台OAuth后端授权会话及一次性回调校验';

ALTER TABLE integration_huolala_connector
    ADD COLUMN platform_code VARCHAR(32) NOT NULL DEFAULT 'HUOLALA'
        COMMENT '第三方平台编码，货拉拉固定HUOLALA' AFTER tenant_id;

ALTER TABLE integration_huolala_voucher
    ADD COLUMN platform_code VARCHAR(32) NOT NULL DEFAULT 'HUOLALA'
        COMMENT '第三方平台编码，货拉拉固定HUOLALA' AFTER tenant_id;

-- 老版文件令牌不迁入公共表。切换后需经新的后端浏览器会话完成一次授权。
UPDATE integration_huolala_connector
SET auth_state_hash=NULL,auth_actor_id=NULL,auth_expires_at=NULL,
    token_secret_ref=NULL,token_expires_at=NULL,auth_status='UNAUTHORIZED';
