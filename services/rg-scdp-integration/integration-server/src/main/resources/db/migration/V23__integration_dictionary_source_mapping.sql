-- 来源枚举映射由 Integration 持有，标准字典仍通过 Settings API 读取。
CREATE TABLE integration_dictionary_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id CHAR(36) NOT NULL,
    source_system VARCHAR(24) NOT NULL,
    source_scope VARCHAR(120) NOT NULL COMMENT '来源表或同步对象范围',
    dictionary_code VARCHAR(50) NOT NULL,
    source_field VARCHAR(120) NOT NULL,
    source_value VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    source_value_hash BINARY(32) NOT NULL,
    target_dictionary_code VARCHAR(50) NULL,
    target_item_code VARCHAR(50) NULL,
    mapping_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    manual_override TINYINT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    first_seen DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(50) NOT NULL,
    updated_by VARCHAR(50) NOT NULL,
    UNIQUE KEY uk_dictionary_source (tenant_id,source_system,source_scope,dictionary_code,source_field,source_value_hash),
    KEY idx_dictionary_mapping_pending (tenant_id,dictionary_code,mapping_status),
    CONSTRAINT ck_dictionary_mapping_status CHECK (mapping_status IN ('PENDING','MAPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户来源枚举映射及待映射值';
