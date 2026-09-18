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
