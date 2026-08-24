-- IAM V56：人员中心与岗位中心。
--
-- 业务口径：
-- 1. iam_staff_profile 是我方人员主档，组织架构和岗位以我方模型为准。
-- 2. 订货宝员工只作为过渡来源，落 iam_external_staff_binding 记录来源字段、幂等哈希和最后出现时间。
-- 3. 账号、角色、权限仍由 iam_user / iam_role / iam_role_resource 管控，人员通过绑定关联登录身份。

SET @changed_at = TIMESTAMP('2026-08-22 22:40:00.000000');
SET @app_system_admin = UUID_TO_BIN('019facf1-0000-7000-8000-000000000002');
SET @organization_menu = UUID_TO_BIN('019facf2-0000-7000-8000-000000000026');
SET @staff_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000330');
SET @staff_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000331');
SET @staff_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000332');
SET @staff_sync = UUID_TO_BIN('019facf2-0000-7000-8000-000000000333');
SET @position_page = UUID_TO_BIN('019facf2-0000-7000-8000-000000000334');
SET @position_read = UUID_TO_BIN('019facf2-0000-7000-8000-000000000335');
SET @position_write = UUID_TO_BIN('019facf2-0000-7000-8000-000000000336');
SET @standard_package_version = UUID_TO_BIN('019facf3-0000-7000-8000-000000000002');

