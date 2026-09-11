-- 拜访位置仅作留痕和复核；保留历史字段，新增质量、原始接收证据和追加审计。
ALTER TABLE temp_sales_checkin_submission
    DROP CHECK ck_temp_sales_checkin_submission_coordinates,
    DROP CHECK ck_temp_sales_checkin_submission_location_verification,
    ADD COLUMN location_quality VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN location_raw_timestamp VARCHAR(128) NULL,
    ADD COLUMN location_received_at DATETIME(6) NULL,
    ADD COLUMN location_client_received_at DATETIME(6) NULL,
    ADD COLUMN location_source VARCHAR(64) NULL,
    ADD COLUMN location_evidence_json JSON NULL,
    ADD COLUMN store_longitude_snapshot DECIMAL(10,7) NULL,
    ADD COLUMN store_latitude_snapshot DECIMAL(10,7) NULL,
    ADD COLUMN distance_meters DECIMAL(12,2) NULL,
    ADD COLUMN review_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN reviewed_by VARCHAR(128) NULL,
    ADD COLUMN reviewed_at DATETIME(6) NULL,
    ADD CONSTRAINT ck_temp_checkin_coords_v21 CHECK (
        (longitude IS NULL AND latitude IS NULL) OR
        (longitude IS NOT NULL AND latitude IS NOT NULL AND longitude BETWEEN -180 AND 180
            AND latitude BETWEEN -90 AND 90)),
    ADD CONSTRAINT ck_temp_checkin_verification_v21
        CHECK (location_verification_status IN ('LEGACY','VERIFIED','UNVERIFIED')),
    ADD CONSTRAINT ck_temp_checkin_quality_v21 CHECK (location_quality IN
        ('LEGACY','GOOD','LOW_ACCURACY','STALE','TIME_UNKNOWN','MISSING','USER_REPORTED','OUT_OF_RANGE','STORE_UNLOCATED')),
    ADD CONSTRAINT ck_temp_checkin_review_v21 CHECK (review_status IN ('PENDING','APPROVED','FOLLOW_UP','FLAGGED')),
    ADD INDEX idx_temp_checkin_review (tenant_id, review_status, submitted_at, id),
    ADD INDEX idx_temp_checkin_quality (tenant_id, location_quality, submitted_at, id);

ALTER TABLE temp_sales_checkin_store
    DROP CHECK ck_temp_sales_checkin_store_coordinates,
    DROP CHECK ck_temp_sales_checkin_store_location_verification,
    ADD CONSTRAINT ck_temp_checkin_store_coords_v21 CHECK (
        (longitude IS NULL AND latitude IS NULL) OR
        (longitude IS NOT NULL AND latitude IS NOT NULL AND longitude BETWEEN -180 AND 180
            AND latitude BETWEEN -90 AND 90)),
    ADD CONSTRAINT ck_temp_checkin_store_verification_v21
        CHECK (location_verification_status IN ('LEGACY','VERIFIED','UNVERIFIED'));

CREATE TABLE temp_sales_checkin_evidence_event (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    submission_id BINARY(16) NOT NULL,
    client_event_id BINARY(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NULL,
    note VARCHAR(2000) NULL,
    actor VARCHAR(128) NOT NULL,
    object_key VARCHAR(1024) NULL,
    sha256 VARCHAR(64) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_temp_evidence_event (tenant_id, submission_id, client_event_id),
    KEY idx_temp_evidence_timeline (tenant_id, submission_id, occurred_at, id),
    CONSTRAINT fk_temp_evidence_submission FOREIGN KEY (tenant_id, submission_id)
        REFERENCES temp_sales_checkin_submission (tenant_id, id) ON DELETE CASCADE
);
