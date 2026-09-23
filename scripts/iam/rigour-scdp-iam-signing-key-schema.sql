-- IAM 签名密钥表的标准定义，来自项目 V7；排序规则统一为 utf8mb4_general_ci。
-- 目标：rigour_scdp_iam。先保留/备份异常旧表，并确认其他 IAM 表结构是否正常。
-- 本文件不删除、不重命名现有表，也不迁移任何记录；若同名表存在会报错停止。
-- 它不会创建私钥或登记 rigour-prod-signing-v1。表修复后还需恢复匹配现有 PEM 的生产密钥记录。
-- 生产 spring.flyway.enabled 继续保持 false。仅作人工修复用，不是 Flyway 迁移脚本。

USE `rigour_scdp_iam`;

CREATE TABLE iam_signing_key (
    id                 BINARY(16)    NOT NULL,
    kid                VARCHAR(100)  NOT NULL,
    algorithm          VARCHAR(16)   NOT NULL,
    key_use            VARCHAR(16)   NOT NULL,
    public_jwk_json    JSON          NOT NULL,
    private_key_ref    VARCHAR(255)  NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    not_before         DATETIME(6)   NOT NULL,
    not_after          DATETIME(6)   NOT NULL,
    activated_at       DATETIME(6)   NULL,
    retired_at         DATETIME(6)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    created_by         BINARY(16)    NULL,
    CONSTRAINT pk_iam_signing_key PRIMARY KEY (id),
    CONSTRAINT uk_iam_signing_key_kid UNIQUE (kid),
    CONSTRAINT ck_iam_signing_key_algorithm CHECK (algorithm = 'RS256'),
    CONSTRAINT ck_iam_signing_key_use CHECK (key_use = 'sig'),
    CONSTRAINT ck_iam_signing_key_status CHECK (
        status IN ('PENDING', 'ACTIVE', 'VERIFY_ONLY', 'RETIRED', 'REVOKED')
    ),
    CONSTRAINT ck_iam_signing_key_period CHECK (not_after > not_before),
    CONSTRAINT ck_iam_signing_key_activation CHECK (
        (status = 'PENDING' AND activated_at IS NULL)
        OR (status <> 'PENDING' AND activated_at IS NOT NULL)
    ),
    CONSTRAINT ck_iam_signing_key_retirement CHECK (
        (status IN ('RETIRED', 'REVOKED') AND retired_at IS NOT NULL)
        OR (status NOT IN ('RETIRED', 'REVOKED') AND retired_at IS NULL)
    ),
    INDEX idx_iam_signing_key_status_period (status, not_before, not_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='只保存公钥和私钥引用的签名密钥元数据';
