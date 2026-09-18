#!/usr/bin/env python3
"""Sales 业务关联维护：各源独立只读连接，仅 Sales 自有表写入；不参与登录或授权。

默认预览。apply 必须给出预览摘要；重新读取源数据后摘要不一致则拒绝写入。
已有关联永久按固定编码保留，不随改名、离职、调城自动重连，也不会隐式覆盖。
依赖 PyMySQL；连接通过 SALES/HR/CRM_DB_{HOST,PORT,NAME,USER,PASSWORD} 环境变量传入。
"""
import argparse
from collections import Counter, defaultdict
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import json
import os
import uuid

RULE = 'EXACT_NAME_CITY_V1'
STATUS = {'在职': 'ACTIVE', '离职': 'LEFT', '停用': 'INACTIVE',
          'ACTIVE': 'ACTIVE', 'LEFT': 'LEFT', 'INACTIVE': 'INACTIVE'}


def clean(value):
    """仅去首尾空白，保留大小写、标点、分店名、行政区及中间空格。"""
    return str(value).strip() if value is not None else ''


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                     default=str, separators=(',', ':')).encode()).hexdigest()


def index(rows, key):
    result = defaultdict(list)
    for row in rows:
        result[key(row)].append(row)
    return result


def candidates(kind, source, targets, existing):
    """双方唯一才关联；已有固定关联优先，冲突和目标已占用只进入复核清单。"""
    person = kind == 'employee'
    key = (lambda r: clean(r['name'])) if person else (lambda r: (clean(r['name']), clean(r['city'])))
    source_index, target_index = index(source, key), index(targets, key)
    bound = {r['source_id']: r for r in existing}
    occupied = {str(r['target_id']) for r in existing}
    eligible, review = [], []
    for row in source:
        if row['source_id'] in bound:
            continue
        name, city = clean(row['name']), clean(row['city'])
        matches = target_index[key(row)]
        target = matches[0] if len(matches) == 1 else None
        reason = None
        if not name or not city:
            reason = 'MISSING_NAME_OR_CITY'
        elif not matches:
            reason = 'NO_TARGET_MATCH'
        elif len(matches) != 1:
            reason = 'MULTIPLE_TARGETS'
        elif len(source_index[key(row)]) != 1:
            reason = 'MULTIPLE_SOURCES'
        elif not clean(target['city']) or city != clean(target['city']):
            reason = 'CITY_CONFLICT'
        elif str(target['target_id']) in occupied:
            reason = 'TARGET_ALREADY_LINKED'
        elif not clean(target.get('target_code')):
            reason = 'MISSING_TARGET_CODE'
        elif person:
            if STATUS.get(clean(row['employment_status'])) != target['employment_status'] or target['employment_status'] not in STATUS.values():
                reason = 'EMPLOYMENT_STATUS_CONFLICT'
        elif row['status'] != 'ACTIVE' or target['status'] != 'ACTIVE':
            reason = 'INACTIVE_STORE_OR_CUSTOMER'
        else:
            phone, target_phone = clean(row['contact_phone']), clean(target['contact_phone'])
            addresses = {clean(row.get(k)) for k in ('location_address', 'location_formatted_address', 'source_poi_address')} - {''}
            address = clean(target['address'])
            if phone and target_phone and phone != target_phone:
                reason = 'PHONE_CONFLICT'
            elif address and addresses and address not in addresses:
                reason = 'ADDRESS_CONFLICT'
        evidence = {'source_name': row['name'], 'source_city': row['city'],
                    'source_digest': digest(row), 'target_digest': digest(target) if target else None}
        if target:
            evidence.update(target_name=target['name'], target_city=target['city'])
            if person:
                evidence.update(source_employment_status=row['employment_status'],
                                target_employment_status=target['employment_status'])
            else:
                evidence.update(phone_corroborated=bool(clean(row['contact_phone']) and clean(target['contact_phone'])),
                                address_corroborated=bool(clean(target['address']) and any(clean(row.get(k)) for k in ('location_address', 'location_formatted_address', 'source_poi_address'))))
        item = {'source_id': row['source_id'], 'name': name, 'city': city,
                'target_id': target['target_id'] if target else None,
                'target_code': target['target_code'] if target else None,
                'evidence': evidence}
        if reason:
            review.append(dict(item, reason=reason, candidate_targets=[
                {k: candidate[k] for k in ('target_id', 'target_code', 'name', 'city')}
                for candidate in matches]))
        else:
            eligible.append(item)
    return {'eligible': eligible, 'review': review, 'existing': len(existing)}


def query(conn, sql, tenant):
    with conn.cursor() as cursor:
        cursor.execute(sql, (tenant,))
        return cursor.fetchall()


def read_sources(sales, hr, crm, tenant, lock=False):
    suffix = ' FOR UPDATE' if lock else ''
    return {
        'people': query(sales, "SELECT BIN_TO_UUID(id) source_id,name,city,employment_status FROM temp_sales_checkin_salesperson WHERE tenant_id=UUID_TO_BIN(%s) ORDER BY id" + suffix, tenant),
        'stores': query(sales, "SELECT BIN_TO_UUID(id) source_id,name,city,status,contact_phone,location_address,location_formatted_address,source_poi_address FROM temp_sales_checkin_store WHERE tenant_id=UUID_TO_BIN(%s) ORDER BY id" + suffix, tenant),
        'hr': query(hr, "SELECT employee_code target_id,employee_code target_code,employee_name name,city_name city,employment_status FROM hr_employee WHERE tenant_id=%s AND deleted=0 ORDER BY employee_code", tenant),
        'crm': query(crm, "SELECT id target_id,customer_code target_code,customer_name name,city_name city,status_code status,address,contact_phone FROM crm_customer WHERE tenant_id=%s AND deleted=0 ORDER BY id", tenant),
        'employee_links': query(sales, "SELECT BIN_TO_UUID(salesperson_id) source_id,employee_code target_id FROM temp_sales_checkin_employee_link WHERE tenant_id=UUID_TO_BIN(%s) ORDER BY salesperson_id" + suffix, tenant),
        'customer_links': query(sales, "SELECT BIN_TO_UUID(store_id) source_id,customer_id target_id FROM temp_sales_checkin_customer_link WHERE tenant_id=UUID_TO_BIN(%s) ORDER BY store_id" + suffix, tenant),
    }


