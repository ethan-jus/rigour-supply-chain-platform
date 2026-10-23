#!/usr/bin/env python3
"""Explicit DEV repair for source-import assignment dates. Preview and backup are mandatory.
Only a sole assignment matching the employee's current department/position can move back to
an existing entry date. Does not fabricate entry dates, close terms, or rewrite transfer history.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import pymysql
import yaml

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['preview', 'apply'])
parser.add_argument('--private-dir', type=Path, required=True)
args = parser.parse_args()
private = args.private_dir.resolve()
if private.is_relative_to(Path.cwd().resolve()):
    raise SystemExit('HR backup must be outside Git')
config = yaml.safe_load(Path('services/rg-scdp-hr/hr-server/src/main/resources/application-dev.yml').read_text())['spring']['datasource']
if '192.168.12.7:13306/rigour_hr' not in config['url']:
    raise SystemExit('Explicitly approved DEV database only')
match = re.search(r'jdbc:mysql://([^:/]+):(\d+)/([^?]+)', config['url'])
tenant = json.load(open(private / 'before.json'))['tenant']
actor = 'DEV_PAYROLL_VERIFICATION_20261021'
backup = private / 'employment-date-repair.json'
with pymysql.connect(host=match[1], port=int(match[2]), database=match[3], user=config['username'], password=config['password'], cursorclass=pymysql.cursors.DictCursor, autocommit=False) as db:
    with db.cursor() as cursor:
        if args.mode == 'preview':
            cursor.execute('SET TRANSACTION READ ONLY')
        else:
            cursor.execute('SELECT version FROM hr_organization_state WHERE tenant_id=%s FOR UPDATE', (tenant,))
        cursor.execute('''SELECT a.*,e.entry_date actual_entry
FROM hr_employee_assignment a JOIN hr_employee e ON e.tenant_id=a.tenant_id COLLATE utf8mb4_general_ci AND e.employee_code=a.employee_code COLLATE utf8mb4_general_ci
WHERE a.tenant_id=%s AND e.deleted=0 AND e.entry_date IS NOT NULL AND e.entry_date<a.effective_from
 AND a.department_id=e.department_id AND a.position_code=e.primary_position_code COLLATE utf8mb4_general_ci
 AND (SELECT COUNT(*) FROM hr_employee_assignment other WHERE other.tenant_id=a.tenant_id AND other.employee_code=a.employee_code)=1
 AND EXISTS (SELECT 1 FROM hr_organization_change c WHERE c.tenant_id=a.tenant_id AND c.object_ref=a.employee_code AND c.event_type IN ('EMPLOYEE_SOURCE_CREATE','EMPLOYEE_ROSTER_IMPORT'))
ORDER BY a.id''', (tenant,))
        rows = cursor.fetchall()
        payload = json.dumps(rows, ensure_ascii=False, default=str, sort_keys=True)
        digest = hashlib.sha256(payload.encode()).hexdigest()
        if args.mode == 'preview':
            # Never replace the evidence needed to restore an already applied repair.
            with backup.open('x', encoding='utf-8') as output:
                output.write(json.dumps(dict(digest=digest, rows=rows), ensure_ascii=False, default=str))
            backup.chmod(0o600)
            db.rollback()
            print('Source import assignment dates eligible for repair:', len(rows))
        else:
            saved = json.load(open(backup))
            if saved['digest'] != digest:
                raise SystemExit('Records changed after preview; refusing repair')
            for row in rows:
                cursor.execute('UPDATE hr_employee_assignment SET effective_from=%s,revision=revision+1 WHERE tenant_id=%s AND id=%s AND revision=%s', (row['actual_entry'], tenant, row['id'], row['revision']))
                if cursor.rowcount != 1:
                    raise RuntimeError('Assignment revision conflict')
                cursor.execute('UPDATE hr_organization_state SET version=version+1 WHERE tenant_id=%s', (tenant,))
                cursor.execute('''INSERT INTO hr_organization_change(tenant_id,version,event_type,object_ref,actor_id)
SELECT tenant_id,version,'DEV_IMPORT_DATE_REPAIR',%s,%s FROM hr_organization_state WHERE tenant_id=%s''', (row['employee_code'], actor, tenant))
                cursor.execute('''INSERT INTO hr_payroll_audit(tenant_id,object_id,action_code,actor_id,reason,snapshot_json)
VALUES(%s,%s,'DEV_IMPORT_DATE_REPAIR',%s,%s,%s)''', (tenant,str(row['id']),actor,'DEV：首次导入任职起点修正为已有入职时间；仅单段同部门同岗位，未改调动历史',json.dumps(row,default=str,ensure_ascii=False)))
            db.commit()
            print('Audited source import assignment dates repaired:', len(rows))
