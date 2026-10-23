-- Frozen fresh-schema baseline. Do not edit after deployment.
-- Historical V migrations remain available for existing databases.
-- Contains built-in configuration seeds, not an import of historical business data.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
SET NAMES utf8mb4 COLLATE utf8mb4_general_ci;

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

-- Source: V8__dhb_binding_review_and_accounts.sql
ALTER TABLE hr_employee_source_binding
 ADD source_employee_name VARCHAR(128) NULL,
 ADD source_mobile VARCHAR(32) NULL,
 ADD review_required BOOLEAN NOT NULL DEFAULT FALSE,
 ADD review_reason VARCHAR(300) NULL,
 ADD review_version BIGINT NOT NULL DEFAULT 1;

-- Old roster links saved the employee name in source_account_name. Recover only
-- from the stored DHB payload whose staff ID agrees with this exact binding.
UPDATE hr_employee_source_binding
SET source_account_name=JSON_UNQUOTE(JSON_EXTRACT(source_payload_json,'$.employee.accountName'))
WHERE source_system='DINGHUOBAO' AND deleted=0
 AND JSON_UNQUOTE(JSON_EXTRACT(source_payload_json,'$.employee.staffId'))=source_employee_id
 AND JSON_TYPE(JSON_EXTRACT(source_payload_json,'$.employee.accountName'))='STRING'
 AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(source_payload_json,'$.employee.accountName')))<>'';

CREATE TABLE hr_dhb_binding_review_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id VARCHAR(64) NOT NULL, binding_id BIGINT NOT NULL,
 before_employee_code VARCHAR(50) NOT NULL, after_employee_code VARCHAR(50) NOT NULL,
 source_employee_name VARCHAR(128) NULL, source_mobile VARCHAR(32) NULL,
 source_account_name VARCHAR(128) NULL, review_reason VARCHAR(300) NULL,
 actor_id VARCHAR(64) NOT NULL, reviewed_at DATETIME(6) NOT NULL,
 review_version BIGINT NOT NULL,
 UNIQUE KEY uk_binding_review (tenant_id,binding_id,review_version)
);