def build_plan(tenant, rows):
    return {'tenant_id': tenant, 'rule': RULE, 'input_digest': digest({'tenant': tenant, 'rule': RULE, 'rows': rows}),
            'employee': candidates('employee', rows['people'], rows['hr'], rows['employee_links']),
            'customer': candidates('customer', rows['stores'], rows['crm'], rows['customer_links'])}


def apply_plan(conn, plan, expected_digest, actor):
    """调用方持有 Sales 源行和关联行锁；INSERT 冲突即回滚，不使用 IGNORE/覆盖更新。"""
    if plan['input_digest'] != expected_digest:
        raise ValueError('源数据或已有关联已变化，请重新预览')
    batch = str(uuid.uuid4())
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    with conn.cursor() as cursor:
        for kind in ('employee', 'customer'):
            for row in plan[kind]['eligible']:
                common = (plan['tenant_id'], row['source_id'])
                audit = (RULE, json.dumps(row['evidence'], ensure_ascii=False), batch, actor, now)
                if kind == 'employee':
                    cursor.execute('INSERT INTO temp_sales_checkin_employee_link (tenant_id,salesperson_id,employee_code,match_rule,evidence_json,batch_id,linked_by,linked_at) VALUES (UUID_TO_BIN(%s),UUID_TO_BIN(%s),%s,%s,%s,%s,%s,%s)', common + (row['target_code'],) + audit)
                else:
                    cursor.execute('INSERT INTO temp_sales_checkin_customer_link (tenant_id,store_id,customer_id,customer_code,match_rule,evidence_json,batch_id,linked_by,linked_at) VALUES (UUID_TO_BIN(%s),UUID_TO_BIN(%s),%s,%s,%s,%s,%s,%s,%s)', common + (row['target_id'], row['target_code']) + audit)
    return batch


def connect(domain):
    import pymysql
    prefix = domain.upper() + '_DB_'
    return pymysql.connect(host=os.environ[prefix + 'HOST'], port=int(os.environ.get(prefix + 'PORT', '3306')),
                           database=os.environ[prefix + 'NAME'], user=os.environ[prefix + 'USER'],
                           password=os.environ[prefix + 'PASSWORD'], charset='utf8mb4', autocommit=False,
                           cursorclass=pymysql.cursors.DictCursor, connect_timeout=10, read_timeout=60)


def private_report(path, value):
    # 审核文件包含业务名称和编码，禁止写进 Git；权限不依赖当前 umask。
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(value, output, ensure_ascii=False, indent=2, default=str)


def run(tenant, report, apply=False, expected_digest=None, actor=None):
    tenant = str(uuid.UUID(tenant))
    if apply and (not expected_digest or not actor or len(actor) > 128):
        raise ValueError('apply 需要 --expected-digest 和 --actor（最长 128 字符）')
    with ExitStack() as stack:
        sales, hr, crm = (stack.enter_context(connect(d)) for d in ('sales', 'hr', 'crm'))
        # 目标库强制只读；只在 Sales 自有库进行一次事务写入。
        for conn in (hr, crm):
            with conn.cursor() as cursor:
                cursor.execute('SET TRANSACTION READ ONLY')
                cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
        if apply:
            with sales.cursor() as cursor:
                cursor.execute('SELECT GET_LOCK(%s, 10) locked', ('sales-business-links:' + tenant,))
                if cursor.fetchone()['locked'] != 1:
                    raise ValueError('其他关联维护正在运行')
        try:
            rows = read_sources(sales, hr, crm, tenant, lock=apply)
            plan = build_plan(tenant, rows)
            plan['checked_at'] = datetime.now(timezone.utc).isoformat()
            plan['applied'] = False
            if apply:
                plan['batch_id'] = apply_plan(sales, plan, expected_digest, actor)
                # 先落审核依据，再提交。文件写入失败时数据库也不会提交。
                private_report(report, plan)
                sales.commit()
                plan['applied'] = True
            private_report(report, plan)
        except Exception:
            sales.rollback()
            raise
        finally:
            if apply:
                with sales.cursor() as cursor:
                    cursor.execute('SELECT RELEASE_LOCK(%s)', ('sales-business-links:' + tenant,))
        summary = {'input_digest': plan['input_digest'], 'applied': plan['applied']}
        for kind in ('employee', 'customer'):
            value = plan[kind]
            summary[kind] = {'eligible': len(value['eligible']), 'existing': value['existing'],
                             'review': dict(Counter(r['reason'] for r in value['review']))}
        return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tenant-id', required=True)
    parser.add_argument('--report', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--expected-digest')
    parser.add_argument('--actor')
    args = parser.parse_args()
    print(json.dumps(run(args.tenant_id, args.report, args.apply, args.expected_digest, args.actor), ensure_ascii=False))
