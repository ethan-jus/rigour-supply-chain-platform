-- HR 员工主数据基线。
-- 人员身份、岗位/职位、区域城市和来源字段归 HR；IAM 只保留账号、角色、权限与登录授权。

CREATE TABLE hr_position (
    id              BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id       VARCHAR(64)  NOT NULL COMMENT '租户ID',
    position_code   VARCHAR(50)  NOT NULL COMMENT '岗位/职位编码，由HR编码规则生成',
    position_name   VARCHAR(120) NOT NULL COMMENT '岗位/职位名称',
    position_type   VARCHAR(32)  NOT NULL COMMENT '类型：JOB_CATEGORY岗位分类，JOB_TITLE职位',
    status_code     VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
    source_system   VARCHAR(32)  NULL COMMENT '来源系统',
    remark          VARCHAR(500) NULL COMMENT '备注',
    revision        INT          NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by      VARCHAR(50)  NULL COMMENT '创建人',
    created_time    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by      VARCHAR(50)  NULL COMMENT '更新人',
    updated_time    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted         INT          NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_position_code (tenant_id, position_code),
    KEY idx_hr_position_name (tenant_id, position_type, position_name, deleted),
    KEY idx_hr_position_source (tenant_id, source_system, deleted),
    CONSTRAINT ck_hr_position_type CHECK (position_type IN ('JOB_CATEGORY', 'JOB_TITLE')),
    CONSTRAINT ck_hr_position_status CHECK (status_code IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HR岗位职位';

CREATE TABLE hr_employee (
    id                             BIGINT(20)    NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id                      VARCHAR(64)   NOT NULL COMMENT '租户ID',
    employee_code                  VARCHAR(50)   NOT NULL COMMENT '员工编码，由HR编码规则生成',
    employee_name                  VARCHAR(128)  NOT NULL COMMENT '员工姓名',
    mobile                         VARCHAR(32)   NULL COMMENT '手机号',
    email                          VARCHAR(128)  NULL COMMENT '邮箱',
    employment_status              VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE' COMMENT '在职状态',
    job_category                   VARCHAR(80)   NULL COMMENT '岗位分类',
    primary_position_code          VARCHAR(50)   NULL COMMENT '主职位编码',
    primary_position_name_snapshot VARCHAR(120)  NULL COMMENT '主职位名称快照',
    department_name_snapshot       VARCHAR(128)  NULL COMMENT '部门名称快照',
    leader_employee_code           VARCHAR(50)   NULL COMMENT '直属负责人员工编码',
    leader_name_snapshot           VARCHAR(128)  NULL COMMENT '直属负责人姓名快照',
    region_name                    VARCHAR(80)   NULL COMMENT '业务区域',
    city_name                      VARCHAR(80)   NULL COMMENT '城市',
    entry_date                     DATETIME(6)   NULL COMMENT '入职日期',
    leave_date                     DATETIME(6)   NULL COMMENT '离职日期',
    source_system                  VARCHAR(32)   NULL COMMENT '来源系统',
    source_document_no             VARCHAR(128)  NULL COMMENT '来源单号或来源人员ID',
    source_created_at              DATETIME(6)   NULL COMMENT '来源创建时间',
    source_updated_at              DATETIME(6)   NULL COMMENT '来源更新时间',
    source_payload_hash            CHAR(64)      NULL COMMENT '来源字段SHA-256摘要',
    source_payload_json            JSON          NULL COMMENT '来源原始字段快照',
    remark                         VARCHAR(500)  NULL COMMENT '备注',
    revision                       INT           NOT NULL DEFAULT 1 COMMENT '乐观锁',
    created_by                     VARCHAR(50)   NULL COMMENT '创建人',
    created_time                   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by                     VARCHAR(50)   NULL COMMENT '更新人',
    updated_time                   DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted                        INT           NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_employee_code (tenant_id, employee_code),
    KEY idx_hr_employee_name (tenant_id, employee_name, deleted),
    KEY idx_hr_employee_mobile (tenant_id, mobile, deleted),
    KEY idx_hr_employee_status (tenant_id, employment_status, deleted),
    KEY idx_hr_employee_area (tenant_id, region_name, city_name, deleted),
    KEY idx_hr_employee_source (tenant_id, source_system, source_document_no, deleted),
    CONSTRAINT ck_hr_employee_status CHECK (employment_status IN ('ACTIVE', 'INACTIVE', 'PENDING'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HR员工主档';

CREATE TABLE hr_employee_source_binding (
    id                  BIGINT(20)   NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)  NOT NULL COMMENT '租户ID',
    employee_code       VARCHAR(50)  NOT NULL COMMENT 'HR员工编码',
    connector_id        BINARY(16)   NULL COMMENT 'Integration连接器ID',
    source_system       VARCHAR(32)  NOT NULL COMMENT '来源系统',
    source_tenant_key   VARCHAR(128) NOT NULL DEFAULT 'DEFAULT' COMMENT '来源租户或表标识',
    source_employee_id  VARCHAR(128) NOT NULL COMMENT '来源员工ID或来源单号',
    source_account_name VARCHAR(128) NULL COMMENT '来源账号名',
    source_payload_hash CHAR(64)     NULL COMMENT '来源字段SHA-256摘要',
    source_payload_json JSON         NULL COMMENT '来源原始字段快照',
    source_created_at   DATETIME(6)  NULL COMMENT '来源创建时间',
    source_updated_at   DATETIME(6)  NULL COMMENT '来源更新时间',
    created_by          VARCHAR(50)  NULL COMMENT '创建人',
    created_time        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)  NULL COMMENT '更新人',
    updated_time        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    deleted             INT          NOT NULL DEFAULT 0 COMMENT '删除标识：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_employee_source_binding (
        tenant_id, source_system, source_tenant_key, source_employee_id
    ),
    KEY idx_hr_employee_source_binding_employee (
        tenant_id, employee_code, deleted
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='HR员工外部来源绑定';
