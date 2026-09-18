-- Sales Work V1：销售组织投影、规则、H5打卡、定位、拜访、录音、复核和日结事实。
-- 跨域ID只保存引用，不建立跨Schema外键；CRM门店和HR正式考勤不在本Schema维护。

CREATE TABLE sales_profile (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    employee_id BINARY(16) NOT NULL,
    sales_no VARCHAR(64) NOT NULL,
    city_org_id BINARY(16) NULL,
    status VARCHAR(24) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_profile PRIMARY KEY (id),
    CONSTRAINT uk_sales_profile_employee UNIQUE (tenant_id, employee_id),
    CONSTRAINT uk_sales_profile_no UNIQUE (tenant_id, sales_no),
    INDEX idx_sales_profile_city_status (tenant_id, city_org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售业务画像，引用HR员工和任职事实';

CREATE TABLE sales_team (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    team_code VARCHAR(64) NOT NULL,
    team_name VARCHAR(128) NOT NULL,
    city_org_id BINARY(16) NULL,
    manager_profile_id BINARY(16) NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_team PRIMARY KEY (id),
    CONSTRAINT uk_sales_team_code UNIQUE (tenant_id, team_code),
    CONSTRAINT fk_sales_team_manager FOREIGN KEY (manager_profile_id) REFERENCES sales_profile (id),
    INDEX idx_sales_team_city_status (tenant_id, city_org_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售团队';

CREATE TABLE sales_team_member (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    team_id BINARY(16) NOT NULL,
    sales_profile_id BINARY(16) NOT NULL,
    member_role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_team_member PRIMARY KEY (id),
    CONSTRAINT uk_sales_team_member_period UNIQUE (tenant_id, team_id, sales_profile_id, effective_from),
    CONSTRAINT fk_sales_team_member_team FOREIGN KEY (team_id) REFERENCES sales_team (id),
    CONSTRAINT fk_sales_team_member_profile FOREIGN KEY (sales_profile_id) REFERENCES sales_profile (id),
    INDEX idx_sales_team_member_active (tenant_id, sales_profile_id, status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售团队成员有效期关系';

CREATE TABLE sales_field_policy (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    policy_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_field_policy PRIMARY KEY (id),
    CONSTRAINT uk_sales_field_policy_code UNIQUE (tenant_id, policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售外勤规则稳定身份';

CREATE TABLE sales_field_policy_version (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    policy_id BINARY(16) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    publish_status VARCHAR(24) NOT NULL,
    timezone_id VARCHAR(64) NOT NULL,
    business_day_cutoff TIME NOT NULL,
    check_in_window_start TIME NULL,
    check_in_window_end TIME NULL,
    check_out_window_start TIME NULL,
    check_out_window_end TIME NULL,
    standard_work_minutes INT UNSIGNED NOT NULL,
    minimum_work_minutes INT UNSIGNED NOT NULL,
    require_check_out TINYINT UNSIGNED NOT NULL DEFAULT 1,
    allow_adjustment TINYINT UNSIGNED NOT NULL DEFAULT 1,
    adjustment_deadline_hours INT UNSIGNED NULL,
    location_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
    location_interval_minutes INT UNSIGNED NOT NULL DEFAULT 20,
    minimum_location_accuracy_meters DECIMAL(8,2) NULL,
    offline_upload_deadline_minutes INT UNSIGNED NOT NULL DEFAULT 120,
    effective_from DATETIME(6) NULL,
    effective_to DATETIME(6) NULL,
    approved_by BINARY(16) NULL,
    approved_at DATETIME(6) NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_field_policy_version PRIMARY KEY (id),
    CONSTRAINT uk_sales_field_policy_version UNIQUE (tenant_id, policy_id, version_no),
    CONSTRAINT fk_sales_field_policy_version_policy FOREIGN KEY (policy_id) REFERENCES sales_field_policy (id),
    CONSTRAINT ck_sales_field_policy_booleans CHECK (
        require_check_out IN (0,1) AND allow_adjustment IN (0,1) AND location_enabled IN (0,1)
    ),
    CONSTRAINT ck_sales_field_policy_minutes CHECK (
        standard_work_minutes > 0 AND minimum_work_minutes <= standard_work_minutes
        AND location_interval_minutes > 0
    ),
    INDEX idx_sales_field_policy_effective (tenant_id, publish_status, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化外勤签到、签退和定位规则';

CREATE TABLE sales_visit_policy (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    policy_code VARCHAR(64) NOT NULL,
    policy_name VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit_policy PRIMARY KEY (id),
    CONSTRAINT uk_sales_visit_policy_code UNIQUE (tenant_id, policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售拜访规则稳定身份';

CREATE TABLE sales_visit_policy_version (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    policy_id BINARY(16) NOT NULL,
    version_no INT UNSIGNED NOT NULL,
    publish_status VARCHAR(24) NOT NULL,
    require_assigned_target TINYINT UNSIGNED NOT NULL DEFAULT 1,
    allow_prospect_target TINYINT UNSIGNED NOT NULL DEFAULT 1,
    check_in_radius_meters INT UNSIGNED NOT NULL,
    minimum_dwell_minutes INT UNSIGNED NOT NULL,
    required_photo_count INT UNSIGNED NOT NULL DEFAULT 0,
    recording_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
    minimum_recording_seconds INT UNSIGNED NOT NULL DEFAULT 600,
    maximum_clip_gap_seconds INT UNSIGNED NOT NULL DEFAULT 30,
    ai_asr_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ai_relevance_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ai_duplicate_enabled TINYINT UNSIGNED NOT NULL DEFAULT 1,
    ai_auto_confirm_threshold DECIMAL(5,4) NULL,
    effective_from DATETIME(6) NULL,
    effective_to DATETIME(6) NULL,
    approved_by BINARY(16) NULL,
    approved_at DATETIME(6) NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit_policy_version PRIMARY KEY (id),
    CONSTRAINT uk_sales_visit_policy_version UNIQUE (tenant_id, policy_id, version_no),
    CONSTRAINT fk_sales_visit_policy_version_policy FOREIGN KEY (policy_id) REFERENCES sales_visit_policy (id),
    CONSTRAINT ck_sales_visit_policy_booleans CHECK (
        require_assigned_target IN (0,1) AND allow_prospect_target IN (0,1)
        AND recording_enabled IN (0,1) AND ai_asr_enabled IN (0,1)
        AND ai_relevance_enabled IN (0,1) AND ai_duplicate_enabled IN (0,1)
    ),
    INDEX idx_sales_visit_policy_effective (tenant_id, publish_status, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化客户门店、现场证据、录音和AI规则';

CREATE TABLE sales_policy_scope (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    policy_type VARCHAR(24) NOT NULL,
    policy_version_id BINARY(16) NOT NULL,
    scope_type VARCHAR(24) NOT NULL,
    scope_id BINARY(16) NULL,
    priority INT NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    exception_reason VARCHAR(512) NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_policy_scope PRIMARY KEY (id),
    CONSTRAINT uk_sales_policy_scope UNIQUE (
        tenant_id, policy_type, policy_version_id, scope_type, scope_id, effective_from
    ),
    INDEX idx_sales_policy_scope_resolve (tenant_id, policy_type, scope_type, scope_id, status, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外勤和拜访规则的租户城市团队员工适用范围';

CREATE TABLE crm_store_projection (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NULL,
    store_id BINARY(16) NOT NULL,
    customer_name VARCHAR(256) NULL,
    store_name VARCHAR(256) NOT NULL,
    store_address VARCHAR(512) NULL,
    longitude DECIMAL(10,7) NULL,
    latitude DECIMAL(10,7) NULL,
    store_status VARCHAR(24) NOT NULL,
    source_version BIGINT UNSIGNED NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    projected_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_crm_store_projection PRIMARY KEY (id),
    CONSTRAINT uk_crm_store_projection_store UNIQUE (tenant_id, store_id),
    INDEX idx_crm_store_projection_customer (tenant_id, customer_id, store_status),
    INDEX idx_crm_store_projection_name (tenant_id, store_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM客户门店最小只读投影，不得由Sales Work页面修改';

CREATE TABLE crm_sales_assignment_projection (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    sales_profile_id BINARY(16) NOT NULL,
    customer_id BINARY(16) NULL,
    store_id BINARY(16) NULL,
    assignment_type VARCHAR(24) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL,
    source_version BIGINT UNSIGNED NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    projected_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_crm_sales_assignment_projection PRIMARY KEY (id),
    CONSTRAINT uk_crm_sales_assignment_period UNIQUE (
        tenant_id, sales_profile_id, assignment_type, customer_id, store_id, effective_from
    ),
    CONSTRAINT fk_crm_sales_assignment_profile FOREIGN KEY (sales_profile_id) REFERENCES sales_profile (id),
    INDEX idx_crm_sales_assignment_target (tenant_id, customer_id, store_id, status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM销售客户门店归属最小只读投影';

CREATE TABLE sales_work_day (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    employee_id BINARY(16) NOT NULL,
    sales_profile_id BINARY(16) NOT NULL,
    business_date DATE NOT NULL,
    timezone_id VARCHAR(64) NOT NULL,
    field_policy_version_id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    checked_in_at DATETIME(6) NULL,
    checked_out_at DATETIME(6) NULL,
    verified_work_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    evidence_quality VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_work_day PRIMARY KEY (id),
    CONSTRAINT uk_sales_work_day_employee_date UNIQUE (tenant_id, employee_id, business_date),
    CONSTRAINT fk_sales_work_day_profile FOREIGN KEY (sales_profile_id) REFERENCES sales_profile (id),
    CONSTRAINT fk_sales_work_day_policy FOREIGN KEY (field_policy_version_id) REFERENCES sales_field_policy_version (id),
    INDEX idx_sales_work_day_team_view (tenant_id, business_date, status, sales_profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售人员单个业务日的外勤聚合根';

CREATE TABLE sales_punch_event (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    device_event_id VARCHAR(128) NOT NULL,
    client_occurred_at DATETIME(6) NULL,
    server_received_at DATETIME(6) NOT NULL,
    longitude DECIMAL(10,7) NULL,
    latitude DECIMAL(10,7) NULL,
    accuracy_meters DECIMAL(8,2) NULL,
    device_id_hash VARCHAR(128) NULL,
    network_type VARCHAR(32) NULL,
    evidence_status VARCHAR(24) NOT NULL,
    policy_version_id BINARY(16) NOT NULL,
    adjustment_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_punch_event PRIMARY KEY (id),
    CONSTRAINT uk_sales_punch_device_event UNIQUE (tenant_id, device_event_id),
    CONSTRAINT fk_sales_punch_work_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    CONSTRAINT fk_sales_punch_policy FOREIGN KEY (policy_version_id) REFERENCES sales_field_policy_version (id),
    INDEX idx_sales_punch_work_day_time (tenant_id, work_day_id, server_received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='H5签到签退补卡等不可变原始事件';

CREATE TABLE sales_location_session (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    expected_interval_minutes INT UNSIGNED NOT NULL,
    point_count INT UNSIGNED NOT NULL DEFAULT 0,
    interruption_count INT UNSIGNED NOT NULL DEFAULT 0,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_location_session PRIMARY KEY (id),
    CONSTRAINT fk_sales_location_session_work_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    INDEX idx_sales_location_session_active (tenant_id, work_day_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='签到至签退期间的位置采集会话';

CREATE TABLE sales_location_point (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    location_session_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    device_event_id VARCHAR(128) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    accuracy_meters DECIMAL(8,2) NULL,
    client_occurred_at DATETIME(6) NULL,
    server_received_at DATETIME(6) NOT NULL,
    source VARCHAR(24) NOT NULL,
    quality_status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_location_point PRIMARY KEY (id),
    CONSTRAINT uk_sales_location_device_event UNIQUE (tenant_id, device_event_id),
    CONSTRAINT fk_sales_location_point_session FOREIGN KEY (location_session_id) REFERENCES sales_location_session (id),
    CONSTRAINT fk_sales_location_point_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    INDEX idx_sales_location_day_time (tenant_id, work_day_id, server_received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售工作期间精确位置点，按租户和时间规划分区';

CREATE TABLE sales_work_interruption (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    interruption_type VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    ended_at DATETIME(6) NULL,
    duration_seconds INT UNSIGNED NULL,
    client_detail VARCHAR(512) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_work_interruption PRIMARY KEY (id),
    CONSTRAINT fk_sales_work_interruption_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    INDEX idx_sales_work_interruption_day (tenant_id, work_day_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='页面隐藏权限关闭定位失败等证据中断';

CREATE TABLE sales_work_day_summary (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    summary_version INT UNSIGNED NOT NULL,
    status VARCHAR(24) NOT NULL,
    check_in_at DATETIME(6) NULL,
    check_out_at DATETIME(6) NULL,
    verified_work_minutes INT UNSIGNED NOT NULL DEFAULT 0,
    location_point_count INT UNSIGNED NOT NULL DEFAULT 0,
    interruption_count INT UNSIGNED NOT NULL DEFAULT 0,
    submitted_visit_count INT UNSIGNED NOT NULL DEFAULT 0,
    effective_visit_count INT UNSIGNED NOT NULL DEFAULT 0,
    pending_review_visit_count INT UNSIGNED NOT NULL DEFAULT 0,
    evidence_quality VARCHAR(24) NOT NULL,
    exception_codes_json JSON NULL,
    finalized_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_work_day_summary PRIMARY KEY (id),
    CONSTRAINT uk_sales_work_day_summary_version UNIQUE (tenant_id, work_day_id, summary_version),
    CONSTRAINT fk_sales_work_day_summary_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    INDEX idx_sales_work_day_summary_status (tenant_id, status, finalized_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发送HR和BI的版本化销售工作日结候选';

CREATE TABLE sales_work_adjustment (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    adjustment_type VARCHAR(32) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    requested_by BINARY(16) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    decision_status VARCHAR(24) NOT NULL,
    decided_by BINARY(16) NULL,
    decided_at DATETIME(6) NULL,
    decision_note VARCHAR(1024) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_sales_work_adjustment PRIMARY KEY (id),
    CONSTRAINT fk_sales_work_adjustment_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    INDEX idx_sales_work_adjustment_queue (tenant_id, decision_status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='补卡纠错和申诉追加调整';

CREATE TABLE sales_visit_plan (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    sales_profile_id BINARY(16) NOT NULL,
    planned_date DATE NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    customer_id BINARY(16) NULL,
    store_id BINARY(16) NULL,
    crm_candidate_id BINARY(16) NULL,
    objective VARCHAR(512) NULL,
    status VARCHAR(24) NOT NULL,
    created_by BINARY(16) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit_plan PRIMARY KEY (id),
    CONSTRAINT fk_sales_visit_plan_profile FOREIGN KEY (sales_profile_id) REFERENCES sales_profile (id),
    INDEX idx_sales_visit_plan_person_date (tenant_id, sales_profile_id, planned_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售拜访计划任务';

CREATE TABLE sales_visit (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    work_day_id BINARY(16) NOT NULL,
    visit_plan_id BINARY(16) NULL,
    sales_profile_id BINARY(16) NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    customer_id BINARY(16) NULL,
    store_id BINARY(16) NULL,
    crm_candidate_id BINARY(16) NULL,
    visit_policy_version_id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    checked_in_at DATETIME(6) NULL,
    checked_out_at DATETIME(6) NULL,
    submitted_at DATETIME(6) NULL,
    finalized_at DATETIME(6) NULL,
    final_reason_code VARCHAR(64) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit PRIMARY KEY (id),
    CONSTRAINT fk_sales_visit_day FOREIGN KEY (work_day_id) REFERENCES sales_work_day (id),
    CONSTRAINT fk_sales_visit_plan FOREIGN KEY (visit_plan_id) REFERENCES sales_visit_plan (id),
    CONSTRAINT fk_sales_visit_profile FOREIGN KEY (sales_profile_id) REFERENCES sales_profile (id),
    CONSTRAINT fk_sales_visit_policy FOREIGN KEY (visit_policy_version_id) REFERENCES sales_visit_policy_version (id),
    INDEX idx_sales_visit_person_time (tenant_id, sales_profile_id, checked_in_at),
    INDEX idx_sales_visit_review_queue (tenant_id, status, submitted_at),
    INDEX idx_sales_visit_target (tenant_id, customer_id, store_id, finalized_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售拜访聚合根';

CREATE TABLE sales_visit_target_snapshot (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    target_type VARCHAR(24) NOT NULL,
    customer_id BINARY(16) NULL,
    customer_name VARCHAR(256) NULL,
    store_id BINARY(16) NULL,
    store_name VARCHAR(256) NULL,
    store_address VARCHAR(512) NULL,
    store_longitude DECIMAL(10,7) NULL,
    store_latitude DECIMAL(10,7) NULL,
    assigned_sales_profile_id BINARY(16) NULL,
    crm_source_version BIGINT UNSIGNED NULL,
    captured_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit_target_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_sales_visit_target_snapshot UNIQUE (tenant_id, visit_id),
    CONSTRAINT fk_sales_visit_target_snapshot_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拜访发生时不可变客户门店和销售归属快照';

CREATE TABLE sales_visit_checkpoint (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    checkpoint_type VARCHAR(24) NOT NULL,
    device_event_id VARCHAR(128) NOT NULL,
    client_occurred_at DATETIME(6) NULL,
    server_received_at DATETIME(6) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    accuracy_meters DECIMAL(8,2) NULL,
    distance_to_target_meters DECIMAL(10,2) NULL,
    evidence_status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit_checkpoint PRIMARY KEY (id),
    CONSTRAINT uk_sales_visit_checkpoint_event UNIQUE (tenant_id, device_event_id),
    CONSTRAINT fk_sales_visit_checkpoint_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id),
    INDEX idx_sales_visit_checkpoint_visit (tenant_id, visit_id, server_received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拜访到店签到签退位置证据';

CREATE TABLE sales_visit_evidence (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    evidence_type VARCHAR(32) NOT NULL,
    object_key VARCHAR(512) NULL,
    text_content VARCHAR(4000) NULL,
    content_hash VARCHAR(128) NULL,
    server_received_at DATETIME(6) NOT NULL,
    evidence_status VARCHAR(24) NOT NULL,
    created_by BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_visit_evidence PRIMARY KEY (id),
    CONSTRAINT fk_sales_visit_evidence_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id),
    INDEX idx_sales_visit_evidence_visit (tenant_id, visit_id, evidence_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拜访照片备注表单等业务证据';

CREATE TABLE sales_recording_session (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    verified_total_duration_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    clip_count INT UNSIGNED NOT NULL DEFAULT 0,
    maximum_observed_gap_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
    evidence_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_recording_session PRIMARY KEY (id),
    CONSTRAINT uk_sales_recording_session_visit UNIQUE (tenant_id, visit_id),
    CONSTRAINT fk_sales_recording_session_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一个拜访的主录音会话';

CREATE TABLE sales_recording_clip (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    recording_session_id BINARY(16) NOT NULL,
    clip_index INT UNSIGNED NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    object_size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    perceptual_hash VARCHAR(256) NULL,
    client_duration_ms BIGINT UNSIGNED NULL,
    verified_duration_ms BIGINT UNSIGNED NULL,
    recorded_from DATETIME(6) NULL,
    recorded_to DATETIME(6) NULL,
    upload_status VARCHAR(24) NOT NULL,
    verify_status VARCHAR(24) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    verified_at DATETIME(6) NULL,
    CONSTRAINT pk_sales_recording_clip PRIMARY KEY (id),
    CONSTRAINT uk_sales_recording_clip_index UNIQUE (tenant_id, recording_session_id, clip_index),
    CONSTRAINT uk_sales_recording_object UNIQUE (tenant_id, object_key),
    CONSTRAINT fk_sales_recording_clip_session FOREIGN KEY (recording_session_id) REFERENCES sales_recording_session (id),
    INDEX idx_sales_recording_clip_hash (tenant_id, sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='录音连续片段及COS对象元数据';

CREATE TABLE sales_ai_result_snapshot (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    recording_session_id BINARY(16) NULL,
    ai_task_id BINARY(16) NOT NULL,
    result_version INT UNSIGNED NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NULL,
    transcript_ref VARCHAR(512) NULL,
    summary_text VARCHAR(4000) NULL,
    relevance_score DECIMAL(5,4) NULL,
    duplicate_risk_score DECIMAL(5,4) NULL,
    confidence_score DECIMAL(5,4) NULL,
    recommendation VARCHAR(32) NOT NULL,
    risk_codes_json JSON NULL,
    adopted TINYINT UNSIGNED NOT NULL DEFAULT 0,
    analyzed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_ai_result_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_sales_ai_result_version UNIQUE (tenant_id, visit_id, result_version),
    CONSTRAINT fk_sales_ai_result_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id),
    CONSTRAINT fk_sales_ai_result_recording FOREIGN KEY (recording_session_id) REFERENCES sales_recording_session (id),
    CONSTRAINT ck_sales_ai_result_adopted CHECK (adopted IN (0,1)),
    INDEX idx_sales_ai_result_task (tenant_id, ai_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sales Work采用的AI分析不可变摘要，原始模型结果归AI域';

CREATE TABLE sales_visit_review (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    review_type VARCHAR(32) NOT NULL,
    review_status VARCHAR(24) NOT NULL,
    reviewer_id BINARY(16) NULL,
    decision VARCHAR(24) NULL,
    reason_code VARCHAR(64) NULL,
    review_note VARCHAR(2000) NULL,
    assigned_at DATETIME(6) NOT NULL,
    decided_at DATETIME(6) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_sales_visit_review PRIMARY KEY (id),
    CONSTRAINT fk_sales_visit_review_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id),
    INDEX idx_sales_visit_review_assignee (tenant_id, review_status, reviewer_id, assigned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主管或专职复核队列和决定';

CREATE TABLE sales_visit_adjustment (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    visit_id BINARY(16) NOT NULL,
    adjustment_type VARCHAR(32) NOT NULL,
    reason VARCHAR(1024) NOT NULL,
    requested_by BINARY(16) NOT NULL,
    requested_at DATETIME(6) NOT NULL,
    decision_status VARCHAR(24) NOT NULL,
    decided_by BINARY(16) NULL,
    decided_at DATETIME(6) NULL,
    decision_note VARCHAR(1024) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    CONSTRAINT pk_sales_visit_adjustment PRIMARY KEY (id),
    CONSTRAINT fk_sales_visit_adjustment_visit FOREIGN KEY (visit_id) REFERENCES sales_visit (id),
    INDEX idx_sales_visit_adjustment_queue (tenant_id, decision_status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='拜访申诉和纠错追加调整';

CREATE TABLE sales_idempotency_record (
    tenant_id BINARY(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    principal_id BINARY(16) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_status VARCHAR(24) NOT NULL,
    result_reference VARCHAR(128) NULL,
    response_code VARCHAR(64) NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_idempotency_record PRIMARY KEY (tenant_id, idempotency_key),
    INDEX idx_sales_idempotency_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='移动端和管理命令幂等结果';

CREATE TABLE sales_outbox_event (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BINARY(16) NOT NULL,
    aggregate_version BIGINT UNSIGNED NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INT UNSIGNED NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    publish_status VARCHAR(24) NOT NULL,
    retry_count INT UNSIGNED NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_sales_outbox_aggregate_version UNIQUE (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type),
    INDEX idx_sales_outbox_publish (publish_status, next_retry_at, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Sales Work可靠领域事件Outbox';

CREATE TABLE sales_audit_log (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    actor_id BINARY(16) NULL,
    actor_type VARCHAR(24) NOT NULL,
    action_code VARCHAR(128) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BINARY(16) NULL,
    result VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64) NULL,
    request_id VARCHAR(128) NULL,
    occurred_at DATETIME(6) NOT NULL,
    detail_json JSON NULL,
    CONSTRAINT pk_sales_audit_log PRIMARY KEY (id),
    INDEX idx_sales_audit_target (tenant_id, target_type, target_id, occurred_at),
    INDEX idx_sales_audit_actor (tenant_id, actor_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规则发布复核录音播放轨迹查看和调整审计';
