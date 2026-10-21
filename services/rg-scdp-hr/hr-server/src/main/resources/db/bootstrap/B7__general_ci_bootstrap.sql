-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- Source: V1__hr_employee_master_data.sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR岗位职位';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR员工主档';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR员工外部来源绑定';

-- Source: V2__hr_employee_separation_status.sql
-- 离职与停用分开。仅修复有明确离职证据的旧 INACTIVE，不按姓名或岗位猜测。
ALTER TABLE hr_employee DROP CHECK ck_hr_employee_status;
ALTER TABLE hr_employee ADD CONSTRAINT ck_hr_employee_status
    CHECK (employment_status IN ('ACTIVE', 'LEFT', 'INACTIVE', 'PENDING'));

UPDATE hr_employee
SET employment_status = 'LEFT', revision = revision + 1, updated_by = 'SYSTEM'
WHERE employment_status = 'INACTIVE'
  AND (leave_date IS NOT NULL
       OR JSON_UNQUOTE(JSON_EXTRACT(source_payload_json, '$."在职状态"')) IN ('离职', 'LEFT')
       OR JSON_UNQUOTE(JSON_EXTRACT(source_payload_json, '$."状态"')) IN ('离职', 'LEFT'));

-- Source: V3__hr_departments_and_assignments.sql
-- HR 部门为供应链组织主数据；不按旧名称自动生成或猜测映射。
CREATE TABLE hr_organization_state (
    tenant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_department (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
    department_code VARCHAR(50) NOT NULL, parent_id BIGINT NULL,
    department_name VARCHAR(128) NOT NULL, sort_order INT NOT NULL DEFAULT 0,
    status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revision INT NOT NULL DEFAULT 1,
    created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY(id), UNIQUE KEY uk_hr_department_identity(tenant_id,id),
    UNIQUE KEY uk_hr_department_code(tenant_id,department_code),
    KEY ix_hr_department_parent(tenant_id,parent_id,deleted,sort_order),
    CONSTRAINT fk_hr_department_parent FOREIGN KEY(tenant_id,parent_id) REFERENCES hr_department(tenant_id,id),
    CONSTRAINT ck_hr_department_status CHECK(status_code IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_department_closure (
    tenant_id VARCHAR(64) NOT NULL, ancestor_id BIGINT NOT NULL, descendant_id BIGINT NOT NULL, depth INT NOT NULL,
    PRIMARY KEY(tenant_id,ancestor_id,descendant_id),
    KEY ix_hr_department_ancestors(tenant_id,descendant_id,depth),
    CONSTRAINT fk_hr_closure_ancestor FOREIGN KEY(tenant_id,ancestor_id) REFERENCES hr_department(tenant_id,id),
    CONSTRAINT fk_hr_closure_descendant FOREIGN KEY(tenant_id,descendant_id) REFERENCES hr_department(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE hr_employee ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN department_id BIGINT NULL AFTER department_name_snapshot,
    ADD INDEX ix_hr_employee_department(tenant_id,department_id,employment_status,deleted),
    ADD CONSTRAINT fk_hr_employee_department FOREIGN KEY(tenant_id,department_id) REFERENCES hr_department(tenant_id,id);

CREATE TABLE hr_employee_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, employee_code VARCHAR(50) NOT NULL,
    department_id BIGINT NOT NULL, department_name_snapshot VARCHAR(128) NOT NULL,
    position_code VARCHAR(50) NOT NULL, position_name_snapshot VARCHAR(120) NOT NULL,
    effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6) NULL,
    actor_id VARCHAR(50) NOT NULL, revision INT NOT NULL DEFAULT 1,
    current_employee_code VARCHAR(50) GENERATED ALWAYS AS(CASE WHEN effective_to IS NULL THEN employee_code ELSE NULL END) STORED,
    PRIMARY KEY(id), UNIQUE KEY uk_hr_current_assignment(tenant_id,current_employee_code),
    KEY ix_hr_assignment_history(tenant_id,employee_code,effective_from),
    CONSTRAINT fk_hr_assignment_employee FOREIGN KEY(tenant_id,employee_code) REFERENCES hr_employee(tenant_id,employee_code),
    CONSTRAINT fk_hr_assignment_department FOREIGN KEY(tenant_id,department_id) REFERENCES hr_department(tenant_id,id),
    CONSTRAINT fk_hr_assignment_position FOREIGN KEY(tenant_id,position_code) REFERENCES hr_position(tenant_id,position_code),
    CONSTRAINT ck_hr_assignment_period CHECK(effective_to IS NULL OR effective_to>=effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_organization_change (
    id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL, object_ref VARCHAR(128) NOT NULL,
    actor_id VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(id), UNIQUE KEY uk_hr_change_version(tenant_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V4__hr_department_contact_and_audit.sql
-- 部门资料与操作人审计。既有创建/修改时间保持原值，旧操作人从组织变更记录回填。
ALTER TABLE hr_department
    ADD COLUMN leader_name VARCHAR(128) NULL COMMENT '部门负责人姓名，资料字段，不自动授权',
    ADD COLUMN contact_phone VARCHAR(32) NULL COMMENT '部门联系电话',
    ADD COLUMN established_date DATE NULL COMMENT '部门设立日期',
    ADD COLUMN created_by VARCHAR(50) NULL COMMENT '创建人账号 ID，由服务端记录',
    ADD COLUMN created_by_name VARCHAR(128) NULL COMMENT '创建人显示名称快照',
    ADD COLUMN updated_by VARCHAR(50) NULL COMMENT '最近修改人账号 ID，由服务端记录',
    ADD COLUMN updated_by_name VARCHAR(128) NULL COMMENT '最近修改人显示名称快照';

UPDATE hr_department d SET
    created_by = (SELECT c.actor_id FROM hr_organization_change c
        WHERE c.tenant_id=d.tenant_id AND c.object_ref=CAST(d.id AS CHAR)
          AND c.event_type='DEPARTMENT_CREATE' ORDER BY c.version ASC LIMIT 1),
    updated_by = (SELECT c.actor_id FROM hr_organization_change c
        WHERE c.tenant_id=d.tenant_id AND c.object_ref=CAST(d.id AS CHAR)
          AND c.event_type IN ('DEPARTMENT_CREATE','DEPARTMENT_UPDATE','DEPARTMENT_DELETE')
        ORDER BY c.version DESC LIMIT 1);

-- Source: V5__employee_profile_and_department_leader.sql
-- 员工个人资料独立存储，列表及跨服务身份接口不读取。
CREATE TABLE hr_employee_profile (
 tenant_id VARCHAR(50) NOT NULL,
 employee_id BIGINT NOT NULL,
 id_number VARCHAR(18) NULL,
 contract_end_date DATE NULL,
 education VARCHAR(32) NULL,
 registered_address VARCHAR(300) NULL,
 household_type VARCHAR(32) NULL,
 residential_address VARCHAR(300) NULL,
 bank_account VARCHAR(32) NULL,
 bank_name VARCHAR(128) NULL,
 social_insurance VARCHAR(32) NULL,
 emergency_contact VARCHAR(128) NULL,
 emergency_phone VARCHAR(32) NULL,
 PRIMARY KEY (tenant_id, employee_id)
);
ALTER TABLE hr_employee ADD COLUMN created_by_name VARCHAR(128) NULL,
 ADD COLUMN updated_by_name VARCHAR(128) NULL;
ALTER TABLE hr_department ADD COLUMN leader_employee_code VARCHAR(50) NULL,
 ADD INDEX idx_department_leader (tenant_id, leader_employee_code);
-- 旧手填姓名仅在同租户唯一且在职的员工匹配时迁移为关联；重名不自动绑定。
UPDATE hr_department d JOIN (
 SELECT tenant_id,employee_name,MIN(employee_code) AS code FROM hr_employee
 WHERE deleted=0 AND employment_status='ACTIVE' GROUP BY tenant_id,employee_name HAVING COUNT(*)=1
) e ON e.tenant_id=d.tenant_id AND e.employee_name=d.leader_name
SET d.leader_employee_code=e.code WHERE d.deleted=0 AND d.leader_employee_code IS NULL;

-- Source: V6__hr_position_catalog.sql
-- 统一岗位目录；保留旧编码对应的历史任职记录，不改写历史 SQL。
ALTER TABLE hr_position ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

CREATE TEMPORARY TABLE hr_position_seed (code VARCHAR(50), name VARCHAR(120), display_order INT);
INSERT INTO hr_position_seed VALUES
('POS_SALES','业务员',0),('POS_CITY_MANAGER','城市总',1),('POS_TRAINEE_CITY_MANAGER','见习城市总',2),
('POS_CEO','CEO',3),('POS_GENERAL_MANAGER','总经理',4),('POS_SALES_DIRECTOR','销售总监',5),
('POS_OPERATIONS_DIRECTOR','运营总监',6),('POS_PLATFORM_OPERATIONS','平台运营',7),
('POS_DATA_OPERATIONS','数据运营',8),('POS_EVENT_OPERATIONS','活动运营',9),('POS_HRBP','HRBP',10);
CREATE TEMPORARY TABLE hr_position_tenants AS
SELECT tenant_id FROM hr_position UNION SELECT tenant_id FROM hr_employee UNION SELECT tenant_id FROM hr_department;

INSERT INTO hr_position(tenant_id,position_code,position_name,position_type,status_code,source_system,sort_order,created_by,updated_by)
SELECT t.tenant_id,s.code,s.name,'JOB_TITLE','ACTIVE','MANUAL',s.display_order,'SYSTEM','SYSTEM'
FROM hr_position_tenants t CROSS JOIN hr_position_seed s
WHERE NOT EXISTS(SELECT 1 FROM hr_position p WHERE p.tenant_id=t.tenant_id AND p.deleted=0 AND
 CASE WHEN p.position_name IN ('销售','销售员','大客户经理') THEN '业务员' ELSE p.position_name END=s.name)
;

CREATE TEMPORARY TABLE hr_position_canonical AS
SELECT p.tenant_id,s.name,s.display_order,MIN(p.id) id
FROM hr_position p JOIN hr_position_seed s ON
 CASE WHEN p.position_name IN ('销售','销售员','大客户经理') THEN '业务员' ELSE p.position_name END=s.name
WHERE p.deleted=0 GROUP BY p.tenant_id,s.name,s.display_order;
CREATE TEMPORARY TABLE hr_position_merge AS
SELECT p.tenant_id,p.id old_id,p.position_code old_code,c.id canonical_id,target.position_code new_code,c.name
FROM hr_position p JOIN hr_position_canonical c ON p.tenant_id=c.tenant_id AND
 CASE WHEN p.position_name IN ('销售','销售员','大客户经理') THEN '业务员' ELSE p.position_name END=c.name
JOIN hr_position target ON target.id=c.id WHERE p.deleted=0;

UPDATE hr_employee e JOIN hr_position_merge m ON e.tenant_id=m.tenant_id AND e.primary_position_code=m.old_code
SET e.primary_position_code=m.new_code,e.primary_position_name_snapshot=m.name,
 e.job_category=CASE WHEN e.job_category IN ('销售','销售员','大客户经理') THEN '业务员' ELSE e.job_category END,
 e.revision=e.revision+1,e.updated_by='SYSTEM',e.updated_by_name=NULL,e.updated_time=UTC_TIMESTAMP(6)
WHERE e.deleted=0;
-- 只更新当前任职；已结束任职保留发生时的岗位编码和名称。
UPDATE hr_employee_assignment a JOIN hr_position_merge m ON a.tenant_id=m.tenant_id AND a.position_code=m.old_code
SET a.position_code=m.new_code,a.position_name_snapshot=m.name,a.revision=a.revision+1
WHERE a.effective_to IS NULL;
UPDATE hr_position p JOIN hr_position_canonical c ON p.id=c.id
SET p.position_name=c.name,p.position_type='JOB_TITLE',p.sort_order=c.display_order,
 p.revision=p.revision+1,p.updated_by='SYSTEM',p.updated_time=UTC_TIMESTAMP(6);
UPDATE hr_position p JOIN hr_position_merge m ON p.id=m.old_id
SET p.deleted=1,p.status_code='INACTIVE',p.revision=p.revision+1,p.updated_by='SYSTEM',p.updated_time=UTC_TIMESTAMP(6)
WHERE m.old_id<>m.canonical_id;
INSERT IGNORE INTO hr_organization_state(tenant_id,version) SELECT tenant_id,0 FROM hr_position_tenants;
UPDATE hr_organization_state st JOIN hr_position_tenants t ON st.tenant_id=t.tenant_id SET st.version=GREATEST(st.version,COALESCE((SELECT MAX(c.version) FROM hr_organization_change c WHERE c.tenant_id=st.tenant_id),0))+1;
INSERT INTO hr_organization_change(tenant_id,version,event_type,object_ref,actor_id)
SELECT st.tenant_id,st.version,'POSITION_CATALOG_NORMALIZED','V6','SYSTEM' FROM hr_organization_state st
JOIN hr_position_tenants t ON st.tenant_id=t.tenant_id;
DROP TEMPORARY TABLE hr_position_merge;
DROP TEMPORARY TABLE hr_position_canonical;
DROP TEMPORARY TABLE hr_position_tenants;
DROP TEMPORARY TABLE hr_position_seed;

-- 对外只保留单一岗位，不再保存类型、来源等旧字段。
ALTER TABLE hr_position DROP CHECK ck_hr_position_type,
    DROP INDEX idx_hr_position_name, DROP INDEX idx_hr_position_source,
    DROP COLUMN position_type, DROP COLUMN source_system,
    ADD INDEX idx_hr_position_name (tenant_id,position_name,deleted),
    ADD INDEX idx_hr_position_sort (tenant_id,deleted,sort_order,id);

-- Source: V7__hr_employee_job_grade.sql
ALTER TABLE hr_employee ADD COLUMN job_grade VARCHAR(32) NULL COMMENT '员工职级',
    ADD COLUMN local_profile_authoritative BOOLEAN NOT NULL DEFAULT FALSE COMMENT '已核定花名册资料由HR维护，来源同步仅保留来源记录',
    ADD INDEX idx_hr_employee_grade (tenant_id, job_grade, deleted);
UPDATE hr_employee e JOIN hr_position p ON p.tenant_id=e.tenant_id AND p.position_code=e.primary_position_code
SET e.job_grade='S1' WHERE e.deleted=0 AND p.deleted=0 AND p.position_name='业务员' AND e.job_grade IS NULL;

ALTER TABLE hr_employee_profile
    ADD COLUMN graduation_school VARCHAR(128) NULL,
    ADD COLUMN major VARCHAR(128) NULL,
    ADD COLUMN regular_salary VARCHAR(64) NULL COMMENT '花名册薪资原文，仅作档案记录',
    ADD COLUMN probation_salary VARCHAR(64) NULL COMMENT '试用期薪资原文，仅作档案记录',
    ADD COLUMN probation_period VARCHAR(32) NULL;

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
