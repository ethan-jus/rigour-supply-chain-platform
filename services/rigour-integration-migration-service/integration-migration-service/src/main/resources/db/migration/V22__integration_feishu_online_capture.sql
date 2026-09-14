-- 只追加完整在线分页证据，不触发导入、附件下载或领域投影；时间字段统一 UTC。
CREATE TABLE integration_feishu_online_capture (
    id             BINARY(16)   NOT NULL COMMENT '采集证据ID',
    tenant_id      BINARY(16)   NOT NULL COMMENT '授权租户ID',
    actor_id       BINARY(16)   NOT NULL COMMENT '发起采集的用户ID',
    source_id      VARCHAR(128) NOT NULL COMMENT '服务端来源配置ID',
    source_name    VARCHAR(255) NOT NULL COMMENT '采集时来源名称',
    source_url     VARCHAR(2000) NOT NULL COMMENT '采集时来源Base地址',
    started_at     DATETIME(6)  NOT NULL COMMENT '开始时间UTC',
    completed_at   DATETIME(6)  NOT NULL COMMENT '完成时间UTC',
    complete       BOOLEAN      NOT NULL COMMENT '仅表示分页完整，不表示跨页原子快照',
    filtered       BOOLEAN      NOT NULL COMMENT '任一表指定view时为true',
    checksum       CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'payload_json UTF-8 SHA-256',
    record_count   INT          NOT NULL COMMENT '所有指定表采集行数',
    page_count     INT          NOT NULL COMMENT '记录请求总页数，不含授权请求',
    payload_json   LONGTEXT     NOT NULL COMMENT '规范化JSON，原始fields保留',
    CONSTRAINT pk_feishu_online_capture PRIMARY KEY (id),
    CONSTRAINT ck_feishu_online_capture_complete CHECK (complete = TRUE AND completed_at >= started_at),
    CONSTRAINT ck_feishu_online_capture_count CHECK (record_count BETWEEN 0 AND 20000 AND page_count BETWEEN 1 AND 200),
    CONSTRAINT ck_feishu_online_capture_json CHECK (JSON_VALID(payload_json)),
    CONSTRAINT ck_feishu_online_capture_size CHECK (OCTET_LENGTH(payload_json) <= 33554432),
    INDEX idx_feishu_online_capture_tenant (tenant_id, completed_at),
    INDEX idx_feishu_online_capture_source (tenant_id, source_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='飞书在线只读对账证据';
