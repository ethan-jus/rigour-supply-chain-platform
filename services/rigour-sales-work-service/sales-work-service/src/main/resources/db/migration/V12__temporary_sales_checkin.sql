-- 临时销售拜访打卡：仅新增销售、门店和提交三张独立表。
-- 公开表单不依赖 IAM 身份，但所有业务键、关联、幂等和索引仍以 tenant_id 隔离。

CREATE TABLE temp_sales_checkin_salesperson (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    source_record_id VARCHAR(128) NULL,
    name VARCHAR(128) NOT NULL,
    city VARCHAR(64) NOT NULL,
    position VARCHAR(64) NULL,
    employment_status VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_temp_sales_checkin_salesperson PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_salesperson_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_temp_sales_checkin_salesperson_source_record
        UNIQUE (tenant_id, source_record_id),
    CONSTRAINT uk_temp_sales_checkin_salesperson_name_city UNIQUE (tenant_id, name, city),
    CONSTRAINT ck_temp_sales_checkin_salesperson_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_temp_sales_checkin_salesperson_sort
        CHECK (sort_order >= 0),
    INDEX idx_temp_sales_checkin_salesperson_lookup (
        tenant_id, city, status, sort_order, name
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时打卡页面销售下拉目录';

CREATE TABLE temp_sales_checkin_store (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    client_store_id BINARY(16) NOT NULL,
    source_record_id VARCHAR(128) NULL,
    city VARCHAR(64) NOT NULL,
    creator_salesperson_id BINARY(16) NULL,
    attribute VARCHAR(64) NOT NULL,
    name VARCHAR(256) NOT NULL,
    operating_status VARCHAR(64) NOT NULL,
    contact_name VARCHAR(128) NOT NULL,
    contact_phone VARCHAR(32) NULL,
    area_range VARCHAR(64) NOT NULL,
    facility_count VARCHAR(128) NOT NULL,
    business_types_json JSON NOT NULL,
    intended_businesses_json JSON NOT NULL,
    cooperation_intent VARCHAR(64) NOT NULL,
    store_grade VARCHAR(64) NULL,
    tags_json JSON NOT NULL,
    longitude DECIMAL(10,7) NULL,
    latitude DECIMAL(10,7) NULL,
    accuracy_meters DECIMAL(10,2) NULL,
    location_captured_at DATETIME(6) NULL,
    location_note VARCHAR(512) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_temp_sales_checkin_store PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_store_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_temp_sales_checkin_store_client_id UNIQUE (tenant_id, client_store_id),
    CONSTRAINT uk_temp_sales_checkin_store_source_record UNIQUE (tenant_id, source_record_id),
    CONSTRAINT fk_temp_sales_checkin_store_creator
        FOREIGN KEY (tenant_id, creator_salesperson_id)
        REFERENCES temp_sales_checkin_salesperson (tenant_id, id),
    CONSTRAINT ck_temp_sales_checkin_store_status
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_temp_sales_checkin_store_coordinates
        CHECK (
            (
                longitude IS NULL
                AND latitude IS NULL
                AND accuracy_meters IS NULL
                AND location_captured_at IS NULL
            )
            OR (
                longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND longitude BETWEEN -180 AND 180
                AND latitude BETWEEN -90 AND 90
                AND accuracy_meters >= 0
            )
        ),
    INDEX idx_temp_sales_checkin_store_lookup (tenant_id, city, status, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时打卡页面门店目录与现场位置事实';

CREATE TABLE temp_sales_checkin_submission (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    client_submission_id BINARY(16) NOT NULL,
    submission_key_hash CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    city VARCHAR(64) NOT NULL,
    salesperson_id BINARY(16) NOT NULL,
    salesperson_name_snapshot VARCHAR(128) NOT NULL,
    store_id BINARY(16) NOT NULL,
    store_name_snapshot VARCHAR(256) NOT NULL,
    customer_name VARCHAR(128) NOT NULL,
    customer_phone VARCHAR(32) NULL,
    visit_result VARCHAR(2000) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    accuracy_meters DECIMAL(10,2) NOT NULL,
    location_captured_at DATETIME(6) NOT NULL,
    location_note VARCHAR(512) NULL,
    privacy_accepted TINYINT UNSIGNED NOT NULL,
    storefront_photo_object_key VARCHAR(512) NULL,
    storefront_photo_content_type VARCHAR(128) NULL,
    storefront_photo_size_bytes BIGINT UNSIGNED NULL,
    storefront_photo_sha256 CHAR(64) NULL,
    storefront_photo_original_filename VARCHAR(512) NULL,
    wechat_screenshot_object_key VARCHAR(512) NULL,
    wechat_screenshot_content_type VARCHAR(128) NULL,
    wechat_screenshot_size_bytes BIGINT UNSIGNED NULL,
    wechat_screenshot_sha256 CHAR(64) NULL,
    wechat_screenshot_original_filename VARCHAR(512) NULL,
    audio_object_key VARCHAR(512) NULL,
    audio_content_type VARCHAR(128) NULL,
    audio_size_bytes BIGINT UNSIGNED NULL,
    audio_sha256 CHAR(64) NULL,
    audio_original_filename VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    submitted_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_temp_sales_checkin_submission PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_submission_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_temp_sales_checkin_submission_client_id
        UNIQUE (tenant_id, client_submission_id),
    CONSTRAINT fk_temp_sales_checkin_submission_salesperson
        FOREIGN KEY (tenant_id, salesperson_id)
        REFERENCES temp_sales_checkin_salesperson (tenant_id, id),
    CONSTRAINT fk_temp_sales_checkin_submission_store
        FOREIGN KEY (tenant_id, store_id)
        REFERENCES temp_sales_checkin_store (tenant_id, id),
    CONSTRAINT ck_temp_sales_checkin_submission_status
        CHECK (status IN ('DRAFT', 'SUBMITTED')),
    CONSTRAINT ck_temp_sales_checkin_submission_status_time
        CHECK (
            (status = 'DRAFT' AND submitted_at IS NULL)
            OR (status = 'SUBMITTED' AND submitted_at IS NOT NULL)
        ),
    CONSTRAINT ck_temp_sales_checkin_submission_privacy
        CHECK (privacy_accepted = 1),
    CONSTRAINT ck_temp_sales_checkin_submission_coordinates
        CHECK (
            longitude BETWEEN -180 AND 180
            AND latitude BETWEEN -90 AND 90
            AND accuracy_meters >= 0
        ),
    CONSTRAINT ck_temp_sales_checkin_submission_hash
        CHECK (submission_key_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_temp_sales_checkin_submission_storefront
        CHECK (
            (
                storefront_photo_object_key IS NULL
                AND storefront_photo_content_type IS NULL
                AND storefront_photo_size_bytes IS NULL
                AND storefront_photo_sha256 IS NULL
                AND storefront_photo_original_filename IS NULL
            )
            OR (
                storefront_photo_object_key IS NOT NULL
                AND storefront_photo_content_type IS NOT NULL
                AND storefront_photo_size_bytes IS NOT NULL
                AND storefront_photo_size_bytes > 0
                AND storefront_photo_sha256 REGEXP '^[0-9a-f]{64}$'
                AND storefront_photo_original_filename IS NOT NULL
            )
        ),
    CONSTRAINT ck_temp_sales_checkin_submission_wechat
        CHECK (
            (
                wechat_screenshot_object_key IS NULL
                AND wechat_screenshot_content_type IS NULL
                AND wechat_screenshot_size_bytes IS NULL
                AND wechat_screenshot_sha256 IS NULL
                AND wechat_screenshot_original_filename IS NULL
            )
            OR (
                wechat_screenshot_object_key IS NOT NULL
                AND wechat_screenshot_content_type IS NOT NULL
                AND wechat_screenshot_size_bytes IS NOT NULL
                AND wechat_screenshot_size_bytes > 0
                AND wechat_screenshot_sha256 REGEXP '^[0-9a-f]{64}$'
                AND wechat_screenshot_original_filename IS NOT NULL
            )
        ),
    CONSTRAINT ck_temp_sales_checkin_submission_audio
        CHECK (
            (
                audio_object_key IS NULL
                AND audio_content_type IS NULL
                AND audio_size_bytes IS NULL
                AND audio_sha256 IS NULL
                AND audio_original_filename IS NULL
            )
            OR (
                audio_object_key IS NOT NULL
                AND audio_content_type IS NOT NULL
                AND audio_size_bytes IS NOT NULL
                AND audio_size_bytes > 0
                AND audio_sha256 REGEXP '^[0-9a-f]{64}$'
                AND audio_original_filename IS NOT NULL
            )
        ),
    CONSTRAINT ck_temp_sales_checkin_submission_complete_media
        CHECK (
            status = 'DRAFT'
            OR storefront_photo_object_key IS NOT NULL
        ),
    INDEX idx_temp_sales_checkin_submission_created (tenant_id, created_at),
    INDEX idx_temp_sales_checkin_submission_status (tenant_id, status, submitted_at),
    INDEX idx_temp_sales_checkin_submission_hash (tenant_id, submission_key_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='临时销售拜访打卡表单、位置和三类媒体元数据';
