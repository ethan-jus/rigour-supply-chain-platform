-- 临时销售打卡后台改为应用级账号与会话认证。
-- 密码只保存 PBKDF2 摘要；会话只保存随机令牌摘要，浏览器原始令牌不得进入数据库或日志。

CREATE TABLE temp_sales_checkin_city (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    name VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_temp_sales_checkin_city PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_city_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_temp_sales_checkin_city_name UNIQUE (tenant_id, name),
    CONSTRAINT ck_temp_sales_checkin_city_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_temp_sales_checkin_city_sort CHECK (sort_order >= 0),
    INDEX idx_temp_sales_checkin_city_lookup (tenant_id, status, sort_order, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时打卡启用城市目录';

CREATE TABLE temp_sales_checkin_admin_account (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    role VARCHAR(24) NOT NULL,
    city_id BINARY(16) NULL,
    password_hash VARCHAR(512) NOT NULL,
    must_change_password TINYINT UNSIGNED NOT NULL DEFAULT 1,
    temporary_password_expires_at DATETIME(6) NULL,
    password_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    failed_login_attempts INT UNSIGNED NOT NULL DEFAULT 0,
    locked_until DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_temp_sales_checkin_admin_account PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_admin_account_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_temp_sales_checkin_admin_account_username UNIQUE (tenant_id, username),
    CONSTRAINT fk_temp_sales_checkin_admin_account_city
        FOREIGN KEY (tenant_id, city_id) REFERENCES temp_sales_checkin_city (tenant_id, id),
    CONSTRAINT ck_temp_sales_checkin_admin_account_role
        CHECK (role IN ('GLOBAL_ADMIN', 'CITY_ADMIN')),
    CONSTRAINT ck_temp_sales_checkin_admin_account_role_city
        CHECK (
            (role = 'GLOBAL_ADMIN' AND city_id IS NULL)
            OR (role = 'CITY_ADMIN' AND city_id IS NOT NULL)
        ),
    CONSTRAINT ck_temp_sales_checkin_admin_account_password_change
        CHECK (must_change_password IN (0, 1)),
    CONSTRAINT ck_temp_sales_checkin_admin_account_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    INDEX idx_temp_sales_checkin_admin_account_city (tenant_id, city_id, status),
    INDEX idx_temp_sales_checkin_admin_account_lock (tenant_id, locked_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时打卡后台管理员账号';

CREATE TABLE temp_sales_checkin_admin_session (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    account_id BINARY(16) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    csrf_token VARCHAR(128) NOT NULL,
    password_version BIGINT UNSIGNED NOT NULL,
    client_ip_hash CHAR(64) NULL,
    user_agent_hash CHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    idle_expires_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT pk_temp_sales_checkin_admin_session PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_admin_session_token UNIQUE (tenant_id, token_hash),
    CONSTRAINT fk_temp_sales_checkin_admin_session_account
        FOREIGN KEY (tenant_id, account_id)
        REFERENCES temp_sales_checkin_admin_account (tenant_id, id),
    CONSTRAINT ck_temp_sales_checkin_admin_session_expiry
        CHECK (last_seen_at >= created_at AND idle_expires_at > created_at AND expires_at > created_at),
    INDEX idx_temp_sales_checkin_admin_session_account
        (tenant_id, account_id, revoked_at, expires_at),
    INDEX idx_temp_sales_checkin_admin_session_expiry
        (tenant_id, revoked_at, idle_expires_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时打卡后台不透明会话';

-- 为已有临时打卡租户幂等种入当前上线的17个城市；后续新增城市由后台目录维护。
INSERT IGNORE INTO temp_sales_checkin_city
    (id, tenant_id, name, status, sort_order, created_at, updated_at)
SELECT UUID_TO_BIN(UUID()), tenants.tenant_id, seeds.name, 'ACTIVE', seeds.sort_order,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
  FROM (
        SELECT tenant_id FROM temp_sales_checkin_salesperson
        UNION
        SELECT tenant_id FROM temp_sales_checkin_store
        UNION
        SELECT tenant_id FROM temp_sales_checkin_submission
       ) tenants
 CROSS JOIN (
        SELECT '北京' AS name, 10 AS sort_order
        UNION ALL SELECT '深圳', 20
        UNION ALL SELECT '杭州', 30
        UNION ALL SELECT '成都', 40
        UNION ALL SELECT '武汉', 50
        UNION ALL SELECT '西安', 60
        UNION ALL SELECT '长沙', 70
        UNION ALL SELECT '南京', 80
        UNION ALL SELECT '石家庄', 90
        UNION ALL SELECT '重庆', 100
        UNION ALL SELECT '苏州', 110
        UNION ALL SELECT '金华', 120
        UNION ALL SELECT '东莞', 130
        UNION ALL SELECT '上海', 140
        UNION ALL SELECT '洛阳', 150
        UNION ALL SELECT '广州', 160
        UNION ALL SELECT '总部', 170
       ) seeds;
