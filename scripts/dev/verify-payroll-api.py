#!/usr/bin/env python3
"""DEV acceptance via the logged-in user's normal gateway permissions.
Inputs/outputs (including authentication and employee data) must stay outside Git.
setup writes explicit test inputs; verify is read-only and never confirms or pays wages.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import statistics
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('mode', choices=['setup', 'verify', 'performance'])
parser.add_argument('--private-dir', required=True, type=Path)
args = parser.parse_args()
private = args.private_dir.resolve()
if private.is_relative_to(Path.cwd().resolve()):
    raise SystemExit('Use a private directory outside Git')
auth = json.loads((private / 'http-auth.json').read_text())
headers = {k: v for k, v in auth['headers'].items() if k.lower() == 'authorization'}
headers['Content-Type'] = 'application/json'
base = 'http://localhost:5100/api/v1/'
plan = json.loads((private / 'plan.json').read_text())
backup = json.loads((private / 'fixture-backup.json').read_text())
people = {e['employee_code']: e for e in backup['hr_employee']}
reason = plan['reason']


def save(name, value):
    path = private / name
    path.write_text(json.dumps(value, ensure_ascii=False, default=str))
    path.chmod(0o600)


def call(path, body=None, method=None):
    # Browser refreshes rotate the same user's token; read it per call without logging it.
    current_auth = json.loads((private / 'http-auth.json').read_text())['headers']
    current_headers = {k:v for k,v in current_auth.items() if k.lower() == 'authorization'}
    current_headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=current_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            value = json.load(response)
            if value.get('code') not in ('OK', 'SUCCESS', '0', 0, 200, '200'):
                # Successful envelopes vary by service. A populated data field is authoritative.
                if response.status >= 400:
                    raise RuntimeError(value.get('message'))
            return value.get('data')
    except urllib.error.HTTPError as failure:
        value = json.loads(failure.read())
        raise RuntimeError(str(failure.code) + ': ' + value.get('message', 'request failed')) from None


if args.mode == 'setup':
    preparation = call('hr/payroll/preparation?month=2026-09')
    live = {e['employeeCode']: e for e in preparation['employees']}
    salary = {p['employee_id']: p for p in backup['hr_employee_profile']}
    patches = []
    for profile in plan['profiles']:
        code = profile['employeeCode']
        current = live.get(code)
        if not current:
            continue
        # Category changes are explicitly authorized; original employee dates/positions are kept.
        patch = dict(employeeCode=code, revision=current['revision'], payrollCategory=profile['category'])
        old = salary.get(people[code]['id'], {})
        regular = str(old.get('regular_salary') or '').split('+')[0]
        if regular.isdecimal() and people[code].get('entry_date'):
            # The confirmed 80% policy applies only to base pay, not performance.
            patch['probationSalary'] = format(int(regular) * 0.8, '.2f')
            patch['salaryEffectiveFrom'] = people[code].get('salary_effective_from') or str(people[code]['entry_date'])[:10]
        patches.append(patch)
    if not (private / 'profile-patches-applied.json').exists():
        for i in range(0, len(patches), 100):
            call('hr/payroll/employee-profiles', dict(employees=patches[i:i+100], reason='DEV 按现有岗位分配薪资类别，并按用户确认的底薪80%修正试用底薪；' + reason), 'PUT')
        save('profile-patches-applied.json', dict(count=len(patches)))
    print('PROFILE_PATCHES', len(patches), flush=True)
    # Only actual incumbents in the verification periods receive city assignments.
    created = []
    assignment_page = call('hr/payroll/rules?kindCode=CITY_MANAGER&page=0&size=100')
    existing_assignments = assignment_page.get('items', assignment_page.get('records', [])) if isinstance(assignment_page, dict) else assignment_page
    for assignment in plan['assignments']:
        if assignment['effectiveTo'] < '2026-09-01':
            continue
        existing = [r for r in existing_assignments if r['data']['employeeCode'] == assignment['employeeCode'] and r['data']['cityCode'] == assignment['cityCode']]
        if existing:
            created.extend(existing)
            continue
        command = dict(requestId=str(uuid.uuid4()), name='验收测试 · ' + assignment['cityName'] + '城市任职', kindCode='CITY_MANAGER', departmentIds=[], includeOwnSalesCommission=True, enabled=True, remark=reason, **assignment)
        created.append(call('hr/payroll/rules', command, 'POST'))
    save('scenario-assignments.json', created)
    print('CITY_ASSIGNMENTS', len(created), flush=True)
    metrics = {'CONTACTED_STORE_COUNT': 100, 'ORDERED_STORE_COUNT': 80, 'YANGZHANGGUI_SALES_AMOUNT': 10000, 'SALES_AMOUNT': 30000, 'RECEIPT_AMOUNT': 20000}
    for month in ['2026-09', '2026-10']:
        target = call('hr/target-settings?month=' + month)
        save('targets-before-' + month + '.json', target)
        changes = [dict(dimensionType='CITY', code=t['code'], metric=t['metric'], value=str(t['value'] if t['value'] is not None and float(t['value']) > 0 else metrics[t['metric']]), expectedRevision=t['revision']) for t in target['targets'] if t['dimensionType'] == 'CITY' and t['metric'] in metrics and (t['revision'] == 0 or t['value'] is None or float(t['value']) <= 0)]
        if changes:
            call('hr/target-settings', dict(month=month, changes=changes, reason=reason), 'PUT')
        target = call('hr/target-settings?month=' + month)
        save('targets-after-' + month + '.json', target)
        count = 0
        for profile in plan['profiles']:
            code = profile['employeeCode']
            role = profile['role']
            employee = people[code]
            if role == 'SALES' or (employee.get('leave_date') and str(employee['leave_date'])[:10] < month + '-01'):
                continue
            facts = call(f'hr/payroll/schemes/monthly-facts/{month}/{code}')
            if facts['revision'] != 0:
                # Prior successful inputs from this exact test run are idempotently kept.
                if facts.get('reason') == reason:
                    count += 1
                    continue
                raise RuntimeError('Existing monthly facts require inspection before overwrite')
            actuals = []
            if role != 'HEADQUARTERS':
                assignment = next((a for a in plan['assignments'] if a['employeeCode'] == code), None)
                for metric in ['CONTACTED_STORE_COUNT', 'ORDERED_STORE_COUNT', 'YANGZHANGGUI_SALES_AMOUNT']:
                    matches = [t for t in target['targets'] if t['dimensionType'] == 'CITY' and t['metric'] == metric and str(t['name']).removesuffix('市') == str(assignment['cityName']).removesuffix('市')]
                    if len(matches) == 1 and matches[0]['value'] is not None:
                        actuals.append(dict(code=metric, value=round(float(matches[0]['value']) * (0.8 if count % 3 == 0 else 1.1 if count % 3 == 1 else 1.5), 2), evidence='DEV 测试核定值；以真实城市目标构造80%/110%/150%边界场景；不是实际经营成果'))
            command = dict(revision=0, performanceBase=None, metrics=actuals, reason=reason, performanceCompletionPercent=[80,100,110][count % 3] if role == 'HEADQUARTERS' else None, commissionAdjustment=None)
            call(f'hr/payroll/schemes/monthly-facts/{month}/{code}', command, 'PUT')
            count += 1
        prep = call('hr/payroll/preparation?month=' + month)
        overrides = 0
        for employee in prep['employees']:
            code = employee['employeeCode']
            if not people[code].get('department_id'):
                continue
            old = call(f'hr/payroll/inputs/{month}/{code}')
            if old.get('departmentOverrideId') is not None:
                continue
            old['departmentOverrideId'] = people[code]['department_id']
            old['reason'] = 'DEV 核对：月末任职为空，以员工现有部门快照作月度核定（含离职后结算）；' + reason
            call(f'hr/payroll/inputs/{month}/{code}', old, 'PUT')
            overrides += 1
        print('MONTH_INPUTS', month, 'city targets filled', len(changes), 'HR facts', count, 'department overrides', overrides, flush=True)
    save('setup-summary.json', dict(profilePatches=len(patches), assignments=len(created), reason=reason))

elif args.mode == 'verify':
    result = {}
    for month in ['2026-09', '2026-10']:
        started = time.monotonic()
        preparation = call('hr/payroll/preparation?month=' + month)
        result[month + '-preparation'] = preparation
        rows = []
        def verify(person):
            began = time.monotonic()
            try:
                value = call('hr/payroll/preview', dict(month=month, employeeCodes=[person['employeeCode']]), 'POST')[0]
                return dict(**value, elapsedMs=round((time.monotonic()-began)*1000, 2))
            except Exception as error:
                return dict(employeeCode=person['employeeCode'], failure=str(error), elapsedMs=round((time.monotonic()-began)*1000, 2))
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            for index, row in enumerate(pool.map(verify, preparation['employees'])):
                rows.append(row)
                if (index+1) % 25 == 0: print('READ_ONLY_PROGRESS', month, index+1, flush=True)
        result[month+'-calculations'] = rows
        save('actual-verification.json', result)
        print('READ_ONLY_MONTH', month, 'employees', len(rows), 'elapsedSeconds', round(time.monotonic()-started, 2), 'failures', sum('failure' in r for r in rows), flush=True)

else:
    latencies = []
    errors = []
    def measure(index):
        start = time.monotonic()
        try:
            call('hr/payroll/preparation?month=2026-09')
            return round((time.monotonic()-start)*1000, 2), None
        except Exception as error:
            return round((time.monotonic()-start)*1000, 2), str(error)
    started = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for elapsed, error in pool.map(measure, range(20)):
            latencies.append(elapsed)
            if error: errors.append(error)
    ordered = sorted(latencies)
    summary = dict(endpoint='GET preparation', concurrentUsers=4, requests=20, errors=len(errors), medianMs=statistics.median(latencies), p95Ms=ordered[18], maxMs=max(latencies), elapsedSeconds=round(time.monotonic()-started,2))
    save('performance.json',dict(summary=summary,latenciesMs=latencies,failures=errors))
    print('PERFORMANCE', json.dumps(summary), flush=True)
