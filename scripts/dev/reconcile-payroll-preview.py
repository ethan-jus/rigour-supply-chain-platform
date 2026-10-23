#!/usr/bin/env python3
"""Independently reconcile saved read-only previews using Decimal arithmetic.

No network, database writes, payroll confirmation or payment. Private input/output only.
Checks the configured calendar-day DEV scenarios, not unspecified formal salary policies.
"""
import argparse
import calendar
import collections
import csv
import datetime as dt
import json
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--private-dir', type=Path, required=True)
args = parser.parse_args()
private = args.private_dir.resolve()
if private.is_relative_to(Path.cwd().resolve()):
    raise SystemExit('Employee evidence must remain outside Git')
data = json.loads((private / 'actual-verification.json').read_text(), parse_float=Decimal)
checks = collections.Counter()
failures = []
summaries = {}
export = []


def number(value):
    return Decimal(str(value))


def money(value):
    return number(value).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)


def date(value):
    return dt.date.fromisoformat(value) if value else None


def check(label, actual, expected, context):
    checks[label] += 1
    if actual is None or abs(number(actual) - number(expected)) > Decimal('.01'):
        failures.append(dict(check=label, context=context, actual=actual, expected=expected))


for month in ('2026-09', '2026-10'):
    people = {e['employeeCode']: e for e in data[month + '-preparation']['employees']}
    counts = collections.Counter()
    year, month_number = map(int, month.split('-'))
    days = [dt.date(year, month_number, day) for day in range(1, calendar.monthrange(year, month_number)[1]+1)]
    for row in data[month + '-calculations']:
        context = month + '/' + row['employeeCode']
        if 'failure' in row:
            failures.append(dict(check='HTTP', context=context, actual=row['failure']))
            continue
        c = row['calculation']
        s = c.get('scheme')
        if not s:
            failures.append(dict(check='SCHEME_SNAPSHOT', context=context))
            continue
        definition = s['scheme']['definition']
        role = definition['role']
        counts[role + (':complete' if not row['issues'] else ':pending')] += 1
        employee = people[row['employeeCode']]
        entry, leave = date(employee['entryDate']), date(employee['leaveDate'])
        employed = [d for d in days if entry and d >= entry and (not leave or d <= leave)]
        personal = definition.get('personalPolicy') or {}
        probation_end = date(personal.get('probationUntil'))
        changed = date(personal.get('salaryChangeDate'))
        base = definition['baseSalary']
        base_amount = base.get('fixedAmount')
        if base['mode'] == 'TIERS':
            matches = [b for b in base['tiers'] if b['code'] == s.get('salaryTier')]
            base_amount = matches[0]['amount'] if len(matches) == 1 else None
        if entry and base_amount is not None and definition['proration']['base'] == 'CALENDAR_DAYS':
            daily = []
            for day in employed:
                amount = personal.get('baseAfterChange') if changed and day >= changed else base_amount
                multiplier = number(personal.get('probationBasePercent', 100))/100 if probation_end and day < probation_end else Decimal(1)
                daily.append(number(amount)*multiplier)
            check('base_with_probation', c['baseSalary'], money(sum(daily)/len(days)), context)
        factor = number(len(employed))/len(days)
        if role == 'HEADQUARTERS' and s.get('performanceCompletionPercent') is not None and c['performance'] is not None:
            check('headquarters_personal_completion', c['performance'], money(number(s['performanceBase'])*number(s['performanceCompletionPercent'])/100*factor), context)
        if role in ('CITY_MANAGER', 'TRAINEE_MANAGER') and c['performance'] is not None and s.get('managerCoefficient') is not None:
            contributions = []
            for metric in s['metrics']:
                if metric.get('completion') is None:
                    continue
                ratio = number(metric['actual'])/number(metric['target'])
                check('city_metric_completion', metric['completion'], ratio, context+'/'+metric['code'])
                check('city_metric_weight', metric['contribution'], number(metric['coefficient'])*number(metric['weight'])/100, context+'/'+metric['code'])
                contributions.append(number(metric['contribution']))
            if s['assessedDays']:
                check('city_weighted_total', s['managerCoefficient'], sum(contributions), context)
            else:
                check('city_no_assessment_weight', s['managerCoefficient'], 1, context)
            payout = number(s['performanceBase'])*(number(s['protectedDays'])+number(s['assessedDays'])*number(s['managerCoefficient']))/len(days)
            check('city_protection_weighting', c['performance'], money(payout), context)
        p = c.get('performanceDetail')
        if p:
            policy = definition['sales']
            for day in p['days']:
                check('daily_checkin_cap', day['missingCheckins'], max(0, policy['dailyCheckins']-day['checkins']), context+'/'+day['date'])
                check('daily_visit_cap', day['missingVisits'], max(0, policy['dailyVisits']-day['effectiveVisits']), context+'/'+day['date'])
                if day['deduction'] is not None:
                    check('daily_process_deduction', day['deduction'], number(day['missingCheckins'])*number(policy['checkinPenalty'])+number(day['missingVisits'])*number(policy['visitPenalty']), context+'/'+day['date'])
            if p['processPay'] is not None:
                check('process_budget_cap', p['processPay'], number(p['processBudget'])-min(number(p['processBudget']), sum(number(d['deduction']) for d in p['days'] if d['deduction'] is not None)), context)
            if p['collectionPay'] is not None and p['collectionTarget'] is not None:
                exempt = entry and entry.strftime('%Y-%m') == month and policy['firstMonthExempt']
                ratio = Decimal(1) if exempt else min(Decimal(1), number(p['collections'])/number(p['collectionTarget'])) if number(p['collectionTarget']) > 0 else None
                if ratio is not None:
                    check('collection_budget_cap', p['collectionPay'], money(number(p['collectionBudget'])*ratio), context)
            if p['processPay'] is not None and p['collectionPay'] is not None and c['performance'] is not None:
                check('sales_prorated_performance', c['performance'], money((number(p['processPay'])+number(p['collectionPay']))*factor), context)
        seen = set()
        for detail in c['details']:
            trace = detail.get('trace') or {}
            key = (detail['sourceLineId'], detail['recipientRoleCode'])
            checks['commission_unique_recipient'] += 1
            if key in seen:
                failures.append(dict(check='commission_unique_recipient', context=context))
            seen.add(key)
            if detail['released'] is not None and trace.get('baseAmount') is not None and trace.get('rate') is not None:
                expected = number(trace['baseAmount'])*number(trace['rate'])/(100 if trace['method'] == 'PERCENT' else 1)
                check('commission_line', detail['accrued'], money(expected), context+'/'+detail['sourceLineId'])
        if c['commission'] is not None:
            check('commission_total', c['commission'], sum(number(d['released']) for d in c['details'] if d['released'] is not None), context)
        amounts = [c[k] for k in ('baseSalary', 'performance', 'allowance', 'commission', 'correctionAmount')]
        if all(v is not None for v in amounts):
            check('gross_components', c['grossPay'], sum(map(number, amounts)), context)
            check('net_components', c['netPay'], number(c['grossPay'])-number(c['deduction']), context)
        else:
            checks['missing_amount_stays_pending'] += 1
            if c['grossPay'] is not None or c['netPay'] is not None or not row['issues']:
                failures.append(dict(check='missing_amount_stays_pending', context=context))
        export.append(dict(month=month, employeeCode=row['employeeCode'], employeeName=row['employeeName'], role=role,
                           base=c['baseSalary'], performance=c['performance'], allowance=c['allowance'], commission=c['commission'], payable=c['netPay'],
                           policySource='DEV marked test salary policy; original business facts', issues='；'.join(row['issues'])))
    summaries[month] = dict(counts)

result = dict(months=summaries, checks=dict(checks), totalChecks=sum(checks.values()), failures=failures)
output = private / 'independent-reconciliation.json'
output.write_text(json.dumps(result, default=str, ensure_ascii=False))
output.chmod(0o600)
with (private / 'employee-payroll-reconciliation.csv').open('w', encoding='utf-8-sig', newline='') as output:
    writer = csv.DictWriter(output, fieldnames=list(export[0]))
    writer.writeheader()
    writer.writerows(export)
(private / 'employee-payroll-reconciliation.csv').chmod(0o600)
print(json.dumps(dict(months=summaries, totalChecks=sum(checks.values()), failures=len(failures)), ensure_ascii=False))
raise SystemExit(bool(failures))