CREATE TABLE iam_position (
    id             BINARY(16)      NOT NULL COMMENT '岗位ID，UUID二进制存储',
    tenant_id      BINARY(16)      NOT NULL COMMENT '租户ID',
    position_code  VARCHAR(50)     NOT NULL COMMENT '岗位编码，由IAM编码规则生成',
    position_name  VARCHAR(128)    NOT NULL COMMENT '岗位名称，例如销售经理、仓库主管',
    description    VARCHAR(500)    NULL COMMENT '岗位职责或备注说明',
    sort_order     INT             NOT NULL DEFAULT 0 COMMENT '岗位展示排序，数字越小越靠前',
    status         VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '岗位状态：ACTIVE启用，DISABLED停用',
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by     BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by     BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at     DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by     BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason  VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_position PRIMARY KEY (id),
    CONSTRAINT uk_iam_position_code UNIQUE (tenant_id, position_code),
    CONSTRAINT uk_iam_position_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_position_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT fk_iam_position_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_position_status (tenant_id, status, sort_order, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户岗位主档';

CREATE TABLE iam_staff_profile (
    id                      BINARY(16)      NOT NULL COMMENT '人员ID，UUID二进制存储',
    tenant_id               BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_code              VARCHAR(50)     NOT NULL COMMENT '员工编码，由IAM编码规则生成',
    staff_name              VARCHAR(128)    NOT NULL COMMENT '员工姓名，以我方人员档案为准',
    mobile                  VARCHAR(32)     NULL COMMENT '员工手机号',
    email                   VARCHAR(128)    NULL COMMENT '员工邮箱',
    employment_status       VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '在职状态：ACTIVE在职，DISABLED停用，LEFT离职',
    primary_organization_id BINARY(16)      NULL COMMENT '主组织ID，指向我方组织架构',
    primary_position_id     BINARY(16)      NULL COMMENT '主岗位ID，指向我方岗位主档',
    record_origin           VARCHAR(32)     NOT NULL DEFAULT 'MANUAL' COMMENT '档案来源：MANUAL手工，DINGHUOBAO订货宝导入，IMPORT批量导入',
    remark                  VARCHAR(500)    NULL COMMENT '人员备注',
    version                 BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at              DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by              BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at              DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by              BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at              DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by              BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason           VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_staff_profile PRIMARY KEY (id),
    CONSTRAINT uk_iam_staff_profile_code UNIQUE (tenant_id, staff_code),
    CONSTRAINT uk_iam_staff_profile_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_iam_staff_profile_status CHECK (employment_status IN ('ACTIVE', 'DISABLED', 'LEFT')),
    CONSTRAINT ck_iam_staff_profile_origin CHECK (record_origin IN ('MANUAL', 'DINGHUOBAO', 'IMPORT')),
    CONSTRAINT fk_iam_staff_profile_tenant FOREIGN KEY (tenant_id)
        REFERENCES iam_tenant (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_profile_org FOREIGN KEY (tenant_id, primary_organization_id)
        REFERENCES iam_organization (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_profile_position FOREIGN KEY (tenant_id, primary_position_id)
        REFERENCES iam_position (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_staff_profile_org (tenant_id, primary_organization_id, employment_status, deleted_at),
    INDEX idx_iam_staff_profile_position (tenant_id, primary_position_id, employment_status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户人员主档';

CREATE TABLE iam_staff_assignment (
    id              BINARY(16)      NOT NULL COMMENT '任职关系ID，UUID二进制存储',
    tenant_id       BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_id        BINARY(16)      NOT NULL COMMENT '人员ID',
    organization_id BINARY(16)      NOT NULL COMMENT '任职组织ID，指向我方组织架构',
    position_id     BINARY(16)      NULL COMMENT '任职岗位ID，指向我方岗位主档',
    assignment_type VARCHAR(32)     NOT NULL DEFAULT 'PRIMARY' COMMENT '任职类型：PRIMARY主任职，SECONDARY兼任',
    status          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '任职状态：ACTIVE有效，INACTIVE失效',
    effective_from  DATETIME(6)     NOT NULL COMMENT '任职生效时间',
    effective_to    DATETIME(6)     NULL COMMENT '任职失效时间，NULL表示当前有效',
    version         BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at      DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by      BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at      DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by      BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at      DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by      BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason   VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_staff_assignment PRIMARY KEY (id),
    CONSTRAINT ck_iam_staff_assignment_type CHECK (assignment_type IN ('PRIMARY', 'SECONDARY')),
    CONSTRAINT ck_iam_staff_assignment_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_iam_staff_assignment_period CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT fk_iam_staff_assignment_staff FOREIGN KEY (tenant_id, staff_id)
        REFERENCES iam_staff_profile (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_assignment_org FOREIGN KEY (tenant_id, organization_id)
        REFERENCES iam_organization (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_assignment_position FOREIGN KEY (tenant_id, position_id)
        REFERENCES iam_position (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_staff_assignment_staff (tenant_id, staff_id, status, effective_to),
    INDEX idx_iam_staff_assignment_org (tenant_id, organization_id, status, effective_to),
    INDEX idx_iam_staff_assignment_position (tenant_id, position_id, status, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人员任职关系';

CREATE TABLE iam_staff_user_binding (
    id             BINARY(16)      NOT NULL COMMENT '人员账号绑定ID，UUID二进制存储',
    tenant_id      BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_id       BINARY(16)      NOT NULL COMMENT '人员ID',
    user_id        BINARY(16)      NOT NULL COMMENT 'IAM登录账号ID',
    status         VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT '绑定状态：ACTIVE有效，INACTIVE失效',
    bound_at       DATETIME(6)     NOT NULL COMMENT '绑定生效时间',
    version        BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at     DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by     BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at     DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by     BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at     DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by     BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason  VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_staff_user_binding PRIMARY KEY (id),
    CONSTRAINT uk_iam_staff_user_binding_staff UNIQUE (tenant_id, staff_id),
    CONSTRAINT ck_iam_staff_user_binding_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT fk_iam_staff_user_binding_staff FOREIGN KEY (tenant_id, staff_id)
        REFERENCES iam_staff_profile (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_iam_staff_user_binding_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES iam_user (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_staff_user_binding_user (tenant_id, user_id, status, deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人员与登录账号绑定';

CREATE TABLE iam_external_staff_binding (
    id                     BINARY(16)      NOT NULL COMMENT '外部员工绑定ID，UUID二进制存储',
    tenant_id              BINARY(16)      NOT NULL COMMENT '租户ID',
    staff_id               BINARY(16)      NOT NULL COMMENT '我方人员ID',
    connector_id           BINARY(16)      NULL COMMENT '订货宝连接配置ID，来源于Integration连接配置',
    source_system          VARCHAR(32)     NOT NULL COMMENT '外部来源系统，当前固定DINGHUOBAO',
    source_tenant_key      VARCHAR(128)    NOT NULL DEFAULT 'DEFAULT' COMMENT '外部租户或店铺标识，缺省DEFAULT',
    source_staff_id        VARCHAR(128)    NOT NULL COMMENT '订货宝staff_id，唯一识别来源员工',
    source_staff_type      VARCHAR(32)     NULL COMMENT '订货宝staff_type：salesman、boss、indoorwork、driver',
    source_account_name    VARCHAR(128)    NULL COMMENT '订货宝accounts_name，登录账号名',
    source_staff_name      VARCHAR(128)    NULL COMMENT '订货宝staff_name，员工姓名快照',
    source_title           VARCHAR(128)    NULL COMMENT '订货宝title，职位快照，不直接生成我方岗位',
    source_branch_name     VARCHAR(128)    NULL COMMENT '订货宝branch_name，部门快照，不直接生成我方组织',
    source_accounts_mobile VARCHAR(32)     NULL COMMENT '订货宝accounts_mobile，账号手机号快照',
    source_about           VARCHAR(500)    NULL COMMENT '订货宝about，备注快照',
    source_role            VARCHAR(128)    NULL COMMENT '订货宝role，角色快照，不直接生成我方IAM角色',
    source_invite_code     VARCHAR(64)     NULL COMMENT '订货宝invite_code，邀请码快照',
    source_mobile          VARCHAR(32)     NULL COMMENT '订货宝mobile，员工手机号快照',
    source_email           VARCHAR(128)    NULL COMMENT '订货宝email，邮箱快照',
    source_qq              VARCHAR(64)     NULL COMMENT '订货宝qq，QQ号码快照',
    source_status          VARCHAR(32)     NULL COMMENT '订货宝状态原值，例如T启用、F停用',
    source_payload_hash    VARCHAR(128)    NULL COMMENT '来源负载哈希，用于幂等判断和跳过未变化记录',
    source_payload_json    JSON            NULL COMMENT '订货宝员工原始行JSON快照，便于问题回放和字段补偿',
    source_created_at      DATETIME(6)     NULL COMMENT '订货宝create_date，员工创建时间',
    source_updated_at      DATETIME(6)     NULL COMMENT '订货宝update_date，员工最后更新时间',
    source_presence        VARCHAR(32)     NOT NULL DEFAULT 'PRESENT' COMMENT '来源存在状态：PRESENT仍存在，MISSING来源缺失',
    last_seen_at           DATETIME(6)     NOT NULL COMMENT '最近一次同步看到该来源员工的时间',
    version                BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    created_at             DATETIME(6)     NOT NULL COMMENT '创建时间',
    created_by             BINARY(16)      NULL COMMENT '创建人用户ID',
    updated_at             DATETIME(6)     NOT NULL COMMENT '最后更新时间',
    updated_by             BINARY(16)      NULL COMMENT '最后更新人用户ID',
    deleted_at             DATETIME(6)     NULL COMMENT '逻辑删除时间',
    deleted_by             BINARY(16)      NULL COMMENT '逻辑删除人用户ID',
    delete_reason          VARCHAR(255)    NULL COMMENT '逻辑删除原因',
    CONSTRAINT pk_iam_external_staff_binding PRIMARY KEY (id),
    CONSTRAINT uk_iam_external_staff_binding_source UNIQUE
        (tenant_id, source_system, source_tenant_key, source_staff_id),
    CONSTRAINT ck_iam_external_staff_binding_system CHECK (source_system IN ('DINGHUOBAO')),
    CONSTRAINT ck_iam_external_staff_binding_presence CHECK (source_presence IN ('PRESENT', 'MISSING')),
    CONSTRAINT fk_iam_external_staff_binding_staff FOREIGN KEY (tenant_id, staff_id)
        REFERENCES iam_staff_profile (tenant_id, id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    INDEX idx_iam_external_staff_binding_staff (tenant_id, staff_id, source_presence),
    INDEX idx_iam_external_staff_binding_seen (tenant_id, source_system, last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='外部员工来源绑定与订货宝字段快照';

INSERT INTO iam_resource (
    id, application_id, parent_id, resource_code, resource_type,
    permission_code, display_name, sort_order, status, created_at, updated_at
) VALUES
    (@staff_page, @app_system_admin, @organization_menu,
     'SYSTEM_ADMIN.PAGE.STAFF_LIST', 'PAGE', NULL,
     '人员管理', 20, 'ACTIVE', @changed_at, @changed_at),
    (@staff_read, @app_system_admin, @staff_page,
     'SYSTEM_ADMIN.API.STAFF_READ', 'API', 'iam:staff:read',
     '查询人员', 10, 'ACTIVE', @changed_at, @changed_at),
    (@staff_write, @app_system_admin, @staff_page,
     'SYSTEM_ADMIN.API.STAFF_WRITE', 'API', 'iam:staff:write',
     '维护人员', 20, 'ACTIVE', @changed_at, @changed_at),
    (@staff_sync, @app_system_admin, @staff_page,
     'SYSTEM_ADMIN.API.STAFF_SYNC', 'API', 'iam:staff:sync',
     '同步外部员工', 30, 'ACTIVE', @changed_at, @changed_at),
    (@position_page, @app_system_admin, @organization_menu,
     'SYSTEM_ADMIN.PAGE.POSITION_LIST', 'PAGE', NULL,
     '岗位管理', 30, 'ACTIVE', @changed_at, @changed_at),
    (@position_read, @app_system_admin, @position_page,
     'SYSTEM_ADMIN.API.POSITION_READ', 'API', 'iam:position:read',
     '查询岗位', 10, 'ACTIVE', @changed_at, @changed_at),
    (@position_write, @app_system_admin, @position_page,
     'SYSTEM_ADMIN.API.POSITION_WRITE', 'API', 'iam:position:write',
     '维护岗位', 20, 'ACTIVE', @changed_at, @changed_at);

INSERT INTO iam_resource_ui (
    resource_id, route_key, route_path, icon_key, visible, keep_alive, created_at, updated_at
) VALUES
    (@staff_page, 'system.staff.list', '/system-admin/staff', NULL, 1, 0, @changed_at, @changed_at),
    (@position_page, 'system.position.list', '/system-admin/positions', NULL, 1, 0, @changed_at, @changed_at);

INSERT INTO iam_package_resource (package_version_id, resource_id, created_at, created_by)
SELECT @standard_package_version, resource_id, @changed_at, NULL
FROM (
    SELECT @staff_page AS resource_id UNION ALL SELECT @staff_read UNION ALL SELECT @staff_write
    UNION ALL SELECT @staff_sync UNION ALL SELECT @position_page UNION ALL SELECT @position_read
    UNION ALL SELECT @position_write
) added_resources
ON DUPLICATE KEY UPDATE created_at = created_at;

INSERT INTO iam_role_resource (tenant_id, role_id, resource_id, status, created_at, updated_at)
SELECT role_record.tenant_id, role_record.id, added_resources.resource_id,
       'ACTIVE', @changed_at, @changed_at
  FROM iam_role role_record
 CROSS JOIN (
    SELECT @staff_page AS resource_id UNION ALL SELECT @staff_read UNION ALL SELECT @staff_write
    UNION ALL SELECT @staff_sync UNION ALL SELECT @position_page UNION ALL SELECT @position_read
    UNION ALL SELECT @position_write
 ) added_resources
 WHERE role_record.role_code = 'TENANT_SUPER_ADMIN'
   AND role_record.role_type = 'SYSTEM'
   AND role_record.status = 'ACTIVE'
   AND role_record.deleted_at IS NULL
ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = @changed_at;

INSERT INTO iam_tenant_menu_config (
    tenant_id, resource_id, visible, created_at, created_by, updated_at, updated_by
)
SELECT DISTINCT subscription.tenant_id, resource_record.id, resource_ui.visible,
       @changed_at, NULL, @changed_at, NULL
  FROM iam_tenant_subscription subscription
  JOIN iam_package_resource package_resource
    ON package_resource.package_version_id = subscription.package_version_id
  JOIN iam_resource resource_record
    ON resource_record.id = package_resource.resource_id
   AND resource_record.resource_type IN ('MENU', 'PAGE')
   AND resource_record.status = 'ACTIVE'
   AND resource_record.deleted_at IS NULL
  JOIN iam_resource_ui resource_ui ON resource_ui.resource_id = resource_record.id
 WHERE subscription.status IN ('ACTIVE', 'SCHEDULED')
   AND subscription.effective_from <= UTC_TIMESTAMP(6)
   AND subscription.effective_to > UTC_TIMESTAMP(6)
   AND resource_record.id IN (@staff_page, @position_page)
ON DUPLICATE KEY UPDATE visible = VALUES(visible), updated_at = @changed_at;

UPDATE iam_tenant
   SET policy_version = policy_version + 1,
       version = version + 1,
       updated_at = @changed_at
 WHERE status = 'ACTIVE'
   AND deleted_at IS NULL;
