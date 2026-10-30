#!/usr/bin/env python3
"""Backfill missing store industry codes from verified source paths, with per-store audit."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

spec=importlib.util.spec_from_file_location('importer',Path(__file__).with_name('import-billiards-stores.py'))
imp=importlib.util.module_from_spec(spec);spec.loader.exec_module(imp)


def classification(category, paths):
    values=[value.strip() for value in (category or '').split('|') if value.strip()]
    if not values: return None
    if any(value not in paths for value in values): raise ValueError('Unmapped source classification')
    return next((paths[value] for value in values if value.endswith(';台球厅')),paths[values[0]])


def apply(connection, directory, catalog, tenant, actor, allowed_codes):
    manifest=json.loads((directory/'manifest.json').read_text())
    if hashlib.sha256((directory/'accepted.jsonl').read_bytes()).hexdigest()!=manifest['acceptedSha256']:
        raise ValueError('Source batch hash mismatch')
    candidates=[]; report=Counter()
    with (directory/'accepted.jsonl').open() as source:
        for line in source:
            row=json.loads(line);code=classification(row.get('sourceCategory'),catalog['sourcePaths'])
            if code is None:report['sourceCategoryMissing']+=1;continue
            if code not in allowed_codes:raise ValueError('Dictionary migration has not published the requested code')
            candidates.append((row,code))
    with connection.cursor() as q:
        q.execute('SELECT GET_LOCK(%s,0)',('crm-file-import:'+tenant,))
        if q.fetchone()[0]!=1:raise ValueError('Another import holds the tenant lock')
    try:
        with connection.cursor() as q:
            q.execute("SELECT CAST(external_id AS CHAR),store_id FROM crm_store_identity WHERE tenant_id=%s AND provider_code='AMAP' AND identity_scope='CN'",(tenant,))
            identities=dict(q.fetchall())
            q.execute('CREATE TEMPORARY TABLE classification_stage(store_id BIGINT PRIMARY KEY,code VARCHAR(32)) ENGINE=InnoDB')
        connection.commit()
        for start in range(0,len(candidates),1000):
            part=candidates[start:start+1000]; rows={}
            for row,code in part:
                sids={identities[i['poi']] for i in row['identities'] if i['poi'] in identities}
                if len(sids)!=1:raise ValueError('Expected exactly one existing store identity')
                sid=next(iter(sids));rows[sid]=(row,code)
            connection.begin()
            try:
                with connection.cursor() as q:
                    q.execute('DELETE FROM classification_stage')
                    imp.insert_many(q,'INSERT INTO classification_stage(store_id,code) VALUES(%s,%s)',[(sid,r[1]) for sid,r in rows.items()])
                    q.execute('SELECT m.id,m.store_type_code FROM crm_store_master m JOIN classification_stage s ON s.store_id=m.id WHERE m.tenant_id=%s FOR UPDATE',(tenant,))
                    existing=q.fetchall(); pending=[sid for sid,code in existing if code is None]
                    report['existingClassificationPreserved']+=len(existing)-len(pending)
                    q.execute('UPDATE crm_store_master m JOIN classification_stage s ON s.store_id=m.id SET m.store_type_code=s.code,m.revision=m.revision+1,m.updated_at=UTC_TIMESTAMP(6),m.updated_by=%s WHERE m.tenant_id=%s AND m.store_type_code IS NULL',(actor,tenant))
                    if q.rowcount!=len(pending):raise ValueError('Classification update count mismatch')
                    events=[]
                    for sid in pending:
                        row,code=rows[sid];request='TYPE_SOURCE:'+str(sid)
                        events.append((imp.stable_id('classification-event',tenant,str(sid)),tenant,sid,sid,actor,
                          json.dumps({'storeTypeCode':code,'sourceCategory':row['sourceCategory'],'sourceBatch':manifest['batchRef'],'classificationSource':catalog['source']},ensure_ascii=False),
                          '按原始高德行业分类关联字典；未推断经营模式，未关联历史客户档案',request))
                    imp.insert_many(q,"INSERT INTO crm_store_event(id,tenant_id,store_id,object_type,object_id,event_type,actor_user_id,occurred_at,before_data,after_data,reason,request_id,event_seq) VALUES(%s,%s,%s,'STORE',%s,'SOURCE_CLASSIFICATION_APPLIED',%s,UTC_TIMESTAMP(6),'{\"storeTypeCode\":null}',%s,%s,%s,1)",events)
                connection.commit();report['updatedStores']+=len(pending)
                if start%10000==0:print(json.dumps(dict(report)),flush=True)
            except Exception:
                connection.rollback();raise
        (directory/'classification-result.json').write_text(json.dumps(dict(report),ensure_ascii=False,indent=2)+'\n')
        return dict(report)
    finally:
        with connection.cursor() as q:
            q.execute('DROP TEMPORARY TABLE IF EXISTS classification_stage')
            q.execute('SELECT RELEASE_LOCK(%s)',('crm-file-import:'+tenant,))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);p.add_argument('catalog',type=Path)
    p.add_argument('--config',required=True);p.add_argument('--foundation-config',required=True);p.add_argument('--tenant',required=True);p.add_argument('--actor',required=True);p.add_argument('--apply',action='store_true')
    a=p.parse_args()
    if not a.apply:p.error('--apply is required')
    foundation=imp.admin_import.connect(a.foundation_config)
    try:
        with foundation.cursor() as q:
            q.execute("SELECT dictionary_item_code FROM data_dictionary_item WHERE dictionary_code='CRM_STORE_TYPE' AND deleted=0 AND enabled=1 AND tenant_key IN ('GLOBAL',%s)",(a.tenant,));codes={r[0] for r in q.fetchall()}
    finally:foundation.close()
    c=imp.admin_import.connect(a.config)
    try:
        with c.cursor() as q:
            q.execute('SELECT DATABASE()')
            if q.fetchone()[0]!='rigour_crm':raise ValueError('CRM database required')
        print(json.dumps(apply(c,a.directory,json.loads(a.catalog.read_text()),a.tenant,a.actor,codes),ensure_ascii=False))
    finally:c.close()
