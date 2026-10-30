#!/usr/bin/env python3
"""Verify missing classifications against exact POIs, then backfill with an audit trail."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import math
from pathlib import Path


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


review = load('store_classification_review', 'recheck-billiards-import.py')
classify = load('store_classification_backfill', 'backfill-billiards-classifications.py')
imp = classify.imp
REASONS = {**review.REASONS,
           'EXACT_POI_CLASSIFICATION': 'POI、门店名称、行政区划、位置及分类编码核对一致',
           'POI_NAME_CHANGED': '同一POI名称已改变，需核实是否仍为原门店',
           'POI_LOCATION_MISSING': '高德未提供可用位置，无法核对门店身份',
           'INVALID_API_COORDINATES': '高德返回坐标无效，无法核对门店位置',
           'ADMIN_CODE_CHANGED': '高德行政区划与门店记录不一致，需核验',
           'UNMAPPED_CATEGORY': '高德分类尚未纳入已发布的分类字典',
           'CATEGORY_MISSING': '高德未返回可用分类',
           'CATEGORY_CODE_CONFLICT': '高德分类名称与分类编码不一致，需核验'}


def decide(store, response, catalog):
    evidence = {'method': 'AMAP_POI_DETAIL', 'poiId': store['poi'],
                'checkedAt': response.get('checkedAt') if response else None}

    def pending(reason):
        return {'storeId': store['storeId'], 'revision': store['revision'], 'name': store['name'],
                'poi': store['poi'], 'decision': 'PENDING', 'reason': reason,
                'storeTypeCode': None, 'verification': evidence}

    if not response or response.get('response', {}).get('status') != '1':
        return pending('API_NOT_VERIFIED')
    pois = [p for p in response['response'].get('pois', []) if p.get('id') == store['poi']]
    if len(pois) != 1:
        return pending('POI_NOT_FOUND')
    poi = pois[0]
    evidence.update({key: poi.get(key) for key in ['name', 'type', 'typecode', 'location', 'adcode']})
    if review.prepare.key(poi.get('name')) != review.prepare.key(store['name']):
        return pending('POI_NAME_CHANGED')
    if not isinstance(poi.get('location'), str) or not poi['location'].strip():
        return pending('POI_LOCATION_MISSING')
    try:
        lng, lat = map(float, poi['location'].split(','))
        if not (math.isfinite(lng) and math.isfinite(lat) and -180 <= lng <= 180 and -90 <= lat <= 90):
            return pending('INVALID_API_COORDINATES')
    except ValueError:
        return pending('INVALID_API_COORDINATES')
    if str(poi.get('adcode')) != store['adminAreaCode']:
        return pending('ADMIN_CODE_CHANGED')
    record = {'data': {'门店名称': store['name'], '地址': store['address'],
                      '经纬度': f"{store['longitude']},{store['latitude']}",
                      '行政区划代码': store['adminAreaCode'], '高德 POI ID': store['poi']}}
    updated, verified, blocked = review.enrich(record, response)
    evidence['positionDifferenceMetres'] = verified.get('positionDifferenceMetres')
    if blocked:
        return pending(blocked)
    category = updated['data'].get('高德分类')
    categories = [value.strip() for value in (category or '').split('|') if value.strip()]
    # An explicit published billiards category remains usable when a secondary business type is unmapped.
    billiards = next((value for value in categories if value.endswith(';台球厅')
                      and catalog['sourcePaths'].get(value) == 'POI_080113'), None)
    try:
        code = catalog['sourcePaths'][billiards] if billiards else classify.classification(category, catalog['sourcePaths'])
    except ValueError:
        return pending('UNMAPPED_CATEGORY')
    if code is None:
        return pending('CATEGORY_MISSING')
    provider_codes = poi.get('typecode')
    codes = provider_codes.split('|') if isinstance(provider_codes, str) else []
    if len(codes) != len(categories) or not any(catalog['sourcePaths'].get(path) == code == 'POI_' + provider.strip()
                                               for path, provider in zip(categories, codes)):
        return pending('CATEGORY_CODE_CONFLICT')
    state, reason = review.decide(updated)
    if state != 'CANDIDATE':
        return pending(reason)
    # Another named sport without an explicit billiards category is not a reliable hall classification.
    if code in {'POI_080102', 'POI_080107', 'POI_080115'}:
        return pending('VENUE_CATEGORY_CONFLICT')
    return {**pending('EXACT_POI_CLASSIFICATION'), 'decision': 'READY', 'storeTypeCode': code}


def build(directory, catalog):
    targets = json.loads((directory / 'targets.json').read_text())
    responses = {}
    for line in (directory / 'api-responses.jsonl').open():
        row = json.loads(line)
        responses[row['poiId']] = row
    decisions = [decide(store, responses.get(store['poi']), catalog) for store in targets]
    report = {'targetStores': len(targets), 'queriedPois': len(set(responses) & {s['poi'] for s in targets}),
              'decisions': dict(Counter(r['decision'] for r in decisions)),
              'byReason': dict(Counter(r['reason'] for r in decisions)),
              'byCategory': dict(Counter(r['storeTypeCode'] for r in decisions if r['decision'] == 'READY')),
              'targetSha256': hashlib.sha256((directory / 'targets.json').read_bytes()).hexdigest(),
              'apiSha256': hashlib.sha256((directory / 'api-responses.jsonl').read_bytes()).hexdigest()}
    (directory / 'classification-decisions.json').write_text(json.dumps(decisions, ensure_ascii=False, indent=2))
    (directory / 'classification-plan.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    return targets, decisions, report


def apply(connection, decisions, tenant, actor, allowed_codes, batch):
    """Only null classifications at the reviewed revision may change. Review cases stay unclassified."""
    counts = Counter()
    with connection.cursor() as q:
        q.execute('SELECT GET_LOCK(%s,0)', ('crm-file-import:' + tenant,))
        if q.fetchone()[0] != 1:
            raise ValueError('Another tenant import is running')
    try:
        with connection.cursor() as q:
            q.execute('CREATE TEMPORARY TABLE classification_recheck_stage(store_id BIGINT PRIMARY KEY,code VARCHAR(32),revision BIGINT) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci')
        connection.commit()
        for start in range(0, len(decisions), 250):
            part = decisions[start:start + 250]
            placeholders = ','.join(['%s'] * len(part))
            connection.begin()
            try:
                with connection.cursor() as q:
                    q.execute(f'SELECT id,store_type_code,revision FROM crm_store_master WHERE tenant_id=%s AND id IN ({placeholders}) FOR UPDATE', (tenant, *[row['storeId'] for row in part]))
                    current = {sid: (code, revision) for sid, code, revision in q.fetchall()}
                    q.execute(f"SELECT CAST(external_id AS CHAR),store_id FROM crm_store_identity WHERE tenant_id=%s AND provider_code='AMAP' AND identity_scope='CN' AND external_id IN ({placeholders}) FOR UPDATE", (tenant, *[row['poi'] for row in part]))
                    identities = dict(q.fetchall())
                    q.execute(f'SELECT request_id FROM crm_store_event WHERE tenant_id=%s AND request_id IN ({placeholders})', (tenant, *[batch + ':' + str(row['storeId']) for row in part]))
                    recorded = {row[0] for row in q.fetchall()}
                    updates, events = [], []
                    for row in part:
                        sid = row['storeId']
                        existing = current.get(sid)
                        if existing is None:
                            raise ValueError('Target store no longer exists')
                        if existing[0]:
                            counts['existingClassificationPreserved'] += 1
                            continue
                        if existing[1] != row['revision']:
                            counts['concurrentChangeSkipped'] += 1
                            continue
                        if identities.get(row['poi']) != sid:
                            counts['identityChangeSkipped'] += 1
                            continue
                        request_id = batch + ':' + str(sid)
                        if request_id in recorded:
                            counts['alreadyRecorded'] += 1
                            continue
                        ready = row['decision'] == 'READY'
                        if ready:
                            if row['storeTypeCode'] not in allowed_codes:
                                raise ValueError('Classification is not published in the dictionary')
                            updates.append((sid, row['storeTypeCode'], row['revision']))
                        after = {'storeTypeCode': row['storeTypeCode'], 'classificationReview': row['decision'],
                                 'reasonCode': row['reason'], 'verification': row['verification'], 'batchRef': batch}
                        reason = ('按准确POI核验补齐行业分类；未关联历史客户档案' if ready
                                  else '分类待核验：' + REASONS.get(row['reason'], row['reason']))
                        events.append((imp.stable_id('classification-recheck-event', tenant, request_id), tenant, sid, sid,
                                       'SOURCE_CLASSIFICATION_APPLIED' if ready else 'SOURCE_CLASSIFICATION_REVIEW_REQUIRED', actor,
                                       '{"storeTypeCode":null}', json.dumps(after, ensure_ascii=False), reason, request_id))
                        counts['updatedStores' if ready else 'pendingReviewRecorded'] += 1
                    q.execute('DELETE FROM classification_recheck_stage')
                    if updates:
                        imp.insert_many(q, 'INSERT INTO classification_recheck_stage(store_id,code,revision) VALUES(%s,%s,%s)', updates)
                        q.execute("UPDATE crm_store_master m JOIN classification_recheck_stage s ON s.store_id=m.id SET m.store_type_code=s.code,m.revision=m.revision+1,m.updated_at=UTC_TIMESTAMP(6),m.updated_by=%s WHERE m.tenant_id=%s AND m.revision=s.revision AND (m.store_type_code IS NULL OR m.store_type_code='')", (actor, tenant))
                        if q.rowcount != len(updates):
                            raise ValueError('Concurrent store modification')
                    imp.insert_many(q, "INSERT INTO crm_store_event(id,tenant_id,store_id,object_type,object_id,event_type,actor_user_id,occurred_at,before_data,after_data,reason,request_id,event_seq) VALUES(%s,%s,%s,'STORE',%s,%s,%s,UTC_TIMESTAMP(6),%s,%s,%s,%s,1)", events)
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return dict(counts)
    finally:
        with connection.cursor() as q:
            q.execute('DROP TEMPORARY TABLE IF EXISTS classification_recheck_stage')
            q.execute('SELECT RELEASE_LOCK(%s)', ('crm-file-import:' + tenant,))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--catalog', type=Path, default=Path('reference-data/store-classifications/amap-initial-classifications.json'))
    parser.add_argument('--config'); parser.add_argument('--foundation-config')
    parser.add_argument('--tenant'); parser.add_argument('--actor')
    parser.add_argument('--batch', default='CLASSIFY_POI_20261029')
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    targets, decisions, report = build(args.directory, json.loads(args.catalog.read_text()))
    print(json.dumps(report, ensure_ascii=False), flush=True)
    if args.apply:
        if report['queriedPois'] != report['targetStores']:
            parser.error('Complete the POI verification before applying the batch')
        if not all([args.config, args.foundation_config, args.tenant, args.actor]):
            parser.error('Database configurations, tenant and actor are required')
        foundation = imp.admin_import.connect(args.foundation_config)
        try:
            with foundation.cursor() as q:
                q.execute("SELECT dictionary_item_code FROM data_dictionary_item WHERE dictionary_code='CRM_STORE_TYPE' AND deleted=0 AND enabled=1 AND tenant_key IN ('GLOBAL',%s)", (args.tenant,))
                allowed = {r[0] for r in q.fetchall()}
        finally:
            foundation.close()
        connection = imp.admin_import.connect(args.config)
        try:
            with connection.cursor() as q:
                q.execute('SELECT DATABASE()')
                if q.fetchone()[0] != 'rigour_crm':
                    raise ValueError('CRM database required')
            applied = apply(connection, decisions, args.tenant, args.actor, allowed, args.batch)
            (args.directory / 'classification-applied.json').write_text(json.dumps(applied, ensure_ascii=False, indent=2))
            print(json.dumps(applied, ensure_ascii=False))
        finally:
            connection.close()
