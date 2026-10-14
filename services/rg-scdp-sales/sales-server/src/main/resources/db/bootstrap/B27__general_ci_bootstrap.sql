-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__sales_work_domain.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售业务画像，引用HR员工和任职事实';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售团队';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售团队成员有效期关系';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售外勤规则稳定身份';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='版本化外勤签到、签退和定位规则';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售拜访规则稳定身份';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='版本化客户门店、现场证据、录音和AI规则';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外勤和拜访规则的租户城市团队员工适用范围';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM客户门店最小只读投影，不得由Sales Work页面修改';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM销售客户门店归属最小只读投影';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售人员单个业务日的外勤聚合根';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='H5签到签退补卡等不可变原始事件';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='签到至签退期间的位置采集会话';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售工作期间精确位置点，按租户和时间规划分区';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='页面隐藏权限关闭定位失败等证据中断';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='发送HR和BI的版本化销售工作日结候选';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='补卡纠错和申诉追加调整';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售拜访计划任务';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='销售拜访聚合根';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='拜访发生时不可变客户门店和销售归属快照';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='拜访到店签到签退位置证据';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='拜访照片备注表单等业务证据';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='一个拜访的主录音会话';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='录音连续片段及COS对象元数据';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Sales Work采用的AI分析不可变摘要，原始模型结果归AI域';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='主管或专职复核队列和决定';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='拜访申诉和纠错追加调整';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='移动端和管理命令幂等结果';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Sales Work可靠领域事件Outbox';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='规则发布复核录音播放轨迹查看和调整审计';

-- Source: V2__sales_identity_projection.sql
-- Sales Work V2：IAM用户/HR员工到销售画像的只读身份投影。
-- 本表由身份或HR事件消费者维护，不能由H5或Portal直接写入。

