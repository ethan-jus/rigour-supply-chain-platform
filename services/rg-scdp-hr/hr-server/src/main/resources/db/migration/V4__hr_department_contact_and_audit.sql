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
