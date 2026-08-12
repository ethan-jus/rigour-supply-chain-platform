-- Integration Schema V7：商品图片异步上传任务及可恢复的逐图状态。
-- 商品原始字段仍由订货宝连接器负责读取；ERP 只在任务完成后读取 object key。

CREATE TABLE integration_product_media_job (
    id               BINARY(16)      NOT NULL,
    tenant_id        BINARY(16)      NOT NULL,
    connector_id     BINARY(16)      NOT NULL,
    status           VARCHAR(16)     NOT NULL DEFAULT 'QUEUED',
    total_images     BIGINT UNSIGNED NOT NULL DEFAULT 0,
    completed_images BIGINT UNSIGNED NOT NULL DEFAULT 0,
    failed_images    BIGINT UNSIGNED NOT NULL DEFAULT 0,
    error_code       VARCHAR(64)     NULL,
    error_message    VARCHAR(2000)   NULL,
    created_at       DATETIME(6)     NOT NULL,
    created_by       BINARY(16)      NULL,
    started_at       DATETIME(6)     NULL,
    finished_at      DATETIME(6)     NULL,
    updated_at       DATETIME(6)     NOT NULL,
    version          BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_product_media_job PRIMARY KEY (id),
    CONSTRAINT ck_integration_product_media_job_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    INDEX idx_integration_product_media_job_tenant (
        tenant_id, connector_id, created_at
    ),
    INDEX idx_integration_product_media_job_status (
        status, updated_at
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品图片异步上传批次';

CREATE TABLE integration_product_media_item (
    id                  BINARY(16)      NOT NULL,
    job_id              BINARY(16)      NOT NULL,
    tenant_id           BINARY(16)      NOT NULL,
    connector_id        BINARY(16)      NOT NULL,
    source_product_id   VARCHAR(128)    NOT NULL,
    source_resource_id  VARCHAR(128)    NULL,
    source_goods_id     VARCHAR(128)    NULL,
    source_url          VARCHAR(2048)   NULL,
    original_name       VARCHAR(255)    NULL,
    source_file_name    VARCHAR(512)    NULL,
    sort_order          INT             NULL,
    status              VARCHAR(16)     NOT NULL DEFAULT 'PENDING',
    object_key          VARCHAR(512)    NULL,
    content_type        VARCHAR(128)    NULL,
    attempts            INT UNSIGNED    NOT NULL DEFAULT 0,
    next_retry_at       DATETIME(6)     NULL,
    error_code          VARCHAR(64)     NULL,
    error_message       VARCHAR(2000)   NULL,
    created_at          DATETIME(6)     NOT NULL,
    updated_at          DATETIME(6)     NOT NULL,
    version             BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_integration_product_media_item PRIMARY KEY (id),
    CONSTRAINT fk_integration_product_media_item_job FOREIGN KEY (job_id)
        REFERENCES integration_product_media_job (id),
    CONSTRAINT ck_integration_product_media_item_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')
    ),
    INDEX idx_integration_product_media_item_queue (
        status, next_retry_at, created_at
    ),
    INDEX idx_integration_product_media_item_job (
        job_id, status
    ),
    INDEX idx_integration_product_media_item_lookup (
        tenant_id, connector_id, job_id, source_product_id, source_resource_id, sort_order
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品图片逐项上传状态';
