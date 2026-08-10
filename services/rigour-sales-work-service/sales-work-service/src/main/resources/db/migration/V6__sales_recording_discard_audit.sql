-- 短录音只保留最小审计元数据，不保存音频对象；client_clip_id保证客户端重试幂等。

CREATE TABLE sales_recording_discard (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    client_clip_id VARCHAR(128) NOT NULL,
    client_duration_ms BIGINT UNSIGNED NOT NULL,
    recorded_from DATETIME(6) NOT NULL,
    recorded_to DATETIME(6) NOT NULL,
    discard_reason VARCHAR(32) NOT NULL,
    disposition VARCHAR(32) NOT NULL DEFAULT 'DISCARDED_NOT_STORED',
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_recording_discard PRIMARY KEY (id),
    CONSTRAINT uk_sales_recording_discard_client UNIQUE (tenant_id, visit_id, client_clip_id),
    CONSTRAINT fk_sales_recording_discard_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id),
    CONSTRAINT ck_sales_recording_discard_duration CHECK (client_duration_ms < 600000),
    CONSTRAINT ck_sales_recording_discard_reason CHECK (discard_reason IN ('TOO_SHORT')),
    INDEX idx_sales_recording_discard_visit (tenant_id, visit_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
