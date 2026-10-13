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