-- Source: V9__hr_business_targets.sql
-- HR 为城市及销售目标的唯一写者；未保存的指标由服务返回初始值。
CREATE TABLE hr_business_target (
    id                  BIGINT(20)     NOT NULL AUTO_INCREMENT COMMENT 'ID',
    tenant_id           VARCHAR(64)    NOT NULL COMMENT '租户ID',
    target_month        DATE           NOT NULL COMMENT '目标月份，取当月1日',
    dimension_type      VARCHAR(32)    NOT NULL COMMENT '目标维度：CITY/SALES_OWNER',
    dimension_code      VARCHAR(64)    NOT NULL COMMENT '维度编码',
    dimension_name      VARCHAR(160)   NULL COMMENT '维度名称快照',
    metric_code         VARCHAR(64)    NOT NULL COMMENT '指标编码：SALES_AMOUNT/RECEIPT_AMOUNT/NEW_CUSTOMER/REPEAT_CUSTOMER',
    target_value        DECIMAL(24,6)  NOT NULL DEFAULT 0 COMMENT '目标值',
    remark              VARCHAR(1000)  NULL COMMENT '备注',
    revision            INT            NOT NULL DEFAULT 1 COMMENT '乐观锁版本',
    created_by          VARCHAR(50)    NULL COMMENT '创建人',
    created_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    updated_by          VARCHAR(50)    NULL COMMENT '更新人',
    updated_time        DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_business_target_metric (
        tenant_id, target_month, dimension_type, dimension_code, metric_code
    ),
    KEY idx_hr_business_target_dimension (tenant_id, dimension_type, dimension_code, target_month),
    KEY idx_hr_business_target_metric (tenant_id, metric_code, target_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR城市/销售经营目标配置';

CREATE TABLE hr_business_target_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    revision INT NOT NULL,
    target_value DECIMAL(24,6) NOT NULL,
    dimension_name VARCHAR(160) NULL,
    remark VARCHAR(1000) NULL,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_target_event_revision (tenant_id, target_id, revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='HR经营目标修订记录';

-- Source: V10__hr_payroll_and_expenses.sql
-- 工资按员工月份锁定，数值薪资标准独立于花名册原文；不改写历史员工档案。
CREATE TABLE hr_payroll_guard (
 tenant_id VARCHAR(64) NOT NULL, version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_rule (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, request_id VARCHAR(36) NOT NULL,
 kind_code VARCHAR(32) NOT NULL, name VARCHAR(120) NOT NULL, employee_code VARCHAR(50) NULL,
 effective_from DATE NOT NULL,effective_to DATE NOT NULL,enabled BOOLEAN NOT NULL,
 data_json JSON NOT NULL,revision INT NOT NULL DEFAULT 1,
 created_by VARCHAR(50) NOT NULL,updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),UNIQUE KEY uk_pay_rule_request(tenant_id,request_id),KEY ix_pay_rule(tenant_id,kind_code,effective_from,effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_rule_department (
 tenant_id VARCHAR(64) NOT NULL,rule_id VARCHAR(36) NOT NULL,department_id BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,rule_id,department_id),KEY ix_pay_rule_department(tenant_id,department_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_input (
 tenant_id VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,payroll_month CHAR(7) NOT NULL,
 base_override DECIMAL(18,2) NULL,performance_override DECIMAL(18,2) NULL,deduction DECIMAL(18,2) NOT NULL,
 reason VARCHAR(500) NOT NULL,revision INT NOT NULL DEFAULT 1,updated_by VARCHAR(50) NOT NULL,
 updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,employee_code,payroll_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll (
 id VARCHAR(36) NOT NULL,tenant_id VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,
 employee_name VARCHAR(128) NOT NULL,department_id BIGINT NULL,department_name VARCHAR(128) NULL,city_name VARCHAR(80) NULL,
 payroll_month CHAR(7) NOT NULL,status_code VARCHAR(20) NOT NULL,revision INT NOT NULL DEFAULT 1,
 calculation_json JSON NOT NULL,created_by VARCHAR(50) NOT NULL,updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),UNIQUE KEY uk_payroll_employee_month(tenant_id,employee_code,payroll_month),
 KEY ix_payroll_list(tenant_id,payroll_month,department_id,status_code),
 CONSTRAINT ck_payroll_status CHECK(status_code IN ('REVIEW','READY','CONFIRMED','PAID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_commission_release (
 tenant_id VARCHAR(64) NOT NULL,source_line_id VARCHAR(50) NOT NULL,recipient_role_code VARCHAR(20) NOT NULL,payroll_id VARCHAR(36) NOT NULL,
 amount DECIMAL(18,2) NOT NULL,PRIMARY KEY(tenant_id,source_line_id,recipient_role_code),KEY ix_payroll_release(tenant_id,payroll_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_expense (
 id VARCHAR(36) NOT NULL,tenant_id VARCHAR(64) NOT NULL,request_id VARCHAR(36) NOT NULL,document_no VARCHAR(50) NOT NULL,
 title VARCHAR(200) NOT NULL,type_code VARCHAR(64) NOT NULL,employee_code VARCHAR(50) NOT NULL,
 employee_name VARCHAR(128) NOT NULL,department_id BIGINT NULL,department_name VARCHAR(128) NULL,
 city_code VARCHAR(64) NOT NULL,cost_date DATE NOT NULL,amount DECIMAL(18,2) NOT NULL,
 payment_status_code VARCHAR(32) NOT NULL,status_code VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 data_json JSON NOT NULL,revision INT NOT NULL DEFAULT 1,created_by VARCHAR(50) NOT NULL,updated_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),UNIQUE KEY uk_expense_request(tenant_id,request_id),UNIQUE KEY uk_expense_no(tenant_id,document_no),
 KEY ix_expense_query(tenant_id,cost_date,employee_code,status_code),CONSTRAINT ck_expense_amount CHECK(amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_audit (
 id BIGINT NOT NULL AUTO_INCREMENT,tenant_id VARCHAR(64) NOT NULL,object_id VARCHAR(128) NOT NULL,
 action_code VARCHAR(32) NOT NULL,actor_id VARCHAR(50) NOT NULL,reason VARCHAR(500) NULL,snapshot_json JSON NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),PRIMARY KEY(id),KEY ix_payroll_audit(tenant_id,object_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- 默认提成口径来自本期确认；只是可选模板，必须关联真实商品、单位、部门和生效日期后才参与核算。
CREATE TABLE hr_commission_policy (
 policy_code VARCHAR(50) NOT NULL,policy_name VARCHAR(120) NOT NULL,method_code VARCHAR(20) NOT NULL,
 sales_rate DECIMAL(18,2) NOT NULL,manager_rate DECIMAL(18,2) NOT NULL,ordinal INT NOT NULL,
 PRIMARY KEY(policy_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
INSERT INTO hr_commission_policy VALUES
 ('INSTANT_NOODLES','快消品：泡面','PERCENT',4,1,10),
 ('BILLIARD_ACCESSORIES','台球周边：巧克粉、皮头等（除台呢）','PERCENT',10,2,20),
 ('CLOTH_A300','澳洋华彩（中式）A300','QUANTITY',15,5,30),
 ('CLOTH_A500B','澳洋华彩（中式）A500B','QUANTITY',15,5,40),
 ('CLOTH_A600','澳洋华彩（中式）A600','QUANTITY',25,10,50),
 ('CLOTH_A700','澳洋华彩（中式）A700','QUANTITY',25,10,60),
 ('CLOTH_A800','澳洋华彩（中式）A800','QUANTITY',25,10,70),
 ('CLOTH_SNOOKER_AURORA','澳洋斯诺克：极光','QUANTITY',35,10,80),
 ('CLOTH_SNOOKER_PEAK','澳洋斯诺克：极峰','QUANTITY',35,10,90),
 ('CLOTH_SNOOKER_POINT','澳洋斯诺克：极点','QUANTITY',35,10,100);

-- Source: V11__hr_employee_salary_profile.sql
-- 转正状态未知保持 NULL，由 HR 核对；不按工龄或岗位名称猜测。
ALTER TABLE hr_employee
 ADD COLUMN regularized BOOLEAN NULL COMMENT '是否转正，NULL为待完善',
 ADD COLUMN regularization_date DATE NULL COMMENT '实际转正日期',
 ADD COLUMN payroll_category VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT 'SALES销售 HEADQUARTERS总部',
 ADD COLUMN salary_effective_from DATE NULL COMMENT '档案当前薪资生效日期',
 ADD INDEX ix_hr_employee_payroll_category (tenant_id, payroll_category, deleted),
 ADD INDEX ix_hr_employee_regularized (tenant_id, regularized, deleted),
 ADD CONSTRAINT ck_hr_employee_payroll_category CHECK(payroll_category IS NULL OR payroll_category IN ('SALES','HEADQUARTERS'));

-- Source: V12__hr_move_fixed_allowances_to_salary_rules.sql
-- 已确认工资快照保持不变；将仍在规则中的固定补贴独立成其他薪资配置。
UPDATE hr_payroll_rule SET data_json=JSON_SET(data_json,'$.allowanceTypeCode','OTHER_ALLOWANCE')
WHERE kind_code='ALLOWANCE' AND JSON_EXTRACT(data_json,'$.allowanceTypeCode') IS NULL;
CREATE TEMPORARY TABLE hr_fixed_allowance_move (
 old_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci PRIMARY KEY,
 new_id VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
 vehicle DECIMAL(18,2) NOT NULL,phone DECIMAL(18,2) NOT NULL,computer DECIMAL(18,2) NOT NULL
);
INSERT INTO hr_fixed_allowance_move
SELECT id,UUID(),
 COALESCE(CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.performancePolicy.vehicleAllowance')),'null') AS DECIMAL(18,2)),0),
 COALESCE(CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.performancePolicy.phoneAllowance')),'null') AS DECIMAL(18,2)),0),
 COALESCE(CAST(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.performancePolicy.computerAllowance')),'null') AS DECIMAL(18,2)),0)
FROM hr_payroll_rule WHERE kind_code='PERFORMANCE'
AND JSON_UNQUOTE(JSON_EXTRACT(data_json,'$.performancePolicy.mode'))='SALES_KPI';
INSERT INTO hr_payroll_rule(id,tenant_id,request_id,kind_code,name,employee_code,effective_from,effective_to,enabled,data_json,revision,created_by,updated_by)
SELECT m.new_id,r.tenant_id,m.new_id,'ALLOWANCE',CONCAT(LEFT(r.name,108),'·固定补贴'),NULL,r.effective_from,r.effective_to,r.enabled,
 JSON_SET(JSON_REMOVE(r.data_json,'$.performancePolicy'),'$.requestId',m.new_id,'$.revision',NULL,
 '$.kindCode','ALLOWANCE','$.name',CONCAT(LEFT(r.name,108),'·固定补贴'),'$.amount',m.vehicle+m.phone+m.computer,
 '$.allowanceTypeCode','FIXED_ALLOWANCE','$.fixedAllowance',JSON_OBJECT('vehicleAllowance',m.vehicle,'phoneAllowance',m.phone,'computerAllowance',m.computer)),
 1,'SYSTEM','SYSTEM'
FROM hr_payroll_rule r JOIN hr_fixed_allowance_move m ON m.old_id=r.id
WHERE m.vehicle+m.phone+m.computer>0;
INSERT INTO hr_payroll_rule_department(tenant_id,rule_id,department_id)
SELECT d.tenant_id,m.new_id,d.department_id FROM hr_payroll_rule_department d
JOIN hr_fixed_allowance_move m ON m.old_id=d.rule_id JOIN hr_payroll_rule r ON r.id=m.new_id;
INSERT INTO hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)
SELECT r.tenant_id,r.id,'RULE_CREATE','SYSTEM','固定补贴从绩效规则迁移至其他薪资设置',r.data_json
FROM hr_payroll_rule r JOIN hr_fixed_allowance_move m ON m.new_id=r.id;
UPDATE hr_payroll_rule r JOIN hr_fixed_allowance_move m ON m.old_id=r.id
SET r.data_json=JSON_REMOVE(r.data_json,'$.performancePolicy.vehicleAllowance','$.performancePolicy.phoneAllowance','$.performancePolicy.computerAllowance'),
 r.revision=r.revision+1,r.updated_by='SYSTEM',r.updated_time=UTC_TIMESTAMP(6);
INSERT INTO hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)
SELECT r.tenant_id,r.id,'RULE_UPDATE','SYSTEM','固定补贴已迁出，绩效规则只处理绩效',r.data_json
FROM hr_payroll_rule r JOIN hr_fixed_allowance_move m ON m.old_id=r.id;
DROP TEMPORARY TABLE hr_fixed_allowance_move;

-- Source: V13__hr_payroll_workflow.sql
-- 月度人工补贴核定、结构化发放及跨月补差；保留既有核算快照。
ALTER TABLE hr_payroll_input ADD COLUMN allowance_override DECIMAL(18,2) NULL;
CREATE TABLE hr_payroll_payment (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, payroll_id VARCHAR(36) NOT NULL,
 request_id VARCHAR(36) NOT NULL, paid_date DATE NOT NULL, amount DECIMAL(18,2) NOT NULL,
 reference_no VARCHAR(128) NOT NULL, reason VARCHAR(500) NOT NULL, created_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_pay_payment_request(tenant_id,request_id),
 UNIQUE KEY uk_pay_payment_reference(tenant_id,payroll_id,reference_no),
 KEY ix_pay_payment_payroll(tenant_id,payroll_id), CONSTRAINT ck_pay_payment_amount CHECK(amount>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_correction (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, source_payroll_id VARCHAR(36) NOT NULL,
 source_month CHAR(7) NOT NULL, employee_code VARCHAR(50) NOT NULL, target_month CHAR(7) NOT NULL,
 request_id VARCHAR(36) NOT NULL, amount DECIMAL(18,2) NOT NULL, reason VARCHAR(500) NOT NULL,
 status_code VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', revision INT NOT NULL DEFAULT 1,
 created_by VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id), UNIQUE KEY uk_pay_correction_request(tenant_id,request_id),
 KEY ix_pay_correction_target(tenant_id,employee_code,target_month),
 KEY ix_pay_correction_source(tenant_id,source_payroll_id),
 CONSTRAINT ck_pay_correction_amount CHECK(amount<>0),
 CONSTRAINT ck_pay_correction_month CHECK(target_month>source_month),
 CONSTRAINT ck_pay_correction_status CHECK(status_code IN ('ACTIVE','VOID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V14__hr_payroll_monthly_department_override.sql
-- 工资核定仅影响指定员工、月份的核算，不改写员工档案或历史任职。
ALTER TABLE hr_payroll_input
    ADD COLUMN department_override_id BIGINT NULL COMMENT 'HR核定的当月部门';

-- Source: V15__hr_attendance_rules_and_notes.sql
-- 一期按月设置统一应出勤，实际天数只读 Sales 日期汇总；原因不改写打卡和工资。
CREATE TABLE hr_attendance_rule (
 tenant_id VARCHAR(64) NOT NULL, attendance_month CHAR(7) NOT NULL,
 expected_days INT NOT NULL, revision INT NOT NULL DEFAULT 1,
 updated_by VARCHAR(50) NOT NULL, updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,attendance_month), CONSTRAINT ck_hr_attendance_days CHECK(expected_days BETWEEN 0 AND 31)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_attendance_note (
 tenant_id VARCHAR(64) NOT NULL, attendance_month CHAR(7) NOT NULL, employee_code VARCHAR(50) NOT NULL,
 reason VARCHAR(500) NOT NULL DEFAULT '', revision INT NOT NULL DEFAULT 1,
 updated_by VARCHAR(50) NOT NULL, updated_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(tenant_id,attendance_month,employee_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_attendance_audit (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL, attendance_month CHAR(7) NOT NULL,
 employee_code VARCHAR(50) NULL, action_code VARCHAR(16) NOT NULL, expected_days INT NULL,
 reason VARCHAR(500) NULL, revision INT NOT NULL, actor_id VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), PRIMARY KEY(id),
 KEY ix_hr_attendance_audit(tenant_id,attendance_month,employee_code,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V16__hr_attendance_department_rules.sql
-- 公司默认保留在 department_id=0；部门规则只对本部门生效。
-- 部门 expected_days=NULL 表示恢复公司默认，保留版本以阻止旧页面覆盖。
ALTER TABLE hr_attendance_rule ADD COLUMN department_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE hr_attendance_rule MODIFY COLUMN expected_days INT NULL;
ALTER TABLE hr_attendance_rule DROP PRIMARY KEY;
ALTER TABLE hr_attendance_rule ADD PRIMARY KEY (tenant_id, attendance_month, department_id);
ALTER TABLE hr_attendance_rule ADD CONSTRAINT ck_hr_attendance_rule_scope
 CHECK (department_id >= 0 AND (department_id > 0 OR expected_days IS NOT NULL));

ALTER TABLE hr_attendance_audit ADD COLUMN department_id BIGINT NULL;

-- Source: V17__hr_attendance_manual_adjustments.sql
-- 人工调整独立于销售打卡来源；NULL 表示继续使用系统计算。
ALTER TABLE hr_attendance_note
    ADD COLUMN actual_days_override DECIMAL(3,1) NULL,
    ADD COLUMN absent_days_override DECIMAL(3,1) NULL,
    ADD COLUMN leave_type_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'NONE',
    ADD CONSTRAINT chk_attendance_actual_override CHECK (actual_days_override BETWEEN 0 AND 31 AND MOD(actual_days_override * 10, 5) = 0),
    ADD CONSTRAINT chk_attendance_absent_override CHECK (absent_days_override BETWEEN 0 AND 31 AND MOD(absent_days_override * 10, 5) = 0);

ALTER TABLE hr_attendance_audit
    ADD COLUMN actual_days_override DECIMAL(3,1) NULL,
    ADD COLUMN absent_days_override DECIMAL(3,1) NULL,
    ADD COLUMN leave_type_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;

-- Source: V18__hr_attendance_confirmation.sql
-- 历史人工资料仍待确认；仅明确执行确认操作才冻结结果。
ALTER TABLE hr_attendance_note
    ADD COLUMN attendance_status VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PENDING',
    ADD COLUMN confirmed_snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL,
    ADD COLUMN confirmed_department_id BIGINT NULL,
    ADD COLUMN confirmed_rule_department_id BIGINT NULL,
    ADD CONSTRAINT chk_attendance_confirmation CHECK (
        (attendance_status = 'PENDING' AND confirmed_snapshot_json IS NULL)
        OR (attendance_status = 'CONFIRMED' AND confirmed_snapshot_json IS NOT NULL)
    );
CREATE INDEX ix_attendance_status ON hr_attendance_note (tenant_id, attendance_month, attendance_status);

ALTER TABLE hr_attendance_audit
    ADD COLUMN confirmed_snapshot_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL;

CREATE INDEX ix_attendance_confirmed_department ON hr_attendance_note (tenant_id, attendance_month, confirmed_department_id);
CREATE INDEX ix_attendance_confirmed_rule ON hr_attendance_note (tenant_id, attendance_month, confirmed_rule_department_id);

CREATE TABLE hr_attendance_month_lock (
    tenant_id VARCHAR(64) NOT NULL,
    attendance_month CHAR(7) NOT NULL,
    PRIMARY KEY (tenant_id, attendance_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V19__hr_target_completions.sql
CREATE TABLE hr_target_completion (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    target_month DATE NOT NULL,
    dimension_type VARCHAR(32) NOT NULL,
    dimension_code VARCHAR(64) NOT NULL,
    metric_code VARCHAR(64) NOT NULL,
    completed_value DECIMAL(24,6) NULL COMMENT 'NULL恢复自动计算；0为手工完成零',
    completed_through DATE NOT NULL COMMENT '完成值截止日期',
    revision INT NOT NULL,
    updated_by VARCHAR(50) NOT NULL,
    updated_time DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_completion (tenant_id,target_month,dimension_type,dimension_code,metric_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='月度指标手工完成值';

CREATE TABLE hr_target_completion_event (
    id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    completion_id BIGINT NOT NULL,
    revision INT NOT NULL,
    completed_value DECIMAL(24,6) NULL,
    completed_through DATE NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    actor VARCHAR(50) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hr_completion_event (tenant_id,completion_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='指标完成值修订记录';

-- Source: V20__hr_payroll_scheme_versions.sql
CREATE TABLE hr_payroll_scheme_version (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, request_id VARCHAR(80) NOT NULL,
 scheme_id VARCHAR(36) NOT NULL, version INT NOT NULL, revision INT NOT NULL DEFAULT 0,
 status VARCHAR(20) NOT NULL, data_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL,
 created_by VARCHAR(36) NOT NULL, published_by VARCHAR(36) NULL,
 updated_time DATETIME(6) NOT NULL, published_time DATETIME(6) NULL, stop_from DATE NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_scheme_request(tenant_id,request_id),
 UNIQUE KEY uk_scheme_version(tenant_id,scheme_id,version), KEY ix_scheme_status(tenant_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_scheme_month_input (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, employee_code VARCHAR(50) NOT NULL,
 payroll_month VARCHAR(7) NOT NULL, revision INT NOT NULL DEFAULT 0, data_json JSON NOT NULL,
 updated_by VARCHAR(36) NOT NULL, updated_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_scheme_month_input(tenant_id,employee_code,payroll_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_scheme_event (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36) NOT NULL, subject_id VARCHAR(100) NOT NULL,
 event_type VARCHAR(32) NOT NULL, data_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL,
 actor VARCHAR(36) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY ix_scheme_event(tenant_id,subject_id,created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V21__hr_payroll_component_catalog.sql
-- Rules are maintained once; published schemes retain immutable component references and snapshots.
CREATE TABLE hr_payroll_component_version (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, request_id VARCHAR(80) NOT NULL,
 rule_id VARCHAR(36) NOT NULL, version INT NOT NULL, kind VARCHAR(20) NOT NULL,
 data_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL, created_by VARCHAR(50) NOT NULL,
 created_time DATETIME(6) NOT NULL,
 PRIMARY KEY (id), UNIQUE KEY uk_payroll_component_request (tenant_id,request_id),
 UNIQUE KEY uk_payroll_component_version (tenant_id,rule_id,version),
 KEY ix_payroll_component_kind (tenant_id,kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_payroll_configuration (
 tenant_id VARCHAR(64) NOT NULL, unified_from CHAR(7) NOT NULL,
 reason VARCHAR(1000) NOT NULL, updated_by VARCHAR(50) NOT NULL, updated_time DATETIME(6) NOT NULL,
 PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V22__hr_payroll_rule_business_code.sql
-- Stable business code per rule family; individual versions retain their own technical IDs.
ALTER TABLE hr_payroll_component_version
 ADD COLUMN rule_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL AFTER rule_id;
UPDATE hr_payroll_component_version v
JOIN (
 SELECT tenant_id,rule_id,CONCAT('PCR',DATE_FORMAT(first_created,'%Y%m%d'),LPAD(ROW_NUMBER() OVER(PARTITION BY tenant_id,DATE(first_created) ORDER BY rule_id),6,'0')) code
 FROM (SELECT tenant_id,rule_id,MIN(created_time) first_created FROM hr_payroll_component_version GROUP BY tenant_id,rule_id) families
) codes ON codes.tenant_id=v.tenant_id AND codes.rule_id=v.rule_id
SET v.rule_code=codes.code;
ALTER TABLE hr_payroll_component_version
 MODIFY COLUMN rule_code VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL,
 ADD UNIQUE KEY uk_payroll_component_code_version (tenant_id,rule_code,version);

-- Source: V23__hr_expense_analytics_version.sql
-- 费用写入与版本递增由同一事务提交；已有记录以版本0首次全量读取。
CREATE TABLE hr_expense_guard (
 tenant_id VARCHAR(36) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V24__payroll_rule_lifecycle.sql
ALTER TABLE hr_payroll_component_version
 ADD COLUMN revision INT NOT NULL DEFAULT 1,
 ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;

ALTER TABLE hr_attendance_note ADD COLUMN exceptions_json JSON NULL;

CREATE TABLE hr_payroll_component_operation (
 id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(64) NOT NULL,
 request_id VARCHAR(80) NOT NULL, rule_id VARCHAR(36) NOT NULL,
 version_id VARCHAR(36) NOT NULL, action_code VARCHAR(20) NOT NULL,
 effective_month CHAR(7) NULL, state_revision INT NULL,
 response_json JSON NOT NULL, reason VARCHAR(1000) NOT NULL,
 actor_id VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_component_operation_request(tenant_id,request_id),
 UNIQUE KEY uk_component_state_revision(tenant_id,rule_id,state_revision),
 KEY ix_component_operation_rule(tenant_id,rule_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE hr_payroll_component_review (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL,
 operation_id BIGINT NOT NULL, rule_id VARCHAR(36) NOT NULL,
 rule_name VARCHAR(120) NOT NULL, action_code VARCHAR(20) NOT NULL,
 subject VARCHAR(20) NOT NULL, subject_id VARCHAR(100) NOT NULL,
 employee_code VARCHAR(50) NOT NULL, review_month CHAR(7) NOT NULL,
 reason VARCHAR(1000) NOT NULL, state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 created_time DATETIME(6) NOT NULL, resolved_by VARCHAR(50) NULL,
 resolved_time DATETIME(6) NULL, resolution_reason VARCHAR(1000) NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_component_review_subject(tenant_id,operation_id,subject,subject_id),
 KEY ix_component_review_month(tenant_id,subject,review_month,state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Source: V25__expense_details_and_evidence.sql
ALTER TABLE hr_expense ADD COLUMN cost_month VARCHAR(7) NULL;
UPDATE hr_expense SET cost_month = DATE_FORMAT(cost_date, '%Y-%m') WHERE cost_month IS NULL;
CREATE INDEX idx_hr_expense_period ON hr_expense(tenant_id,cost_month,status_code,employee_code);
CREATE TABLE hr_expense_cost_link (
 tenant_id VARCHAR(64) NOT NULL, expense_id VARCHAR(36) NOT NULL,
 source_service VARCHAR(16) NOT NULL, source_id VARCHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,expense_id), UNIQUE KEY uk_expense_original(tenant_id,source_service,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE hr_expense_attachment (
 id VARCHAR(36) NOT NULL, tenant_id VARCHAR(64) NOT NULL, entry_id VARCHAR(36) NOT NULL,
 file_key VARCHAR(500) NOT NULL, file_name VARCHAR(200) NOT NULL, content_type VARCHAR(100) NOT NULL,
 file_size BIGINT NOT NULL, created_by VARCHAR(50) NOT NULL, created_time DATETIME(6) NOT NULL,
 PRIMARY KEY(id), KEY idx_expense_attachment(tenant_id,entry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

ALTER TABLE flyway_schema_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
