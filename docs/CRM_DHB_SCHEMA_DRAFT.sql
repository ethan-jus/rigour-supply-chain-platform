-- CRM / 订货宝一期数据库草案（评审稿，不是 Flyway migration）。
-- 目标：查询接口返回的业务字段完整落库，同时把 CRM 规范主档与订货宝来源事实解耦。
-- 约束：Integration 仍是订货宝 Raw Landing 的唯一写者；本文件不得直接放入 db/migration。

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户类型/等级规范主档';

CREATE TABLE crm_customer_area (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    area_code          VARCHAR(128)  NOT NULL COMMENT 'CRM 内部稳定编码',
    area_name          VARCHAR(160)  NOT NULL COMMENT '客户经营归属地区，不等于省市区地址',
    parent_area_code   VARCHAR(128)  NULL COMMENT '订货宝 parentID，对应上级地区来源 AreaID',
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
    KEY idx_crm_customer_area_parent (tenant_id, parent_area_code, status),
    KEY idx_crm_customer_area_city (tenant_id, city_id),
    KEY idx_crm_customer_area_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户经营归属地区主档';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户/商家统一经营主体';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='经营主体业务角色';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户角色扩展资料';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户当前信用与结算政策';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户/商家门店主档；一期不把订货宝客户强行等同为门店';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户和门店联系人';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户和门店地址';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订货宝员工目录在 CRM 的外部引用投影';

CREATE TABLE crm_sales_assignment (
    id                 BINARY(16)    NOT NULL COMMENT 'UUIDv7',
    tenant_id          BINARY(16)    NOT NULL,
    party_id           BINARY(16)    NULL,
    store_id           BINARY(16)    NULL,
    assignment_type    VARCHAR(24)   NOT NULL DEFAULT 'PRIMARY' COMMENT 'PRIMARY/SECONDARY/SERVICE',
    assignee_type      VARCHAR(32)   NOT NULL COMMENT 'EXTERNAL_STAFF/SALES_PROFILE/SALES_TEAM',
    external_staff_id  BINARY(16)    NULL,
    source_staff_id    VARCHAR(128)  NULL COMMENT '订货宝来源业务员ID；员工目录延迟时仍可保留',
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
    KEY idx_crm_sales_assignment_source_staff (tenant_id, source_staff_id, status),
    KEY idx_crm_sales_assignment_effective (tenant_id, status, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户/门店销售归属有效期历史';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM 外部主数据同步批次';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM 同步成功游标';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM 同步租约锁';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM 规范主档与外部来源身份及完整字段快照';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部对象多身份别名，处理 clientGUID/clientNO/addressId/addressGuid 并存';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM 领域事件可靠 Outbox';
