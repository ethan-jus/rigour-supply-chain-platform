-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__crm_dhb_customer_master_data.sql
-- CRM V1：订货宝客户、地址、客户字典和外部员工主数据。
-- 本迁移仅创建 CRM 自有 Schema；Integration 凭据和 Raw Landing 不进入本库。

CREATE TABLE crm_customer_type (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    type_code          VARCHAR(128)  NOT NULL COMMENT 'CRM 内部稳定编码',
    type_name          VARCHAR(160)  NOT NULL,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL COMMENT 'CRM 本地扩展，不代替来源完整字段快照',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    deleted_by         BINARY(16)    NULL,
    delete_reason      VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_type_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_customer_type_code (tenant_id, type_code),
    KEY idx_crm_customer_type_name (tenant_id, type_name),
    KEY idx_crm_customer_type_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户类型/等级规范主档';

CREATE TABLE crm_customer_area (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    area_code          VARCHAR(128)  NOT NULL COMMENT 'CRM 内部稳定编码',
    area_name          VARCHAR(160)  NOT NULL COMMENT '客户经营归属地区，不等于省市区地址',
    city_id            BINARY(16)    NULL COMMENT '跨 City 服务稳定 ID，仅保存引用',
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    deleted_by         BINARY(16)    NULL,
    delete_reason      VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_area_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_customer_area_code (tenant_id, area_code),
    KEY idx_crm_customer_area_name (tenant_id, area_name),
    KEY idx_crm_customer_area_city (tenant_id, city_id),
    KEY idx_crm_customer_area_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户经营归属地区主档';

CREATE TABLE crm_party (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7，客户/商家的统一经营主体 ID',
    tenant_id          BINARY(16)    NOT NULL,
    party_code         VARCHAR(128)  NOT NULL COMMENT 'CRM 内部稳定业务编号',
    display_name       VARCHAR(240)  NOT NULL,
    party_kind         VARCHAR(24)   NOT NULL DEFAULT 'ORGANIZATION' COMMENT 'ORGANIZATION/INDIVIDUAL',
    internal_status    VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL COMMENT 'CRM 本地自研扩展字段',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    deleted_by         BINARY(16)    NULL,
    delete_reason      VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_party_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_party_code (tenant_id, party_code),
    KEY idx_crm_party_name (tenant_id, display_name),
    KEY idx_crm_party_status (tenant_id, internal_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户/商家统一经营主体';

CREATE TABLE crm_party_role (
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NOT NULL,
    role_code          VARCHAR(24)   NOT NULL COMMENT 'CUSTOMER/MERCHANT',
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    effective_from     DATETIME(6)   NOT NULL,
    effective_to       DATETIME(6)   NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (tenant_id, party_id, role_code),
    CONSTRAINT fk_crm_party_role_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_party_role_status (tenant_id, role_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='经营主体业务角色';

CREATE TABLE crm_customer_profile (
    party_id                    BINARY(16)    NOT NULL,
    tenant_id                   BINARY(16)    NOT NULL,
    customer_type_id            BINARY(16)    NULL,
    customer_area_id            BINARY(16)    NULL,
    login_account               VARCHAR(191)  NULL COMMENT '订货宝 clientAccount；一期按用户要求不脱敏',
    customer_type_name_snapshot VARCHAR(160)  NULL COMMENT '来源类型名称快照，防止字典延迟造成展示丢失',
    customer_area_name_snapshot VARCHAR(160)  NULL COMMENT '来源归属地区名称快照',
    city_text                   VARCHAR(500)  NULL COMMENT '订货宝 clientCity 原文',
    inviter_name                VARCHAR(160)  NULL,
    remark                      VARCHAR(2000) NULL,
    version                     BIGINT        NOT NULL DEFAULT 0,
    created_at                  DATETIME(6)   NOT NULL,
    updated_at                  DATETIME(6)   NOT NULL,
    PRIMARY KEY (party_id),
    UNIQUE KEY uk_crm_customer_profile_tenant_id (tenant_id, party_id),
    CONSTRAINT fk_crm_customer_profile_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_customer_profile_type FOREIGN KEY (tenant_id, customer_type_id)
        REFERENCES crm_customer_type (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_customer_profile_area FOREIGN KEY (tenant_id, customer_area_id)
        REFERENCES crm_customer_area (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_customer_profile_account (tenant_id, login_account),
    KEY idx_crm_customer_profile_type (tenant_id, customer_type_id),
    KEY idx_crm_customer_profile_area (tenant_id, customer_area_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户角色扩展资料';

CREATE TABLE crm_customer_policy (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NOT NULL,
    settlement_mode    VARCHAR(24)   NULL COMMENT 'PREPAID/CASH/POSTPAID；来源 clearingForm',
    credit_limit       DECIMAL(20,6) NULL COMMENT '未来自研信用额度；不得映射订货宝 credit 应收余额',
    currency           VARCHAR(8)    NOT NULL DEFAULT 'CNY',
    payment_term_days  INT UNSIGNED  NULL,
    billing_cycle      VARCHAR(32)   NULL,
    grace_days         INT UNSIGNED  NULL,
    over_limit_action  VARCHAR(32)   NULL,
    invoice_profile_json JSON        NULL COMMENT '未来自研开票政策；当前查询接口无数据',
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'IMPORTED',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_policy_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_customer_policy_party (tenant_id, party_id),
    CONSTRAINT fk_crm_customer_policy_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_customer_policy_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户当前信用与结算政策';

CREATE TABLE crm_store (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NOT NULL COMMENT '所属客户/商家主体',
    store_code         VARCHAR(128)  NOT NULL,
    store_name         VARCHAR(240)  NOT NULL,
    store_type         VARCHAR(64)   NULL,
    store_level        VARCHAR(64)   NULL,
    internal_status    VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'INTERNAL_ONLY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'MANUAL',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    deleted_by         BINARY(16)    NULL,
    delete_reason      VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_store_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_store_code (tenant_id, store_code),
    CONSTRAINT fk_crm_store_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_store_party (tenant_id, party_id, internal_status),
    KEY idx_crm_store_name (tenant_id, store_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户/商家门店主档；一期不把订货宝客户强行等同为门店';

CREATE TABLE crm_contact (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NOT NULL,
    store_id           BINARY(16)    NULL,
    contact_type       VARCHAR(32)   NOT NULL DEFAULT 'BUSINESS' COMMENT 'PRIMARY/BUSINESS/SHIPPING/BILLING',
    contact_name       VARCHAR(160)  NULL,
    phone              VARCHAR(128)  NULL COMMENT '一期按用户要求不脱敏',
    email              VARCHAR(320)  NULL COMMENT '一期按用户要求不脱敏',
    is_primary         TINYINT(1)    NOT NULL DEFAULT 0,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    deleted_by         BINARY(16)    NULL,
    delete_reason      VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_contact_tenant_id (tenant_id, id),
    CONSTRAINT fk_crm_contact_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_contact_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES crm_store (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_contact_party (tenant_id, party_id, is_primary),
    KEY idx_crm_contact_store (tenant_id, store_id),
    KEY idx_crm_contact_phone (tenant_id, phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户和门店联系人';

CREATE TABLE crm_address (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NOT NULL,
    store_id           BINARY(16)    NULL,
    contact_id         BINARY(16)    NULL,
    address_type       VARCHAR(32)   NOT NULL COMMENT 'CONTACT/SHIPPING/BILLING/REGISTERED',
    consignee          VARCHAR(240)  NULL COMMENT '收货单位',
    region_text        VARCHAR(500)  NULL COMMENT '省市区连续文本',
    area_name          VARCHAR(500)  NULL COMMENT '订货宝 areaName 原文',
    address_detail     VARCHAR(1000) NULL,
    full_address       VARCHAR(1500) NULL,
    is_default         TINYINT(1)    NOT NULL DEFAULT 0,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    ownership_state    VARCHAR(32)   NOT NULL DEFAULT 'EXTERNAL_PRIMARY',
    record_origin      VARCHAR(24)   NOT NULL DEFAULT 'IMPORTED',
    attributes_json    JSON          NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    deleted_at         DATETIME(6)   NULL,
    deleted_by         BINARY(16)    NULL,
    delete_reason      VARCHAR(500)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_address_tenant_id (tenant_id, id),
    CONSTRAINT fk_crm_address_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_address_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES crm_store (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_address_contact FOREIGN KEY (tenant_id, contact_id)
        REFERENCES crm_contact (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_address_party (tenant_id, party_id, address_type, is_default),
    KEY idx_crm_address_store (tenant_id, store_id, address_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户和门店地址';

CREATE TABLE crm_external_staff (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7；外部员工引用，不是 HR 员工主档',
    tenant_id          BINARY(16)    NOT NULL,
    connector_id       BINARY(16)    NOT NULL COMMENT 'Integration 连接器跨服务引用',
    source_system      VARCHAR(32)   NOT NULL DEFAULT 'DINGHUOBAO',
    source_staff_id    VARCHAR(128)  NOT NULL,
    source_account_id  VARCHAR(128)  NULL,
    account_name       VARCHAR(191)  NULL,
    staff_type         VARCHAR(32)   NULL COMMENT 'salesman/boss/indoorwork/driver',
    staff_name         VARCHAR(160)  NULL,
    title              VARCHAR(160)  NULL,
    branch_name        VARCHAR(240)  NULL,
    account_mobile     VARCHAR(128)  NULL,
    mobile             VARCHAR(128)  NULL,
    email              VARCHAR(320)  NULL,
    qq                 VARCHAR(64)   NULL,
    role_name          VARCHAR(500)  NULL,
    invite_code        VARCHAR(128)  NULL,
    remark             VARCHAR(2000) NULL,
    source_status      VARCHAR(24)   NULL COMMENT '文档列表响应未声明状态字段，实际缺失时保持 NULL',
    source_created_at  DATETIME(6)   NULL,
    source_updated_at  DATETIME(6)   NULL,
    sales_profile_id   BINARY(16)    NULL COMMENT '跨 Sales Work/HR 的人工或规则映射，不建外键',
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_external_staff_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_external_staff_source
        (tenant_id, connector_id, source_system, source_staff_id),
    UNIQUE KEY uk_crm_external_staff_account
        (tenant_id, connector_id, source_system, source_account_id),
    KEY idx_crm_external_staff_name (tenant_id, staff_name),
    KEY idx_crm_external_staff_profile (tenant_id, sales_profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订货宝员工目录在 CRM 的外部引用投影';

CREATE TABLE crm_sales_assignment (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NULL,
    store_id           BINARY(16)    NULL,
    assignment_type    VARCHAR(24)   NOT NULL DEFAULT 'PRIMARY' COMMENT 'PRIMARY/SECONDARY/SERVICE',
    assignee_type      VARCHAR(32)   NOT NULL COMMENT 'EXTERNAL_STAFF/SALES_PROFILE/SALES_TEAM',
    external_staff_id  BINARY(16)    NULL,
    sales_profile_id   BINARY(16)    NULL COMMENT '跨服务引用',
    sales_team_id      BINARY(16)    NULL COMMENT '跨服务引用',
    city_id            BINARY(16)    NULL COMMENT '跨 City 服务引用',
    source             VARCHAR(24)   NOT NULL COMMENT 'DHB_IMPORT/MANUAL/RULE',
    source_name_snapshot VARCHAR(160) NULL,
    effective_from     DATETIME(6)   NOT NULL,
    effective_to       DATETIME(6)   NULL,
    status             VARCHAR(24)   NOT NULL DEFAULT 'ACTIVE',
    reason             VARCHAR(1000) NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    created_by         BINARY(16)    NULL,
    updated_by         BINARY(16)    NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    active_primary_party_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' AND assignment_type = 'PRIMARY' THEN party_id ELSE NULL END
        ) STORED,
    active_primary_store_id BINARY(16)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' AND assignment_type = 'PRIMARY' THEN store_id ELSE NULL END
        ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_sales_assignment_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_sales_assignment_active_party (tenant_id, active_primary_party_id),
    UNIQUE KEY uk_crm_sales_assignment_active_store (tenant_id, active_primary_store_id),
    CONSTRAINT ck_crm_sales_assignment_subject CHECK (
        (party_id IS NOT NULL AND store_id IS NULL) OR (party_id IS NULL AND store_id IS NOT NULL)
    ),
    CONSTRAINT fk_crm_sales_assignment_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES crm_party (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_sales_assignment_store FOREIGN KEY (tenant_id, store_id)
        REFERENCES crm_store (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_crm_sales_assignment_external_staff FOREIGN KEY (tenant_id, external_staff_id)
        REFERENCES crm_external_staff (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_sales_assignment_assignee (tenant_id, assignee_type, sales_profile_id, status),
    KEY idx_crm_sales_assignment_external (tenant_id, external_staff_id, status),
    KEY idx_crm_sales_assignment_effective (tenant_id, status, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='客户/门店销售归属有效期历史';

CREATE TABLE crm_sync_run (
    id                   BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id            BINARY(16)    NOT NULL,
    connector_id         BINARY(16)    NOT NULL,
    source_system        VARCHAR(32)   NOT NULL,
    object_type          VARCHAR(32)   NOT NULL COMMENT 'CUSTOMER/ADDRESS/CUSTOMER_TYPE/CUSTOMER_AREA/STAFF',
    trigger_type         VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',
    sync_mode            VARCHAR(16)   NOT NULL COMMENT 'FULL/INCREMENTAL/REPAIR',
    status               VARCHAR(16)   NOT NULL DEFAULT 'RUNNING',
    window_from          DATETIME(6)   NULL,
    window_to            DATETIME(6)   NULL,
    page_size            INT UNSIGNED  NOT NULL DEFAULT 500,
    max_pages            INT UNSIGNED  NOT NULL DEFAULT 100,
    provider_total_count BIGINT         NULL,
    fetched_count        BIGINT         NOT NULL DEFAULT 0,
    created_count        BIGINT         NOT NULL DEFAULT 0,
    changed_count        BIGINT         NOT NULL DEFAULT 0,
    repaired_count       BIGINT         NOT NULL DEFAULT 0,
    duplicate_count      BIGINT         NOT NULL DEFAULT 0,
    absent_count         BIGINT         NOT NULL DEFAULT 0,
    rejected_count       BIGINT         NOT NULL DEFAULT 0,
    error_code           VARCHAR(64)    NULL,
    error_message        VARCHAR(2000)  NULL,
    started_at           DATETIME(6)    NOT NULL,
    finished_at          DATETIME(6)    NULL,
    created_by           BINARY(16)     NULL,
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_sync_run_tenant_id (tenant_id, id),
    KEY idx_crm_sync_run_scope (tenant_id, connector_id, object_type, started_at),
    KEY idx_crm_sync_run_status (tenant_id, status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM 外部主数据同步批次';

CREATE TABLE crm_sync_checkpoint (
    id                  BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id           BINARY(16)    NOT NULL,
    connector_id        BINARY(16)    NOT NULL,
    source_system       VARCHAR(32)   NOT NULL,
    object_type         VARCHAR(32)   NOT NULL,
    cursor_type         VARCHAR(24)   NOT NULL COMMENT 'UPDATED_AT/OFFSET/PAGE/FULL_ONLY',
    cursor_value        VARCHAR(1024) NULL,
    source_updated_at   DATETIME(6)   NULL,
    last_success_run_id BINARY(16)    NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_sync_checkpoint_scope
        (tenant_id, connector_id, source_system, object_type),
    KEY idx_crm_sync_checkpoint_run (tenant_id, last_success_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM 同步成功游标';

CREATE TABLE crm_sync_lock (
    id                 BINARY(16)   NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)   NOT NULL,
    connector_id       BINARY(16)   NOT NULL,
    object_type        VARCHAR(32)  NOT NULL,
    run_id             BINARY(16)   NOT NULL,
    lock_token         VARCHAR(128) NOT NULL,
    acquired_at        DATETIME(6)  NOT NULL,
    expires_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_sync_lock_scope (tenant_id, connector_id, object_type),
    KEY idx_crm_sync_lock_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM 同步租约锁';

CREATE TABLE crm_source_binding (
    id                  BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id           BINARY(16)    NOT NULL,
    connector_id        BINARY(16)    NOT NULL COMMENT 'Integration 连接器跨服务引用',
    source_system       VARCHAR(32)   NOT NULL,
    source_object_type  VARCHAR(32)   NOT NULL,
    source_object_id    VARCHAR(191)  NOT NULL,
    source_code         VARCHAR(191)  NULL,
    source_name         VARCHAR(240)  NULL,
    source_status       VARCHAR(32)   NULL,
    target_type         VARCHAR(32)   NULL COMMENT 'PARTY/TYPE/AREA/CONTACT/ADDRESS/EXTERNAL_STAFF',
    target_id           BINARY(16)    NULL COMMENT '同 CRM Schema 多态目标；未解析来源记录保持 NULL',
    binding_status      VARCHAR(24)   NOT NULL DEFAULT 'RESOLVED' COMMENT 'UNRESOLVED/RESOLVED/REJECTED',
    resolution_error_code VARCHAR(64) NULL,
    resolution_error_message VARCHAR(1000) NULL,
    source_created_at   DATETIME(6)   NULL,
    source_updated_at   DATETIME(6)   NULL,
    source_fields_json  JSON          NOT NULL COMMENT '单条业务对象完整字段，不含 sKey/密码/Token',
    source_payload_hash CHAR(64)      NOT NULL COMMENT '规范化 source_fields_json 的 SHA-256',
    source_presence     VARCHAR(24)   NOT NULL DEFAULT 'PRESENT' COMMENT 'PRESENT/ABSENT_CANDIDATE/ABSENT/DELETED',
    absent_confirm_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '连续成功全量未见次数，避免误判来源删除',
    source_absent_at    DATETIME(6)   NULL,
    last_seen_run_id    BINARY(16)    NULL,
    last_sync_run_id    BINARY(16)    NULL,
    synced_at           DATETIME(6)   NOT NULL,
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_source_binding_tenant_id (tenant_id, id),
    UNIQUE KEY uk_crm_source_binding_source
        (tenant_id, connector_id, source_system, source_object_type, source_object_id),
    UNIQUE KEY uk_crm_source_binding_target
        (tenant_id, connector_id, source_system, target_type, target_id),
    KEY idx_crm_source_binding_code
        (tenant_id, connector_id, source_system, source_object_type, source_code),
    KEY idx_crm_source_binding_target (tenant_id, target_type, target_id),
    KEY idx_crm_source_binding_resolution
        (tenant_id, connector_id, source_object_type, binding_status, updated_at),
    KEY idx_crm_source_binding_presence
        (tenant_id, connector_id, source_object_type, source_presence, source_absent_at),
    KEY idx_crm_source_binding_seen (tenant_id, connector_id, source_object_type, last_seen_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM 规范主档与外部来源身份及完整字段快照';

CREATE TABLE crm_source_identity_alias (
    id                  BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id           BINARY(16)    NOT NULL,
    binding_id          BINARY(16)    NOT NULL,
    connector_id        BINARY(16)    NOT NULL,
    source_system       VARCHAR(32)   NOT NULL,
    source_object_type  VARCHAR(32)   NOT NULL,
    alias_type          VARCHAR(32)   NOT NULL COMMENT 'GUID/NUM/ID/ACCOUNT/ERP_ID',
    alias_value         VARCHAR(191)  NOT NULL,
    is_primary          TINYINT(1)    NOT NULL DEFAULT 0,
    first_seen_at       DATETIME(6)   NOT NULL,
    last_seen_at        DATETIME(6)   NOT NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_source_identity_alias_binding
        (tenant_id, binding_id, alias_type, alias_value),
    UNIQUE KEY uk_crm_source_identity_alias_scope
        (tenant_id, connector_id, source_system, source_object_type, alias_type, alias_value),
    CONSTRAINT fk_crm_source_identity_alias_binding FOREIGN KEY (tenant_id, binding_id)
        REFERENCES crm_source_binding (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    KEY idx_crm_source_identity_alias_lookup
        (tenant_id, connector_id, source_object_type, alias_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='外部对象多身份别名，处理 clientGUID/clientNO/addressId/addressGuid 并存';

CREATE TABLE crm_outbox_event (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    aggregate_type     VARCHAR(64)   NOT NULL,
    aggregate_id       BINARY(16)    NOT NULL,
    aggregate_version  BIGINT        NOT NULL,
    event_type         VARCHAR(128)  NOT NULL,
    event_version      INT UNSIGNED  NOT NULL,
    payload_json       JSON          NOT NULL,
    occurred_at        DATETIME(6)   NOT NULL,
    publish_status     VARCHAR(24)   NOT NULL DEFAULT 'PENDING',
    retry_count        INT UNSIGNED  NOT NULL DEFAULT 0,
    next_retry_at      DATETIME(6)   NULL,
    published_at       DATETIME(6)   NULL,
    last_error         VARCHAR(2000) NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_outbox_aggregate_version
        (tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type),
    KEY idx_crm_outbox_publish (publish_status, next_retry_at, occurred_at),
    KEY idx_crm_outbox_tenant (tenant_id, publish_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM 领域事件可靠 Outbox';

-- Source: V2__crm_sales_assignment_source_staff.sql
-- CRM V2：保存订货宝业务员来源 ID，允许员工目录延迟同步时先保留归属事实。
ALTER TABLE crm_sales_assignment
    ADD COLUMN source_staff_id VARCHAR(128) NULL
        COMMENT '订货宝来源业务员ID；可在外部员工目录解析前先落库'
        AFTER external_staff_id;

CREATE INDEX idx_crm_sales_assignment_source_staff
    ON crm_sales_assignment (tenant_id, source_staff_id, status);

-- Source: V3__crm_customer_area_hierarchy.sql
-- CRM V3：保存订货宝归属地区的来源父级关系。
-- getArea 未返回 parentID 时保持 NULL，不按地区名称猜测父级。
ALTER TABLE crm_customer_area
    ADD COLUMN parent_area_code VARCHAR(128) NULL
        COMMENT '订货宝 parentID，对应上级地区的来源 AreaID'
        AFTER area_name;

CREATE INDEX idx_crm_customer_area_parent
    ON crm_customer_area (tenant_id, parent_area_code, status);

-- Source: V4__crm_repair_invalid_area_parent_codes.sql
-- CRM V4：清理历史同步把字段名误写入地区父级编码的问题。
-- 订货宝未提供真实 parentID 时，父级关系应为空，而不是 literal "parentId"。
UPDATE crm_customer_area
   SET parent_area_code = NULL,
       updated_at = CURRENT_TIMESTAMP,
       version = version + 1
 WHERE LOWER(TRIM(parent_area_code)) IN ('parentid', 'parent_id', 'parent-id', 'null', 'undefined');

-- Source: V5__crm_sync_run_scheduler_audit.sql
ALTER TABLE crm_sync_run
    ADD COLUMN source_task_id BINARY(16) NULL
        COMMENT 'Integration同步任务ID；手动任务为空' AFTER trigger_type,
    ADD KEY idx_crm_sync_run_source_task (tenant_id, source_task_id, started_at),
    ADD CONSTRAINT chk_crm_sync_run_skipped_terminal CHECK (
        status <> 'SKIPPED'
        OR (source_task_id IS NOT NULL AND finished_at IS NOT NULL
            AND error_code IS NOT NULL AND error_message IS NOT NULL)
    );

-- Source: V6__crm_internal_customer_baseline.sql
-- CRM 自研业务基线：客户、商家、门店统一为客户管理。
-- 本表只服务我们的客户管理流程，不保存订货宝来源字段；外部数据通过 Integration 映射到本表。

CREATE TABLE crm_customer (
    id                    BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id             VARCHAR(64)    NOT NULL COMMENT '租户ID',
    customer_code         VARCHAR(50)    NOT NULL COMMENT '客户编号，由CRM编码规则生成',
    customer_name         VARCHAR(200)   NOT NULL COMMENT '客户名称，即业务门店名称',
    contact_name          VARCHAR(100)   NULL COMMENT '联系人',
    contact_phone         VARCHAR(50)    NULL COMMENT '联系电话',
    region_code           VARCHAR(64)    NULL COMMENT '客户归属地区，关联 REGION 字典项',
    owner_sales_user_id   VARCHAR(64)    NULL COMMENT '归属销售用户ID，跨IAM/销售组织引用',
    owner_sales_name      VARCHAR(100)   NULL COMMENT '归属销售名称快照',
    settlement_type_code  VARCHAR(64)    NULL COMMENT '客户结算类型，关联 CUSTOMER_SETTLEMENT_TYPE 字典项',
    address               VARCHAR(1000)  NULL COMMENT '客户地址',
    status_code           VARCHAR(64)    NOT NULL DEFAULT 'ACTIVE' COMMENT '客户状态，关联 CUSTOMER_STATUS 字典项',
    remark                VARCHAR(1000)  NULL COMMENT '备注',
    revision              INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by            VARCHAR(50)    NULL COMMENT '创建人',
    created_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by            VARCHAR(50)    NULL COMMENT '更新人',
    updated_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted               INT            NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_crm_customer_code (tenant_id, customer_code),
    KEY idx_crm_customer_name (tenant_id, customer_name),
    KEY idx_crm_customer_region (tenant_id, region_code),
    KEY idx_crm_customer_owner (tenant_id, owner_sales_user_id),
    KEY idx_crm_customer_status (tenant_id, status_code, updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='CRM客户主表';

-- Source: V7__crm_sales_assignment_iam_staff_code.sql
-- CRM V7：客户归属人员改为引用 IAM 员工业务编码。
--
-- 业务口径：
-- 1. CRM 不再维护员工主档，订货宝员工先同步到 IAM 人员中心。
-- 2. CRM 客户归属只保存 iam_staff_code 与名称快照；source_staff_id 仅用于来源追溯。
-- 3. 旧 external_staff_id 暂留作兼容字段，待所有引用审计完成后再删除旧表和外键。

ALTER TABLE crm_sales_assignment
    ADD COLUMN iam_staff_code VARCHAR(50) NULL COMMENT 'IAM员工编码，跨服务关联使用我方业务编码'
        AFTER source_staff_id,
    ADD COLUMN iam_staff_name_snapshot VARCHAR(128) NULL COMMENT 'IAM员工姓名快照'
        AFTER iam_staff_code;

CREATE INDEX idx_crm_sales_assignment_iam_staff
    ON crm_sales_assignment (tenant_id, iam_staff_code, status);

ALTER TABLE crm_sales_assignment
    MODIFY COLUMN assignee_type VARCHAR(32) NOT NULL
        COMMENT '归属对象类型：IAM_STAFF员工中心人员，SOURCE_STAFF来源人员待解析，SALES_TEAM销售团队；EXTERNAL_STAFF仅旧数据兼容';

-- Source: V8__crm_customer_party_link.sql
-- CRM 自研客户与 CRM 往来主体建立内部关联。
-- 订货宝来源编号只保留在 crm_source_binding/source_identity_alias；crm_customer.customer_code 始终使用我方编码规则。

ALTER TABLE crm_customer
    ADD COLUMN party_id BINARY(16) NULL COMMENT '客户对应CRM往来主体ID；用于来源同步后关联CRM自研客户主档' AFTER customer_code,
    ADD UNIQUE KEY uk_crm_customer_party (tenant_id, party_id),
    ADD KEY idx_crm_customer_party (party_id);

-- Source: V9__drop_legacy_crm_external_staff.sql
-- CRM V9：删除旧订货宝外部员工投影。
--
-- 新方案口径：
-- 1. 员工、岗位、角色、组织统一归 IAM 员工中心管理。
-- 2. CRM 客户归属只保存 iam_staff_code、iam_staff_name_snapshot 和 source_staff_id 追溯字段。
-- 3. 订货宝 staff_id/accounts_id 等来源字段进入 IAM iam_external_staff_binding，不再进入 CRM 员工表。

SET @fk_exists = (
    SELECT COUNT(*)
      FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'crm_sales_assignment'
       AND constraint_name = 'fk_crm_sales_assignment_external_staff'
       AND constraint_type = 'FOREIGN KEY'
);
SET @sql = IF(@fk_exists > 0,
    'ALTER TABLE crm_sales_assignment DROP FOREIGN KEY fk_crm_sales_assignment_external_staff',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
    SELECT COUNT(*)
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'crm_sales_assignment'
       AND index_name = 'idx_crm_sales_assignment_external'
);
SET @sql = IF(@idx_exists > 0,
    'ALTER TABLE crm_sales_assignment DROP INDEX idx_crm_sales_assignment_external',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @column_exists = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'crm_sales_assignment'
       AND column_name = 'external_staff_id'
);
SET @sql = IF(@column_exists > 0,
    'ALTER TABLE crm_sales_assignment DROP COLUMN external_staff_id',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE crm_sales_assignment
    MODIFY COLUMN assignee_type VARCHAR(32) NOT NULL
        COMMENT '归属对象类型：IAM_STAFF员工中心人员，SOURCE_STAFF来源人员待解析，SALES_TEAM销售团队';

-- crm_external_staff 旧表物理删除由 docs/LEGACY_DHB_TABLE_CLEANUP.sql
-- 在确认备份和切流后使用 DBA 账号清理。日常 Flyway 迁移账号不授予 DROP 权限。
SELECT 1 AS legacy_crm_external_staff_cleanup_deferred;

-- Source: V10__crm_customer_owner_staff_code.sql
-- CRM V10：自研客户归属销售人员改为 IAM 员工业务编码。
--
-- 业务口径：
-- 1. owner_staff_code 是 CRM、Order、IAM 关联归属销售人员的主字段。
-- 2. owner_staff_name_snapshot 用于列表和单据展示人员名称，避免跨服务实时查询拖慢页面。
-- 3. owner_sales_user_id/owner_sales_name 仅保留旧接口和旧数据兼容，不再作为新同步关联依据。

ALTER TABLE crm_customer
    ADD COLUMN owner_staff_code VARCHAR(50) NULL COMMENT '归属销售人员员工编码，来自IAM员工中心'
        AFTER owner_sales_name,
    ADD COLUMN owner_staff_name_snapshot VARCHAR(100) NULL COMMENT '归属销售人员姓名快照'
        AFTER owner_staff_code;

CREATE INDEX idx_crm_customer_owner_staff
    ON crm_customer (tenant_id, owner_staff_code);

ALTER TABLE crm_customer
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_staff_code',
    MODIFY COLUMN owner_sales_name VARCHAR(100) NULL
        COMMENT '归属销售名称快照，旧接口兼容字段；新流程优先使用owner_staff_name_snapshot';

-- Source: V11__crm_customer_type_code.sql
-- CRM V11：自研客户主表补客户类型编码。
--
-- 业务口径：
-- 1. customer_type_code 保存我们自己的客户类型编码，关联 crm_customer_type.type_code。
-- 2. 订货宝客户类型仅作为来源同步映射，不直接成为客户主表的外部字段。
-- 3. 列表、订单等跨服务引用只传业务编码，展示名称由 CRM 主数据或前端缓存转换。

ALTER TABLE crm_customer
    ADD COLUMN customer_type_code VARCHAR(64) NULL COMMENT '客户类型编码，关联CRM客户类型主数据type_code'
        AFTER contact_phone;

CREATE INDEX idx_crm_customer_type
    ON crm_customer (tenant_id, customer_type_code);

-- Source: V12__crm_unified_audit_columns.sql
-- CRM V12：统一仍在使用的 CRM 业务/同步表本地审计字段命名和值类型。
-- source_created_at/source_updated_at 是订货宝来源证据字段，保留原名。

ALTER TABLE crm_customer_type RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_type RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_type RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_type MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_customer_type MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_customer_type ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_customer_type SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_customer_type DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_customer_area RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_area RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_area RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_area MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_customer_area MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_customer_area ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_customer_area SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_customer_area DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_party RENAME COLUMN version TO revision;
ALTER TABLE crm_party RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_party RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_party MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_party MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_party ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_party SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_party DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_party_role RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_party_role RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_party_role ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER effective_to;
ALTER TABLE crm_party_role ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_party_role ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_party_role ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_customer_profile RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_profile RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_profile RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_profile ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_customer_profile ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_customer_profile ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_customer_policy RENAME COLUMN version TO revision;
ALTER TABLE crm_customer_policy RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_customer_policy RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_customer_policy MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_customer_policy MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_customer_policy ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_store RENAME COLUMN version TO revision;
ALTER TABLE crm_store RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_store RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_store MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_store MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_store ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_store SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_store DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_contact RENAME COLUMN version TO revision;
ALTER TABLE crm_contact RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_contact RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_contact MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_contact MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_contact ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_contact SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_contact DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_address RENAME COLUMN version TO revision;
ALTER TABLE crm_address RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_address RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_address MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_address MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_address ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;
UPDATE crm_address SET deleted = 1 WHERE deleted_at IS NOT NULL;
ALTER TABLE crm_address DROP COLUMN deleted_at, DROP COLUMN deleted_by, DROP COLUMN delete_reason;

ALTER TABLE crm_sales_assignment RENAME COLUMN version TO revision;
ALTER TABLE crm_sales_assignment RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_sales_assignment RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_sales_assignment MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_sales_assignment MODIFY COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人';
ALTER TABLE crm_sales_assignment ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_sync_run RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_sync_run RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_sync_run MODIFY COLUMN created_by VARCHAR(50) NULL COMMENT '创建人';
ALTER TABLE crm_sync_run ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER finished_at;
ALTER TABLE crm_sync_run ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_sync_run ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_sync_checkpoint RENAME COLUMN version TO revision;
ALTER TABLE crm_sync_checkpoint RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_sync_checkpoint RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_sync_checkpoint ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_sync_checkpoint ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_sync_checkpoint ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_sync_lock ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER expires_at;
ALTER TABLE crm_sync_lock ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_sync_lock ADD COLUMN created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间' AFTER created_by;
ALTER TABLE crm_sync_lock ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_sync_lock ADD COLUMN updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间' AFTER updated_by;
ALTER TABLE crm_sync_lock ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_source_binding RENAME COLUMN version TO revision;
ALTER TABLE crm_source_binding RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_source_binding RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_source_binding ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_source_binding ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_source_binding ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

ALTER TABLE crm_source_identity_alias RENAME COLUMN created_at TO created_time;
ALTER TABLE crm_source_identity_alias RENAME COLUMN updated_at TO updated_time;
ALTER TABLE crm_source_identity_alias ADD COLUMN revision INT NOT NULL DEFAULT 1 COMMENT '乐观锁版本' AFTER last_seen_at;
ALTER TABLE crm_source_identity_alias ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人' AFTER revision;
ALTER TABLE crm_source_identity_alias ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '更新人' AFTER created_time;
ALTER TABLE crm_source_identity_alias ADD COLUMN deleted INT NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除' AFTER updated_time;

-- Source: V13__crm_employee_reference_fields.sql
-- CRM V13：客户归属人员字段从 IAM 员工语义改为 HR 员工语义。
--
-- 背景：人员主档归 HR 管理，IAM 只负责账号、角色和权限。
-- 本迁移仅重命名 CRM 本地引用字段，不删除订货宝来源人员 ID，避免影响订货宝手动同步追溯。

ALTER TABLE crm_customer
    DROP INDEX idx_crm_customer_owner_staff;

ALTER TABLE crm_customer
    RENAME COLUMN owner_staff_code TO owner_employee_code,
    RENAME COLUMN owner_staff_name_snapshot TO owner_employee_name_snapshot;

CREATE INDEX idx_crm_customer_owner_employee
    ON crm_customer (tenant_id, owner_employee_code);

ALTER TABLE crm_customer
    MODIFY COLUMN owner_sales_user_id VARCHAR(64) NULL
        COMMENT '归属销售用户ID，旧接口兼容字段；新流程优先使用owner_employee_code',
    MODIFY COLUMN owner_sales_name VARCHAR(100) NULL
        COMMENT '归属销售名称快照，旧接口兼容字段；新流程优先使用owner_employee_name_snapshot',
    MODIFY COLUMN owner_employee_code VARCHAR(50) NULL
        COMMENT '归属销售员工编码，来自HR员工主档',
    MODIFY COLUMN owner_employee_name_snapshot VARCHAR(100) NULL
        COMMENT '归属销售员工姓名快照';

ALTER TABLE crm_sales_assignment
    DROP INDEX idx_crm_sales_assignment_iam_staff;

ALTER TABLE crm_sales_assignment
    RENAME COLUMN iam_staff_code TO employee_code,
    RENAME COLUMN iam_staff_name_snapshot TO employee_name_snapshot;

CREATE INDEX idx_crm_sales_assignment_employee
    ON crm_sales_assignment (tenant_id, employee_code, status);

UPDATE crm_sales_assignment
   SET assignee_type = 'EMPLOYEE'
 WHERE assignee_type = 'IAM_STAFF';

ALTER TABLE crm_sales_assignment
    MODIFY COLUMN employee_code VARCHAR(50) NULL
        COMMENT '员工编码，跨服务关联HR员工主档',
    MODIFY COLUMN employee_name_snapshot VARCHAR(128) NULL
        COMMENT '员工姓名快照',
    MODIFY COLUMN assignee_type VARCHAR(32) NOT NULL
        COMMENT '归属对象类型：EMPLOYEE HR员工，SOURCE_STAFF来源人员待解析，SALES_TEAM销售团队；EXTERNAL_STAFF仅旧数据兼容';

-- Source: V14__crm_feishu_customer_source_fields.sql
-- CRM 客户/门店承接飞书导入字段。
-- 飞书原始全量仍保留在 Integration raw row；这里补业务查询、筛选和来源幂等所需字段。

ALTER TABLE crm_customer
    MODIFY COLUMN region_code VARCHAR(64) NULL COMMENT '客户归属区域/城市编码，关联CRM客户区域主数据',
    ADD COLUMN region_name VARCHAR(80) NULL COMMENT '客户业务区域名称，飞书区域原文' AFTER region_code,
    ADD COLUMN city_name VARCHAR(80) NULL COMMENT '客户城市名称，飞书城市原文' AFTER region_name,
    ADD COLUMN customer_source_name VARCHAR(120) NULL COMMENT '客户来源名称，飞书商家来源原文' AFTER city_name,
    ADD COLUMN business_category_name VARCHAR(120) NULL COMMENT '客户/门店业务类目或属性原文' AFTER customer_source_name,
    ADD COLUMN source_system_code VARCHAR(32) NULL COMMENT '来源系统编码，如FEISHU' AFTER remark,
    ADD COLUMN source_tenant_key VARCHAR(128) NULL COMMENT '来源租户/表标识' AFTER source_system_code,
    ADD COLUMN source_customer_id VARCHAR(128) NULL COMMENT '来源客户或门店ID' AFTER source_tenant_key,
    ADD COLUMN source_document_no VARCHAR(128) NULL COMMENT '来源单号/来源业务编码' AFTER source_customer_id,
    ADD COLUMN source_created_at DATETIME(6) NULL COMMENT '来源创建时间' AFTER source_document_no,
    ADD COLUMN source_updated_at DATETIME(6) NULL COMMENT '来源更新时间' AFTER source_created_at,
    ADD COLUMN source_payload_hash CHAR(64) NULL COMMENT '来源字段SHA-256摘要' AFTER source_updated_at,
    ADD COLUMN source_payload_json JSON NULL COMMENT '来源原始字段快照' AFTER source_payload_hash,
    ADD KEY idx_crm_customer_area_city (tenant_id, region_name, city_name, deleted),
    ADD KEY idx_crm_customer_source_doc (tenant_id, source_system_code, source_document_no),
    ADD UNIQUE KEY uk_crm_customer_source (tenant_id, source_system_code, source_tenant_key, source_customer_id);

-- Source: V15__crm_feishu_import_system_creator.sql
-- 旧版飞书投影曾把服务 principal UUID 写入创建人；现行服务调用已统一写 SYSTEM。
-- UUID 来自 nameUUIDFromBytes("rigour-integration-feishu-import-service")，不是租户或人员编号。
-- 仅修正飞书来源且创建人为该服务的记录；保留人工创建人、创建时间和后续更新审计。
-- crm_customer.updated_time 带 ON UPDATE，必须显式原值赋回，避免本次修正篡改更新时间。

UPDATE crm_customer
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE source_system_code = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_party
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_area
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_type
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_contact
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_address
   SET created_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_party_role r
   SET r.created_by = 'SYSTEM', r.updated_time = r.updated_time
 WHERE r.created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = r.tenant_id AND p.id = r.party_id AND p.record_origin = 'FEISHU');

UPDATE crm_customer_profile c
   SET c.created_by = 'SYSTEM', c.updated_time = c.updated_time
 WHERE c.created_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = c.tenant_id AND p.id = c.party_id AND p.record_origin = 'FEISHU');

-- Source: V16__crm_feishu_import_system_updater.sql
-- 旧飞书导入服务 principal 和客户销售编码修复任务均属于系统操作。
-- 精确匹配已知系统身份，并限定飞书来源，保留后续人工修改的更新人。
-- V15 仅修正创建人，本迁移补齐历史更新人。显式保留 ON UPDATE 时间戳。

UPDATE crm_customer
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE source_system_code = 'FEISHU'
   AND updated_by IN ('f34f98c1-7be2-3bc2-b275-4f8ae8914bab', 'codex-bi-owner-code-repair');

UPDATE crm_party
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_area
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_customer_type
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_contact
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_address
   SET updated_by = 'SYSTEM', updated_time = updated_time
 WHERE record_origin = 'FEISHU'
   AND updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab';

UPDATE crm_party_role r
   SET r.updated_by = 'SYSTEM', r.updated_time = r.updated_time
 WHERE r.updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = r.tenant_id AND p.id = r.party_id AND p.record_origin = 'FEISHU');

UPDATE crm_customer_profile c
   SET c.updated_by = 'SYSTEM', c.updated_time = c.updated_time
 WHERE c.updated_by = 'f34f98c1-7be2-3bc2-b275-4f8ae8914bab'
   AND EXISTS (SELECT 1 FROM crm_party p
                WHERE p.tenant_id = c.tenant_id AND p.id = c.party_id AND p.record_origin = 'FEISHU');

-- Source: V17__crm_customer_responsibility_history.sql
-- 客户当前主责是新订单归属的来源；本地接管后外部同步不得静默覆盖。
ALTER TABLE crm_customer
    ADD COLUMN owner_management_mode VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL',
    ADD COLUMN region_management_mode VARCHAR(16) NOT NULL DEFAULT 'EXTERNAL',
    ADD COLUMN owner_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN region_revision BIGINT NOT NULL DEFAULT 0,
    ADD UNIQUE KEY uk_crm_customer_tenant_id(tenant_id,id),
    ADD CONSTRAINT ck_crm_owner_management CHECK(owner_management_mode IN ('EXTERNAL','LOCAL')),
    ADD CONSTRAINT ck_crm_region_management CHECK(region_management_mode IN ('EXTERNAL','LOCAL'));
CREATE TABLE crm_customer_responsibility_history (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, customer_id BIGINT NOT NULL,
    old_employee_code VARCHAR(50) NULL, new_employee_code VARCHAR(50) NULL,
    old_employee_name VARCHAR(128) NULL,new_employee_name VARCHAR(128) NULL,
    old_region_code VARCHAR(128) NULL,new_region_code VARCHAR(128) NULL,
    change_type VARCHAR(32) NOT NULL, reason VARCHAR(500) NOT NULL, source_system VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL, customer_revision BIGINT NOT NULL, effective_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id),KEY ix_crm_responsibility_history(tenant_id,customer_id,effective_at,id),
    CONSTRAINT fk_crm_responsibility_customer FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_authority_state(tenant_id VARCHAR(64) NOT NULL,version BIGINT NOT NULL DEFAULT 0,PRIMARY KEY(tenant_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_authority_change(id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(64) NOT NULL,version BIGINT NOT NULL,object_type VARCHAR(32) NOT NULL,object_ref VARCHAR(128) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(id),UNIQUE KEY uk_crm_authority_version(tenant_id,version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_responsibility_source_conflict(id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(64) NOT NULL,customer_id BIGINT NOT NULL,source_system VARCHAR(32) NOT NULL,proposed_employee_code VARCHAR(50) NULL,proposed_region_code VARCHAR(128) NULL,source_revision VARCHAR(128) NULL,status VARCHAR(16) NOT NULL DEFAULT 'PENDING',created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(id),KEY ix_crm_responsibility_conflict(tenant_id,customer_id,status),CONSTRAINT fk_crm_responsibility_conflict_customer FOREIGN KEY(tenant_id,customer_id) REFERENCES crm_customer(tenant_id,id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V18__customer_responsibility_review.sql
ALTER TABLE crm_responsibility_source_conflict
 ADD resolved_by VARCHAR(64) NULL, ADD resolved_at DATETIME(6) NULL,
 ADD resolution_reason VARCHAR(500) NULL, ADD customer_revision BIGINT NULL;

-- Source: V19__crm_member_responsibility_preview.sql
-- 预览只保存短期确认凭证和版本，不创建另一份用户客户授权名单。
CREATE TABLE crm_member_responsibility_preview (
    token VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    target_user_id VARCHAR(36) NOT NULL,
    target_employee_code VARCHAR(50) NOT NULL,
    target_fingerprint VARCHAR(64) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(tenant_id,token),
    KEY idx_crm_responsibility_preview_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE crm_member_responsibility_preview_item (
    tenant_id VARCHAR(64) NOT NULL,
    token VARCHAR(36) NOT NULL,
    customer_id BIGINT NOT NULL,
    customer_revision BIGINT NOT NULL,
    PRIMARY KEY(tenant_id,token,customer_id),
    CONSTRAINT fk_crm_responsibility_preview FOREIGN KEY(tenant_id,token)
        REFERENCES crm_member_responsibility_preview(tenant_id,token) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V20__crm_customer_area_sort_and_source_code.sql
-- 地区目录沿用稳定内部编码，来源编号单独展示，排序只在同级比较。
ALTER TABLE crm_customer_area
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0 COMMENT '同级显示顺序',
    ADD COLUMN source_area_code VARCHAR(128) NULL COMMENT '来源地区展示编号';
CREATE INDEX idx_crm_area_sort ON crm_customer_area(tenant_id, deleted, sort_order, area_code);

-- Source: V21__customer_business_creation.sql
-- 真实建档时间用于月度新增统计；系统 created_time 保留实际入库审计。
ALTER TABLE crm_customer
    ADD COLUMN business_created_at DATETIME(6) NULL COMMENT '客户原始建档时间 UTC，未知不猜测',
    ADD COLUMN business_created_by_id VARCHAR(128) NULL COMMENT '原始创建人来源标识',
    ADD COLUMN business_created_by_name VARCHAR(160) NULL COMMENT '原始创建人姓名快照',
    ADD COLUMN business_creation_source VARCHAR(32) NULL COMMENT 'FEISHU DINGHUOBAO INTERNAL，空表示待核实',
    ADD INDEX idx_customer_business_created (tenant_id, deleted, business_created_at);

-- 仅本系统人工新建记录可由入库审计还原；外部导入需核对原始客户记录。
UPDATE crm_customer SET business_created_at=created_time,
    business_created_by_id=created_by, business_creation_source='INTERNAL'
WHERE source_system_code IS NULL AND created_by NOT IN ('SYSTEM', 'system', 'DINGHUOBAO');

-- Source: V22__customer_source_merge_primary.sql
-- A user-confirmed duplicate can keep multiple source IDs without alternating ownership on each sync.
ALTER TABLE crm_source_binding
    ADD COLUMN primary_customer_source_id VARCHAR(128) NULL
        COMMENT '合并客户的主订货宝来源ID；NULL表示本记录负责主档投影',
    ADD COLUMN primary_projection_target_id BINARY(16)
        GENERATED ALWAYS AS (CASE WHEN primary_customer_source_id IS NULL THEN target_id ELSE NULL END) STORED,
    DROP INDEX uk_crm_source_binding_target,
    ADD UNIQUE KEY uk_crm_source_binding_target
        (tenant_id, connector_id, source_system, target_type, primary_projection_target_id),
    ADD CONSTRAINT ck_crm_customer_merge_primary CHECK
        (primary_customer_source_id IS NULL OR
            (source_object_type = 'CUSTOMER' AND target_type = 'PARTY'
                AND primary_customer_source_id <> source_object_id));

-- Source: V23__customer_account_and_sync_audit.sql
ALTER TABLE crm_customer
    ADD COLUMN login_account VARCHAR(160) NULL COMMENT '客户登录账号；订货宝主来源clientAccount',
    ADD COLUMN dhb_customer_code VARCHAR(128) NULL COMMENT '订货宝主客户编码，非系统客户编号或来源ID',
    ADD COLUMN synced_at DATETIME(6) NULL COMMENT '最近客户同步时间 UTC',
    ADD COLUMN synced_by VARCHAR(64) NULL COMMENT '最近同步发起人；历史服务同步为SYSTEM';

-- Use verified bindings only. Retain business creation facts and the existing modification audit.
UPDATE crm_customer c
JOIN crm_source_binding b ON b.tenant_id=UUID_TO_BIN(c.tenant_id) AND b.target_id=c.party_id
    AND b.source_system='DINGHUOBAO' AND b.source_object_type='CUSTOMER'
    AND b.binding_status='RESOLVED' AND b.deleted=0 AND b.primary_customer_source_id IS NULL
LEFT JOIN crm_customer_profile p ON p.tenant_id=b.tenant_id AND p.party_id=b.target_id AND p.deleted=0
LEFT JOIN crm_sync_run r ON r.tenant_id=b.tenant_id AND r.id=b.last_sync_run_id
SET c.login_account=COALESCE(NULLIF(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientAccount')),'null'),''),p.login_account),
    c.dhb_customer_code=b.source_code,
    c.settlement_type_code=COALESCE(NULLIF(NULLIF(LOWER(JSON_UNQUOTE(JSON_EXTRACT(b.source_fields_json,'$.clientClearingForm'))),'null'),''),c.settlement_type_code),
    c.synced_at=b.synced_at, c.synced_by=COALESCE(r.created_by,'SYSTEM'),
    c.updated_time=c.updated_time
WHERE c.deleted=0;

-- Source: V24__customer_identity_validation.sql
-- Legacy duplicate names remain intact; serialize identity checks per tenant.
CREATE TABLE crm_customer_identity_guard (
    tenant_id VARCHAR(36) NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

-- Unknown historical accounts stay NULL. Deleted accounts no longer reserve a login.
ALTER TABLE crm_customer
    ADD COLUMN active_login_account VARCHAR(160)
        GENERATED ALWAYS AS (CASE WHEN deleted=0 THEN NULLIF(LOWER(TRIM(login_account)), '') ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_customer_active_login (tenant_id, active_login_account);

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