CREATE TABLE sales_identity_projection (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    platform_user_id BINARY(16) NOT NULL,
    employee_id BINARY(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    source_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_sales_identity_projection PRIMARY KEY (id),
    CONSTRAINT uk_sales_identity_projection_user UNIQUE (tenant_id, platform_user_id),
    CONSTRAINT uk_sales_identity_projection_employee UNIQUE (tenant_id, employee_id),
    INDEX idx_sales_identity_projection_active (
        tenant_id, platform_user_id, status, effective_from, effective_to
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='IAM平台用户到HR员工的Sales Work只读身份投影';

-- Source: V3__sales_visit_result.sql
-- Sales Work V3：拜访结果采集字段（KP 关键人称呼、联系电话、合作意向、结果备注）。
-- 只追加可空列，不改写 V1/V2 历史迁移；拜访结果属于销售事实，不做绩效计算。

ALTER TABLE sales_visit
    ADD COLUMN kp_name VARCHAR(128) NULL COMMENT '门店关键人称呼' AFTER final_reason_code,
    ADD COLUMN kp_phone VARCHAR(32) NULL COMMENT '门店关键人联系电话' AFTER kp_name,
    ADD COLUMN intention_level VARCHAR(24) NULL COMMENT '合作意向 HIGH/MEDIUM/LOW/NONE' AFTER kp_phone,
    ADD COLUMN result_note VARCHAR(1024) NULL COMMENT '拜访结果备注' AFTER intention_level,
    ADD COLUMN result_submitted_at DATETIME(6) NULL COMMENT '拜访结果最近提交时间' AFTER result_note;

-- Source: V4__sales_recording_clip_idempotency.sql
-- Sales Work V4：为飞书录音片段补充客户端幂等标识。
-- tt.uploadFile 在网络超时后可能由用户重试；同一拜访录音片段必须只登记一次。

ALTER TABLE sales_recording_clip
    ADD COLUMN client_clip_id VARCHAR(128) NULL COMMENT '客户端片段幂等标识' AFTER clip_index;

UPDATE sales_recording_clip
   SET client_clip_id = CONCAT('legacy-', LOWER(HEX(id)))
 WHERE client_clip_id IS NULL;

ALTER TABLE sales_recording_clip
    MODIFY COLUMN client_clip_id VARCHAR(128) NOT NULL COMMENT '客户端片段幂等标识',
    ADD CONSTRAINT uk_sales_recording_client_clip
        UNIQUE (tenant_id, recording_session_id, client_clip_id);

-- Source: V5__repair_enabled_recording_duration.sql
-- 修复历史上“启用录音但要求0秒”导致0/0被判完成的无效规则。
-- 10分钟是当前已确认的有效拜访硬证据下限；迁移后数据库约束阻止同类配置再次写入。
UPDATE sales_visit_policy_version
   SET minimum_recording_seconds = 600
 WHERE recording_enabled = 1
   AND minimum_recording_seconds = 0;

ALTER TABLE sales_visit_policy_version
    ADD CONSTRAINT ck_sales_visit_policy_recording_duration
    CHECK (recording_enabled = 0 OR minimum_recording_seconds > 0);

-- Source: V6__sales_recording_discard_audit.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V7__sales_visit_contact_outcome.sql
-- Sales Work V7：记录本次是否真正接触KP，支持关门、KP不在、拒绝接待等真实离店场景。
-- 不将未接触场景伪造成空白KP资料；录音不足与未接触结果进入后续复核，不阻断离店。

ALTER TABLE sales_visit
    ADD COLUMN contact_outcome VARCHAR(32) NULL COMMENT 'CONTACTED/STORE_CLOSED/KP_ABSENT/REFUSED/OTHER_NO_CONTACT'
        AFTER final_reason_code;

UPDATE sales_visit
   SET contact_outcome='CONTACTED'
 WHERE result_submitted_at IS NOT NULL
   AND contact_outcome IS NULL;

-- Source: V8__sales_visit_storefront_photo_evidence.sql
-- 门头照证据闭环：保留 V1 证据表，新增现场相机、位置、媒体与客户端幂等事实。
-- 已执行迁移不改写；历史证据允许新增字段为空，新上传门头照必须由应用层完整填写。

ALTER TABLE sales_visit_evidence
    ADD COLUMN client_evidence_id VARCHAR(128) NULL AFTER visit_id,
    ADD COLUMN evidence_role VARCHAR(32) NULL AFTER evidence_type,
    ADD COLUMN capture_source VARCHAR(32) NULL AFTER evidence_role,
    ADD COLUMN captured_at DATETIME(6) NULL AFTER capture_source,
    ADD COLUMN media_type VARCHAR(128) NULL AFTER object_key,
    ADD COLUMN object_size_bytes BIGINT UNSIGNED NULL AFTER media_type,
    ADD COLUMN longitude DECIMAL(10,7) NULL AFTER content_hash,
    ADD COLUMN latitude DECIMAL(10,7) NULL AFTER longitude,
    ADD COLUMN accuracy_meters DECIMAL(8,2) NULL AFTER latitude,
    ADD COLUMN distance_to_target_meters DECIMAL(10,2) NULL AFTER accuracy_meters,
    ADD CONSTRAINT uk_sales_visit_evidence_client
        UNIQUE (tenant_id, visit_id, client_evidence_id);

-- 产品规则已冻结：每次拜访至少要求一张现场门头照。
UPDATE sales_visit_policy_version
   SET required_photo_count = 1
 WHERE required_photo_count < 1;

ALTER TABLE sales_visit_policy_version
    ADD CONSTRAINT ck_sales_visit_policy_required_photo
        CHECK (required_photo_count >= 1);

-- Source: V9__sales_visit_plan_execution.sql
-- 主管计划到销售执行闭环：一个计划最多关联一次真实拜访，并固化可执行状态机。

ALTER TABLE sales_visit
    ADD CONSTRAINT uk_sales_visit_plan_execution UNIQUE (tenant_id, visit_plan_id);

ALTER TABLE sales_visit_plan
    ADD CONSTRAINT ck_sales_visit_plan_status
        CHECK (status IN ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED')),
    ADD INDEX idx_sales_visit_plan_management (tenant_id, planned_date, status, created_at);

-- Source: V10__sales_visit_plan_active_duplicate_guard.sql
-- 同一销售、同一天、同一门店只能存在一个待执行或进行中的计划。
-- 已完成/已取消计划的生成列为 NULL，允许后续重新安排，不删除历史计划。

ALTER TABLE sales_visit_plan
    ADD COLUMN active_store_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('PLANNED','IN_PROGRESS') THEN store_id ELSE NULL END
        ) STORED AFTER store_id,
    ADD CONSTRAINT uk_sales_visit_plan_active_store
        UNIQUE (tenant_id, sales_profile_id, planned_date, active_store_id);

-- Source: V11__sales_recording_decoded_content_hash_index.sql
-- perceptual_hash 旧字段现存储版本化的解码 PCM 精确指纹，支持同租户录音重放检测。
CREATE INDEX idx_sales_recording_clip_decoded_content_hash
    ON sales_recording_clip (tenant_id, perceptual_hash);

-- Source: V12__temporary_sales_checkin.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='临时销售拜访打卡表单、位置和三类媒体元数据';

-- Source: V13__temporary_sales_checkin_readable_location.sql
-- 临时销售打卡可读位置：保留浏览器原始 GPS 坐标，同时保存转换后的高德坐标和逆地理编码快照。
-- 既有记录保持 PENDING，可在高德 Web 服务 Key 配置后补解析；不改写 V12 历史。

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN location_address VARCHAR(512) NULL AFTER location_note,
    ADD COLUMN location_formatted_address VARCHAR(512) NULL AFTER location_address,
    ADD COLUMN location_adcode VARCHAR(16) NULL AFTER location_formatted_address,
    ADD COLUMN location_province VARCHAR(64) NULL AFTER location_adcode,
    ADD COLUMN location_city VARCHAR(64) NULL AFTER location_province,
    ADD COLUMN location_district VARCHAR(64) NULL AFTER location_city,
    ADD COLUMN location_township VARCHAR(128) NULL AFTER location_district,
    ADD COLUMN amap_longitude DECIMAL(10,6) NULL AFTER location_township,
    ADD COLUMN amap_latitude DECIMAL(10,6) NULL AFTER amap_longitude,
    ADD COLUMN geocode_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER amap_latitude,
    ADD COLUMN geocode_error_code VARCHAR(64) NULL AFTER geocode_status,
    ADD COLUMN geocoded_at DATETIME(6) NULL AFTER geocode_error_code,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_amap_coordinates
        CHECK (
            (amap_longitude IS NULL AND amap_latitude IS NULL)
            OR (
                amap_longitude IS NOT NULL
                AND amap_latitude IS NOT NULL
                AND amap_longitude BETWEEN -180 AND 180
                AND amap_latitude BETWEEN -90 AND 90
            )
        ),
    ADD INDEX idx_temp_sales_checkin_submission_city_created
        (tenant_id, city, created_at);

-- Source: V14__temporary_sales_checkin_location_media_ai.sql
-- 临时销售打卡增强：门店可读位置、媒体物理删除审计，以及录音转写/摘要状态。
-- 继续只使用 V12 创建的三张业务表；不改写既有迁移，所有新增列对旧镜像保持向后兼容。

ALTER TABLE temp_sales_checkin_store
    ADD COLUMN source_poi_id VARCHAR(128) NULL AFTER source_record_id,
    ADD COLUMN source_poi_name VARCHAR(256) NULL AFTER source_poi_id,
    ADD COLUMN source_poi_address VARCHAR(512) NULL AFTER source_poi_name,
    ADD COLUMN source_poi_longitude DECIMAL(10,6) NULL AFTER source_poi_address,
    ADD COLUMN source_poi_latitude DECIMAL(10,6) NULL AFTER source_poi_longitude,
    ADD COLUMN location_address VARCHAR(512) NULL AFTER location_note,
    ADD COLUMN location_formatted_address VARCHAR(512) NULL AFTER location_address,
    ADD COLUMN location_adcode VARCHAR(16) NULL AFTER location_formatted_address,
    ADD COLUMN amap_longitude DECIMAL(10,6) NULL AFTER location_adcode,
    ADD COLUMN amap_latitude DECIMAL(10,6) NULL AFTER amap_longitude,
    ADD COLUMN geocode_status VARCHAR(24) NOT NULL DEFAULT 'PENDING' AFTER amap_latitude,
    ADD COLUMN geocode_error_code VARCHAR(64) NULL AFTER geocode_status,
    ADD COLUMN geocoded_at DATETIME(6) NULL AFTER geocode_error_code,
    ADD CONSTRAINT ck_temp_sales_checkin_store_source_poi_coordinates
        CHECK (
            (source_poi_longitude IS NULL AND source_poi_latitude IS NULL)
            OR (
                source_poi_longitude IS NOT NULL
                AND source_poi_latitude IS NOT NULL
                AND source_poi_longitude BETWEEN -180 AND 180
                AND source_poi_latitude BETWEEN -90 AND 90
            )
        ),
    ADD CONSTRAINT ck_temp_sales_checkin_store_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED'));

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN privacy_notice_version VARCHAR(32) NULL AFTER privacy_accepted,

    ADD COLUMN storefront_photo_deleted_at DATETIME(6) NULL AFTER storefront_photo_original_filename,
    ADD COLUMN storefront_photo_deleted_by VARCHAR(128) NULL AFTER storefront_photo_deleted_at,
    ADD COLUMN storefront_photo_deletion_reason VARCHAR(512) NULL AFTER storefront_photo_deleted_by,

    ADD COLUMN wechat_screenshot_deleted_at DATETIME(6) NULL AFTER wechat_screenshot_original_filename,
    ADD COLUMN wechat_screenshot_deleted_by VARCHAR(128) NULL AFTER wechat_screenshot_deleted_at,
    ADD COLUMN wechat_screenshot_deletion_reason VARCHAR(512) NULL AFTER wechat_screenshot_deleted_by,

    ADD COLUMN audio_deleted_at DATETIME(6) NULL AFTER audio_original_filename,
    ADD COLUMN audio_deleted_by VARCHAR(128) NULL AFTER audio_deleted_at,
    ADD COLUMN audio_deletion_reason VARCHAR(512) NULL AFTER audio_deleted_by,

    ADD COLUMN transcription_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED' AFTER audio_deletion_reason,
    ADD COLUMN asr_task_id VARCHAR(64) NULL AFTER transcription_status,
    ADD COLUMN asr_request_id VARCHAR(128) NULL AFTER asr_task_id,
    ADD COLUMN transcript MEDIUMTEXT NULL AFTER asr_request_id,
    ADD COLUMN transcription_error_code VARCHAR(128) NULL AFTER transcript,
    ADD COLUMN transcription_attempts INT UNSIGNED NOT NULL DEFAULT 0 AFTER transcription_error_code,
    ADD COLUMN transcription_updated_at DATETIME(6) NULL AFTER transcription_attempts,

    ADD COLUMN summary_status VARCHAR(24) NOT NULL DEFAULT 'NOT_REQUESTED' AFTER transcription_updated_at,
    ADD COLUMN summary_text TEXT NULL AFTER summary_status,
    ADD COLUMN summary_error_code VARCHAR(128) NULL AFTER summary_text,
    ADD COLUMN summary_model VARCHAR(128) NULL AFTER summary_error_code,
    ADD COLUMN summary_updated_at DATETIME(6) NULL AFTER summary_model,

    ADD CONSTRAINT ck_temp_sales_checkin_submission_transcription_status
        CHECK (transcription_status IN (
            'NOT_REQUESTED', 'PENDING', 'SUBMITTING', 'PROCESSING', 'SUCCEEDED',
            'FAILED', 'UNSUPPORTED', 'DELETED'
        )),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_summary_status
        CHECK (summary_status IN (
            'NOT_REQUESTED', 'PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'DELETED'
        )),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_media_deletion_actor
        CHECK (
            (storefront_photo_deleted_at IS NULL OR storefront_photo_deleted_by IS NOT NULL)
            AND (wechat_screenshot_deleted_at IS NULL OR wechat_screenshot_deleted_by IS NOT NULL)
            AND (audio_deleted_at IS NULL OR audio_deleted_by IS NOT NULL)
        ),
    ADD INDEX idx_temp_sales_checkin_submission_transcription
        (tenant_id, transcription_status, transcription_updated_at),
    ADD INDEX idx_temp_sales_checkin_submission_summary
        (tenant_id, summary_status, summary_updated_at);

-- Source: V15__temporary_sales_checkin_visit_history.sql
-- 临时销售打卡数据治理：清理飞书导入时误带入的日期前缀，并为“本临时系统内”拜访次序提供索引。
-- 清理语句只匹配 YYYY-MM-DD- / YYYY-MM-DD<空格> 前缀，重复执行不会再改写已清理名称。

UPDATE temp_sales_checkin_salesperson
   SET name = TRIM(REGEXP_REPLACE(
           name,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       )),
       updated_at = UTC_TIMESTAMP(6)
 WHERE name REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+'
   AND CHAR_LENGTH(TRIM(REGEXP_REPLACE(
           name,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       ))) > 0;

UPDATE temp_sales_checkin_submission
   SET salesperson_name_snapshot = TRIM(REGEXP_REPLACE(
           salesperson_name_snapshot,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       )),
       updated_at = UTC_TIMESTAMP(6)
 WHERE salesperson_name_snapshot REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+'
   AND CHAR_LENGTH(TRIM(REGEXP_REPLACE(
           salesperson_name_snapshot,
           '^[0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]-]+',
           ''
       ))) > 0;

ALTER TABLE temp_sales_checkin_store
    ADD CONSTRAINT uk_temp_sales_checkin_store_source_poi
        UNIQUE (tenant_id, source_poi_id);

CREATE INDEX idx_temp_sales_checkin_submission_visit_rank
    ON temp_sales_checkin_submission (
        tenant_id, status, salesperson_id, store_id, submitted_at, id
    );

-- Source: V16__temporary_sales_checkin_identity_risk.sql
-- 临时销售打卡身份与风险事实：个人码仅保存强 KDF 摘要，IP/设备仅用于异常提示。
-- 不改写 V12-V15，不新增业务表；旧数据明确标记为 LEGACY_ANONYMOUS。

ALTER TABLE temp_sales_checkin_salesperson
    ADD COLUMN checkin_secret_hash VARCHAR(255) NULL AFTER sort_order,
    ADD COLUMN credential_version INT UNSIGNED NOT NULL DEFAULT 1 AFTER checkin_secret_hash,
    ADD COLUMN credential_updated_at DATETIME(6) NULL AFTER credential_version,
    ADD COLUMN credential_updated_by VARCHAR(128) NULL AFTER credential_updated_at,
    ADD COLUMN credential_update_reason VARCHAR(512) NULL AFTER credential_updated_by,
    ADD CONSTRAINT ck_temp_sales_checkin_salesperson_credential_version
        CHECK (credential_version >= 1);

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN identity_method VARCHAR(32) NOT NULL DEFAULT 'LEGACY_ANONYMOUS'
        AFTER privacy_notice_version,
    ADD COLUMN identity_verified_at DATETIME(6) NULL AFTER identity_method,
    ADD COLUMN credential_version INT UNSIGNED NULL AFTER identity_verified_at,
    ADD COLUMN device_token_hash CHAR(64) NULL AFTER credential_version,
    ADD COLUMN draft_ip_hash CHAR(64) NULL AFTER device_token_hash,
    ADD COLUMN draft_ip_network_hash CHAR(64) NULL AFTER draft_ip_hash,
    ADD COLUMN draft_ip_masked VARCHAR(64) NULL AFTER draft_ip_network_hash,
    ADD COLUMN submitted_ip_hash CHAR(64) NULL AFTER draft_ip_masked,
    ADD COLUMN submitted_ip_network_hash CHAR(64) NULL AFTER submitted_ip_hash,
    ADD COLUMN submitted_ip_masked VARCHAR(64) NULL AFTER submitted_ip_network_hash,
    ADD COLUMN user_agent_hash CHAR(64) NULL AFTER submitted_ip_masked,
    ADD COLUMN user_agent_summary VARCHAR(192) NULL AFTER user_agent_hash,
    ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT 'NONE' AFTER user_agent_summary,
    ADD COLUMN risk_flags_json JSON NULL AFTER risk_level,
    ADD COLUMN risk_evaluated_at DATETIME(6) NULL AFTER risk_flags_json,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_identity_method
        CHECK (identity_method IN ('LEGACY_ANONYMOUS', 'PERSONAL_CODE')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_identity_version
        CHECK (credential_version IS NULL OR credential_version >= 1),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_risk_level
        CHECK (risk_level IN ('NONE', 'LOW', 'MEDIUM', 'HIGH')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_identity_hashes
        CHECK (
            (device_token_hash IS NULL OR device_token_hash REGEXP '^[0-9a-f]{64}$')
            AND (draft_ip_hash IS NULL OR draft_ip_hash REGEXP '^[0-9a-f]{64}$')
            AND (draft_ip_network_hash IS NULL OR draft_ip_network_hash REGEXP '^[0-9a-f]{64}$')
            AND (submitted_ip_hash IS NULL OR submitted_ip_hash REGEXP '^[0-9a-f]{64}$')
            AND (submitted_ip_network_hash IS NULL OR submitted_ip_network_hash REGEXP '^[0-9a-f]{64}$')
            AND (user_agent_hash IS NULL OR user_agent_hash REGEXP '^[0-9a-f]{64}$')
        ),
    ADD INDEX idx_temp_sales_checkin_submission_device_risk
        (tenant_id, device_token_hash, status, submitted_at),
    ADD INDEX idx_temp_sales_checkin_submission_sales_risk
        (tenant_id, salesperson_id, status, submitted_at),
    ADD INDEX idx_temp_sales_checkin_submission_ip_risk
        (tenant_id, submitted_ip_network_hash, status, submitted_at);

-- Source: V17__temporary_sales_checkin_admin_authentication.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
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

-- Source: V18__temporary_sales_checkin_deletion_governance.sql
-- 临时销售打卡治理：为少量测试数据的受控物理删除保留独立任务审计。
-- 业务记录最终会被硬删除，因此任务表必须独立保留请求人、原因和逐条结果。

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN deletion_state VARCHAR(24) NOT NULL DEFAULT 'NONE' AFTER summary_updated_at,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_deletion_state
        CHECK (deletion_state IN ('NONE', 'PENDING', 'FAILED')),
    ADD INDEX idx_temp_sales_checkin_submission_deletion
        (tenant_id, deletion_state, updated_at);

CREATE TABLE temp_sales_checkin_deletion_job (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    request_id BINARY(16) NOT NULL,
    requested_by VARCHAR(128) NOT NULL,
    requested_scope_city VARCHAR(64) NULL,
    reason VARCHAR(512) NOT NULL,
    requested_count INT UNSIGNED NOT NULL,
    deleted_count INT UNSIGNED NOT NULL DEFAULT 0,
    failed_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    result_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT pk_temp_sales_checkin_deletion_job PRIMARY KEY (id),
    CONSTRAINT uk_temp_sales_checkin_deletion_job_request
        UNIQUE (tenant_id, request_id),
    CONSTRAINT ck_temp_sales_checkin_deletion_job_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'PARTIAL_FAILED', 'FAILED')),
    CONSTRAINT ck_temp_sales_checkin_deletion_job_counts
        CHECK (
            requested_count > 0
            AND deleted_count + failed_count <= requested_count
        ),
    INDEX idx_temp_sales_checkin_deletion_job_created
        (tenant_id, created_at),
    INDEX idx_temp_sales_checkin_deletion_job_status
        (tenant_id, status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='临时打卡物理删除任务与不可变请求审计';

-- Source: V19__temporary_sales_checkin_multi_audio.sql
-- 临时销售打卡多段录音：继续把媒体元数据保存在提交表中，不新增媒体业务表。
-- 旧 audio_* 列保留为首个活动录音的兼容投影；历史单录音以 submission id 作为稳定段 ID 回填。

ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN audio_segments_json JSON NULL AFTER audio_deletion_reason,
    ADD COLUMN audio_active_segment_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER audio_segments_json,
    ADD COLUMN audio_active_size_bytes BIGINT UNSIGNED NOT NULL DEFAULT 0 AFTER audio_active_segment_count;

UPDATE temp_sales_checkin_submission
   SET audio_segments_json = CASE
           WHEN audio_object_key IS NULL THEN JSON_ARRAY()
           ELSE JSON_ARRAY(JSON_OBJECT(
               'segmentId', LOWER(CONCAT(
                   SUBSTR(HEX(id), 1, 8), '-', SUBSTR(HEX(id), 9, 4), '-',
                   SUBSTR(HEX(id), 13, 4), '-', SUBSTR(HEX(id), 17, 4), '-', SUBSTR(HEX(id), 21, 12)
               )),
               'objectKey', audio_object_key,
               'contentType', audio_content_type,
               'sizeBytes', audio_size_bytes,
               'sha256', audio_sha256,
               'originalFilename', audio_original_filename,
               'uploadedAt', DATE_FORMAT(COALESCE(submitted_at, updated_at, created_at),
                                         '%Y-%m-%dT%H:%i:%s.%fZ'),
               'deletedAt', IF(audio_deleted_at IS NULL, NULL,
                   DATE_FORMAT(audio_deleted_at, '%Y-%m-%dT%H:%i:%s.%fZ')),
               'deletedBy', audio_deleted_by,
               'deletionReason', audio_deletion_reason
           ))
       END,
       audio_active_segment_count = IF(
           audio_object_key IS NOT NULL AND audio_deleted_at IS NULL, 1, 0),
       audio_active_size_bytes = IF(
           audio_object_key IS NOT NULL AND audio_deleted_at IS NULL,
           COALESCE(audio_size_bytes, 0), 0);

ALTER TABLE temp_sales_checkin_submission
    MODIFY COLUMN audio_segments_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_audio_segments_json
        CHECK (JSON_TYPE(audio_segments_json) = 'ARRAY'),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_audio_segment_count
        CHECK (audio_active_segment_count <= JSON_LENGTH(audio_segments_json)),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_audio_segment_bytes
        CHECK (
            (audio_active_segment_count = 0 AND audio_active_size_bytes = 0)
            OR (audio_active_segment_count > 0 AND audio_active_size_bytes > 0)
        );

-- Source: V20__temporary_sales_checkin_unverified_location.sql
-- 临时销售打卡定位例外留档：GPS 失败时允许提交现场记录，但必须与已验证定位明确区分。
-- 旧记录无法追溯当时是否经过现行签名定位流程，统一标记 LEGACY；新严格路径显式写 VERIFIED。
-- V12 允许历史门店目录没有坐标，因此门店 LEGACY 保留“全空或全齐”；历史提交则始终有完整坐标。

ALTER TABLE temp_sales_checkin_store
    DROP CHECK ck_temp_sales_checkin_store_geocode_status,
    ADD COLUMN location_verification_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
        AFTER location_note,
    ADD COLUMN location_failure_reason VARCHAR(64) NULL
        AFTER location_verification_status,
    ADD COLUMN location_attempt_id BINARY(16) NULL
        AFTER location_failure_reason,
    ADD CONSTRAINT ck_temp_sales_checkin_store_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_temp_sales_checkin_store_location_verification
        CHECK (
            (
                location_verification_status = 'LEGACY'
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'VERIFIED'
                AND longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'UNVERIFIED'
                AND location_failure_reason IS NOT NULL
                AND location_failure_reason IN (
                    'PERMISSION_DENIED', 'POSITION_UNAVAILABLE', 'TIMEOUT', 'UNSUPPORTED',
                    'INSECURE_CONTEXT', 'INVALID_POSITION', 'TIMESTAMP_UNUSABLE',
                    'ACCURACY_INSUFFICIENT', 'RESOLVE_FAILED', 'USER_CONTINUED_AFTER_WAIT'
                )
                AND location_attempt_id IS NOT NULL
            )
        ),
    ADD INDEX idx_temp_sales_checkin_store_location_verification
        (tenant_id, location_verification_status, updated_at);

ALTER TABLE temp_sales_checkin_submission
    DROP CHECK ck_temp_sales_checkin_submission_coordinates,
    DROP CHECK ck_temp_sales_checkin_submission_geocode_status,
    MODIFY COLUMN longitude DECIMAL(10,7) NULL,
    MODIFY COLUMN latitude DECIMAL(10,7) NULL,
    MODIFY COLUMN accuracy_meters DECIMAL(10,2) NULL,
    MODIFY COLUMN location_captured_at DATETIME(6) NULL,
    ADD COLUMN location_verification_status VARCHAR(24) NOT NULL DEFAULT 'LEGACY'
        AFTER location_note,
    ADD COLUMN location_failure_reason VARCHAR(64) NULL
        AFTER location_verification_status,
    ADD COLUMN location_attempt_id BINARY(16) NULL
        AFTER location_failure_reason,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_geocode_status
        CHECK (geocode_status IN ('PENDING', 'RESOLVED', 'KEY_MISSING', 'FAILED', 'SKIPPED')),
    ADD CONSTRAINT ck_temp_sales_checkin_submission_coordinates
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
    ADD CONSTRAINT ck_temp_sales_checkin_submission_location_verification
        CHECK (
            (
                location_verification_status = 'LEGACY'
                AND longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'VERIFIED'
                AND longitude IS NOT NULL
                AND latitude IS NOT NULL
                AND accuracy_meters IS NOT NULL
                AND location_captured_at IS NOT NULL
                AND location_failure_reason IS NULL
                AND location_attempt_id IS NULL
            )
            OR (
                location_verification_status = 'UNVERIFIED'
                AND location_failure_reason IS NOT NULL
                AND location_failure_reason IN (
                    'PERMISSION_DENIED', 'POSITION_UNAVAILABLE', 'TIMEOUT', 'UNSUPPORTED',
                    'INSECURE_CONTEXT', 'INVALID_POSITION', 'TIMESTAMP_UNUSABLE',
                    'ACCURACY_INSUFFICIENT', 'RESOLVE_FAILED', 'USER_CONTINUED_AFTER_WAIT'
                )
                AND location_attempt_id IS NOT NULL
            )
        ),
    ADD INDEX idx_temp_sales_checkin_submission_location_verification
        (tenant_id, location_verification_status, submitted_at);

-- Source: V21__temporary_checkin_evidence_review.sql
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

-- Source: V22__temporary_checkin_media_derivatives.sql
-- 派生文件只改善后台读取，不替代原件；生成状态和时长跨请求复用。
CREATE TABLE temp_sales_checkin_media_derivative (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    submission_id BINARY(16) NOT NULL,
    media_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    source_object_key VARCHAR(1024) NOT NULL,
    source_sha256 VARCHAR(64) NOT NULL,
    source_content_type VARCHAR(128) NULL,
    source_filename VARCHAR(256) NULL,
    source_size_bytes BIGINT NOT NULL,
    lease_token BINARY(16) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    duration_ms BIGINT NULL,
    thumbnail_bytes MEDIUMBLOB NULL,
    derived_object_key VARCHAR(1024) NULL,
    derived_size_bytes BIGINT NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_temp_media_derivative (tenant_id,submission_id,media_id,source_sha256),
    KEY idx_temp_media_derivative_jobs (tenant_id,status,updated_at),
    CONSTRAINT fk_temp_derivative_submission FOREIGN KEY (tenant_id,submission_id)
        REFERENCES temp_sales_checkin_submission (tenant_id,id) ON DELETE CASCADE,
    CONSTRAINT ck_temp_derivative_status CHECK (status IN ('PENDING','PROCESSING','READY','FAILED')),
    CONSTRAINT ck_temp_derivative_kind CHECK (kind IN ('IMAGE','AUDIO'))
);

-- Source: V23__temporary_checkin_multiple_photos.sql
-- 每张现场照片独立标识；原 storefront_photo_* 列继续投影首张活动照片，兼容已发布客户端。
-- 旧文件没有可核验的拍摄来源或独立上传时间，迁移保持 NULL，不补造事实。
CREATE TABLE temp_sales_checkin_photo (
    tenant_id BINARY(16) NOT NULL,
    submission_id BINARY(16) NOT NULL,
    photo_id BINARY(16) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes BIGINT UNSIGNED NOT NULL,
    sha256 CHAR(64) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    uploaded_at DATETIME(6) NULL,
    capture_source VARCHAR(24) NULL,
    deleted_at DATETIME(6) NULL,
    deleted_by VARCHAR(128) NULL,
    deletion_reason VARCHAR(512) NULL,
    PRIMARY KEY (tenant_id, submission_id, photo_id),
    CONSTRAINT fk_temp_photo_submission FOREIGN KEY (tenant_id, submission_id)
        REFERENCES temp_sales_checkin_submission (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_temp_photo_size CHECK (size_bytes > 0),
    CONSTRAINT ck_temp_photo_source CHECK (capture_source IS NULL OR capture_source IN ('CAMERA','FILE_IMPORT'))
);

INSERT INTO temp_sales_checkin_photo
    (tenant_id, submission_id, photo_id, object_key, content_type, size_bytes, sha256,
     original_filename, deleted_at, deleted_by, deletion_reason)
SELECT tenant_id, id, id, storefront_photo_object_key, storefront_photo_content_type,
       storefront_photo_size_bytes, storefront_photo_sha256, storefront_photo_original_filename,
       storefront_photo_deleted_at, storefront_photo_deleted_by, storefront_photo_deletion_reason
  FROM temp_sales_checkin_submission
 WHERE storefront_photo_object_key IS NOT NULL;

ALTER TABLE temp_sales_checkin_submission
    DROP CHECK ck_temp_sales_checkin_submission_privacy,
    ADD CONSTRAINT ck_temp_sales_checkin_submission_privacy CHECK (privacy_accepted IN (0, 1));

-- Source: V24__temporary_checkin_risk_links.sql
-- 关联证据始终来自现存已提交记录；编号登记不是媒体副本，不参与公开上传或提交事务。
CREATE TABLE temp_sales_checkin_risk_device (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    hash_version VARCHAR(16) NOT NULL DEFAULT 'V1',
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_temp_risk_device (tenant_id,token_hash,hash_version)
);
CREATE TABLE temp_sales_checkin_risk_audio (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_temp_risk_audio (tenant_id,sha256,size_bytes),
    CONSTRAINT ck_temp_risk_audio_size CHECK (size_bytes>0)
);

-- JSON 清单为唯一事实源；旧首段投影只在清单为空时兜底，避免重复计数。
CREATE VIEW temp_sales_checkin_risk_audio_source AS
SELECT s.tenant_id,s.id AS submission_id,LOWER(j.segment_id) AS segment_id,
       LOWER(j.sha256) AS sha256,j.size_bytes,j.original_filename,j.content_type,
       j.uploaded_at_text,j.client_duration_ms,j.capture_source
FROM temp_sales_checkin_submission s
JOIN JSON_TABLE(s.audio_segments_json,'$[*]' COLUMNS (
    segment_id VARCHAR(64) PATH '$.segmentId' NULL ON EMPTY,
    sha256 VARCHAR(64) PATH '$.sha256' NULL ON EMPTY,
    size_bytes BIGINT PATH '$.sizeBytes' NULL ON EMPTY,
    object_key VARCHAR(1024) PATH '$.objectKey' NULL ON EMPTY,
    deleted_at VARCHAR(64) PATH '$.deletedAt' NULL ON EMPTY,
    original_filename VARCHAR(512) PATH '$.originalFilename' NULL ON EMPTY,
    content_type VARCHAR(128) PATH '$.contentType' NULL ON EMPTY,
    uploaded_at_text VARCHAR(64) PATH '$.uploadedAt' NULL ON EMPTY,
    client_duration_ms BIGINT PATH '$.clientDurationMs' NULL ON EMPTY,
    capture_source VARCHAR(32) PATH '$.captureSource' NULL ON EMPTY)) j
WHERE s.status='SUBMITTED' AND s.deletion_state='NONE' AND j.deleted_at IS NULL
  AND j.object_key IS NOT NULL AND j.object_key<>'' AND j.size_bytes>0
  AND j.sha256 REGEXP '^[0-9a-fA-F]{64}$'
  AND j.segment_id REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
UNION ALL
SELECT s.tenant_id,s.id,LOWER(BIN_TO_UUID(s.id)),s.audio_sha256,s.audio_size_bytes,
       s.audio_original_filename,s.audio_content_type,NULL,NULL,NULL
FROM temp_sales_checkin_submission s
WHERE s.status='SUBMITTED' AND s.deletion_state='NONE'
  AND (s.audio_segments_json IS NULL OR JSON_LENGTH(s.audio_segments_json)=0)
  AND s.audio_deleted_at IS NULL AND s.audio_object_key IS NOT NULL AND s.audio_object_key<>''
  AND s.audio_size_bytes>0 AND s.audio_sha256 REGEXP '^[0-9a-f]{64}$';

INSERT INTO temp_sales_checkin_risk_device(tenant_id,token_hash,created_at)
SELECT tenant_id,device_token_hash,UTC_TIMESTAMP(6) FROM temp_sales_checkin_submission
WHERE status='SUBMITTED' AND deletion_state='NONE' AND device_token_hash IS NOT NULL
GROUP BY tenant_id,device_token_hash ORDER BY tenant_id,device_token_hash;
INSERT INTO temp_sales_checkin_risk_audio(tenant_id,sha256,size_bytes,created_at)
SELECT tenant_id,sha256,size_bytes,UTC_TIMESTAMP(6) FROM temp_sales_checkin_risk_audio_source
GROUP BY tenant_id,sha256,size_bytes ORDER BY tenant_id,sha256,size_bytes;

CREATE TABLE temp_sales_checkin_risk_assignment (
    id BINARY(16) NOT NULL PRIMARY KEY,tenant_id BINARY(16) NOT NULL,device_id BIGINT UNSIGNED NOT NULL,
    client_event_id BINARY(16) NOT NULL,scope_key VARCHAR(80) NOT NULL,
    assignment_type VARCHAR(24) NOT NULL,salesperson_id BINARY(16) NULL,salesperson_name VARCHAR(128) NULL,
    valid_from DATE NULL,valid_to DATE NULL,note VARCHAR(2000) NOT NULL,actor VARCHAR(128) NOT NULL,
    assigned_at DATETIME(6) NOT NULL,request_hash CHAR(64) NOT NULL,
    UNIQUE KEY uk_temp_risk_assignment_event(tenant_id,client_event_id),
    KEY idx_temp_risk_assignment_device(tenant_id,device_id,assigned_at),
    CONSTRAINT ck_temp_risk_assignment_type CHECK(assignment_type IN ('PERSONAL','SHARED','UNCONFIRMED')),
    CONSTRAINT ck_temp_risk_assignment_range CHECK(valid_from IS NULL OR valid_to IS NULL OR valid_from<=valid_to)
);
CREATE TABLE temp_sales_checkin_risk_review (
    id BINARY(16) NOT NULL PRIMARY KEY,tenant_id BINARY(16) NOT NULL,
    group_kind VARCHAR(16) NOT NULL,group_id BIGINT UNSIGNED NOT NULL,scope_key VARCHAR(80) NOT NULL,
    client_event_id BINARY(16) NOT NULL,evidence_version CHAR(64) NOT NULL,rules_version VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,note VARCHAR(2000) NOT NULL,actor VARCHAR(128) NOT NULL,
    reviewed_at DATETIME(6) NOT NULL,member_count BIGINT NOT NULL,request_hash CHAR(64) NOT NULL,
    UNIQUE KEY uk_temp_risk_review_event(tenant_id,client_event_id),
    KEY idx_temp_risk_review_group(tenant_id,group_kind,group_id,scope_key,reviewed_at),
    CONSTRAINT ck_temp_risk_review_kind CHECK(group_kind IN ('DEVICE','AUDIO')),
    CONSTRAINT ck_temp_risk_review_status CHECK(status IN ('EXPLAINED','FLAGGED','INCONCLUSIVE'))
);
CREATE TABLE temp_sales_checkin_risk_review_member (
    tenant_id BINARY(16) NOT NULL,review_id BINARY(16) NOT NULL,member_key VARCHAR(128) NOT NULL,
    PRIMARY KEY(tenant_id,review_id,member_key),
    CONSTRAINT fk_temp_risk_review_member FOREIGN KEY(review_id)
        REFERENCES temp_sales_checkin_risk_review(id) ON DELETE CASCADE
);

-- Source: V25__temporary_checkin_address_provenance.sql
-- 地址是对已保存设备坐标的派生说明；不改原始位置、采样时刻、质量和风险。
ALTER TABLE temp_sales_checkin_submission
    ADD COLUMN address_source VARCHAR(64) NULL,
    ADD COLUMN address_resolved_at DATETIME(6) NULL,
    ADD COLUMN address_conversion_version VARCHAR(64) NULL,
    ADD COLUMN address_coordinate_hash CHAR(64) NULL;

-- 旧 geocoded_at 可能是草稿落库时间，不能补造精确解析时间。
UPDATE temp_sales_checkin_submission
   SET address_source='LEGACY_SNAPSHOT', address_conversion_version='UNRECORDED'
 WHERE NULLIF(TRIM(location_address),'') IS NOT NULL;

CREATE TABLE temp_sales_checkin_address_cache (
    tenant_id BINARY(16) NOT NULL,
    coordinate_hash CHAR(64) NOT NULL,
    provider_version VARCHAR(96) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_json JSON NULL,
    resolved_at DATETIME(6) NULL,
    retry_at DATETIME(6) NULL,
    claim_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    requested_by VARCHAR(128) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id,coordinate_hash,provider_version),
    CONSTRAINT ck_temp_address_cache_status CHECK (status IN ('PROCESSING','RESOLVED','FAILED'))
);

-- 每租户最多一个按需解析在途；配额跨重启保留。只对显式管理员操作分配额度。
CREATE TABLE temp_sales_checkin_address_guard (
    tenant_id BINARY(16) NOT NULL PRIMARY KEY,
    budget_date DATE NOT NULL,
    calls_used INT UNSIGNED NOT NULL DEFAULT 0,
    lease_token BINARY(16) NULL,
    lease_until DATETIME(6) NULL
);

-- Source: V26__temporary_checkin_identity_events.sql
-- 只追加新版真实成功验证事件，不从历史拜访回填登录轨迹，不保存 Cookie、会话或个人码。
CREATE TABLE temp_sales_checkin_identity_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    device_token_hash CHAR(64) NOT NULL,
    salesperson_id BINARY(16) NOT NULL,
    salesperson_name_snapshot VARCHAR(128) NOT NULL,
    identity_city VARCHAR(64) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_temp_identity_event(tenant_id,event_id),
    KEY idx_temp_identity_event_device(tenant_id,device_token_hash,occurred_at,id),
    KEY idx_temp_identity_event_city(tenant_id,device_token_hash,identity_city,occurred_at,id),
    CONSTRAINT ck_temp_identity_event_type CHECK(event_type='PERSONAL_CODE_VERIFIED'),
    CONSTRAINT ck_temp_identity_event_device CHECK(device_token_hash REGEXP '^[0-9a-f]{64}$')
);

-- Source: V27__temporary_checkin_business_links.sql
-- 仅存业务关联，不建立 IAM 用户、角色或登录身份。名称只用于首次核对，刷新按固定编码关联。
CREATE TABLE temp_sales_checkin_employee_link (
    tenant_id BINARY(16) NOT NULL,
    salesperson_id BINARY(16) NOT NULL,
    employee_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    match_rule VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL,
    batch_id CHAR(36) NOT NULL,
    linked_by VARCHAR(128) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, salesperson_id),
    UNIQUE KEY uk_temp_employee_link_target (tenant_id, employee_code),
    CONSTRAINT fk_temp_employee_link_salesperson FOREIGN KEY (tenant_id, salesperson_id)
        REFERENCES temp_sales_checkin_salesperson (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Sales 人员到 HR 员工编码的已核对业务关联';

CREATE TABLE temp_sales_checkin_customer_link (
    tenant_id BINARY(16) NOT NULL,
    store_id BINARY(16) NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_code VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
    match_rule VARCHAR(64) NOT NULL,
    evidence_json JSON NOT NULL,
    batch_id CHAR(36) NOT NULL,
    linked_by VARCHAR(128) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, store_id),
    UNIQUE KEY uk_temp_customer_link_target (tenant_id, customer_id),
    UNIQUE KEY uk_temp_customer_link_code (tenant_id, customer_code),
    CONSTRAINT fk_temp_customer_link_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES temp_sales_checkin_store (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='Sales 门店到 CRM 客户主键及编码的已核对业务关联';

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
