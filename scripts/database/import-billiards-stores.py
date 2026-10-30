#!/usr/bin/env python3
"""Import a validated file batch into CRM store tables only; restartable by POI identity."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path

spec=importlib.util.spec_from_file_location('admin_import',Path(__file__).with_name('import-admin-divisions.py'))
admin_import=importlib.util.module_from_spec(spec);spec.loader.exec_module(admin_import)


def stable_id(kind, tenant, value):
    return int.from_bytes(hashlib.sha256(f'{kind}:{tenant}:{value}'.encode()).digest()[:8], 'big') & ((1 << 62) - 1)


def insert_many(cursor, sql, rows):
    # PyMySQL falls back to one round trip per row when VALUES contains SQL functions/literals.
    prefix, values = sql.split(' VALUES', 1)
    for start in range(0, len(rows), 250):
        part = rows[start:start + 250]
        cursor.execute(prefix + ' VALUES' + ','.join([values] * len(part)),
                       tuple(value for row in part for value in row))


def apply(connection, directory, tenant, actor, expected_sha, retire_dev=False):
    manifest=json.loads((directory/'manifest.json').read_text())
    digest=hashlib.sha256((directory/'accepted.jsonl').read_bytes()).hexdigest()
    if digest != expected_sha or digest != manifest['acceptedSha256']:
        raise ValueError('Accepted file hash does not match reviewed manifest')
    batch=manifest['batchRef'];version=manifest['catalogVersion']
    report={'batchRef':batch,'insertedStores':0,'insertedIdentities':0,'skippedStores':0,'retiredDevStores':0}
    with connection.cursor() as q:
        q.execute("SELECT GET_LOCK(%s,0)",('crm-file-import:'+tenant,))
        if q.fetchone()[0]!=1: raise ValueError('A store import is already running for this tenant')
    try:
        if retire_dev:
            connection.begin()
            with connection.cursor() as q:
                # The caller selects the actual DEV fixture IDs below; never retire ordinary stores by fuzzy names.
                ids=list(range(902610280101,902610280107))+list(range(902610280201,902610280207))
                q.execute('SELECT id,name,record_status FROM crm_store_master WHERE tenant_id=%s AND id IN ('+','.join(['%s']*len(ids))+') FOR UPDATE',(tenant,*ids))
                fixtures=q.fetchall()
                if len(fixtures)!=12 or any(not name.startswith('【DEV') for _,name,_ in fixtures):
                    raise ValueError('DEV fixture guard failed')
                q.execute('SELECT COUNT(*) FROM crm_store_summary WHERE tenant_id=%s AND store_id IN ('+','.join(['%s']*len(ids))+') AND current_ownership_id IS NOT NULL',(tenant,*ids))
                if q.fetchone()[0]: raise ValueError('DEV fixture still has active ownership')
                for store_id,name,status in fixtures:
                    if status!='ACTIVE': continue
                    reason='全国真实门店导入：停用原DEV联调样本，保留历史并隔离其测试POI身份'
                    q.execute("UPDATE crm_store_master SET record_status='SUSPENDED',status_reason=%s,revision=revision+1,updated_at=UTC_TIMESTAMP(6),updated_by=%s WHERE tenant_id=%s AND id=%s",(reason,actor,tenant,store_id))
                    q.execute("UPDATE crm_store_summary SET record_status='SUSPENDED',revision=revision+1,updated_at=UTC_TIMESTAMP(6) WHERE tenant_id=%s AND store_id=%s",(tenant,store_id))
                    q.execute("UPDATE crm_store_identity SET identity_scope='DEV_TEST_CN',status_code='INACTIVE',revision=revision+1,updated_at=UTC_TIMESTAMP(6),updated_by=%s WHERE tenant_id=%s AND store_id=%s AND acquisition_method='DEV_TEST'",(actor,tenant,store_id))
                    q.execute("INSERT INTO crm_store_event(id,tenant_id,store_id,object_type,object_id,event_type,actor_user_id,occurred_at,before_data,after_data,reason,request_id,event_seq) VALUES(%s,%s,%s,'STORE',%s,'DEV_FIXTURE_RETIRED',%s,UTC_TIMESTAMP(6),%s,%s,%s,%s,1)",
                        (stable_id('dev-retirement',tenant,str(store_id)),tenant,store_id,store_id,actor,json.dumps({'recordStatus':'ACTIVE'}),json.dumps({'recordStatus':'SUSPENDED'}),reason,batch+':dev:'+str(store_id)))
                    report['retiredDevStores']+=1
            connection.commit()
        with connection.cursor() as q:
            q.execute("SELECT CAST(external_id AS CHAR),store_id FROM crm_store_identity WHERE tenant_id=%s AND provider_code='AMAP' AND identity_scope='CN'",(tenant,))
            existing=dict(q.fetchall())
            q.execute('SELECT id FROM crm_pool_policy WHERE tenant_id=%s AND status_code=\'PUBLISHED\'',(tenant,))
            if not q.fetchone(): raise ValueError('Published CRM pool policy required')
        connection.commit()
        rows=[]
        def flush():
            if not rows:return
            connection.begin()
            try:
                with connection.cursor() as q:
                    # Same lock as online admission: no duplicate creation races while this batch commits.
                    q.execute("SELECT id FROM crm_pool_policy WHERE tenant_id=%s AND status_code='PUBLISHED' FOR UPDATE",(tenant,))
                    masters,summaries,identities,events=[],[],[],[]
                    all_pois=[i['poi'] for row in rows for i in row['identities']]
                    q.execute("SELECT CAST(external_id AS CHAR),store_id FROM crm_store_identity WHERE tenant_id=%s AND provider_code='AMAP' AND identity_scope='CN' AND external_id IN ("+','.join(['%s']*len(all_pois))+')',(tenant,*all_pois))
                    batch_matches=dict(q.fetchall())
                    for row in rows:
                        pois=[i['poi'] for i in row['identities']]
                        matches={p:batch_matches[p] for p in pois if p in batch_matches}
                        if matches:
                            if len(matches)!=len(pois) or len(set(matches.values()))!=1: raise ValueError('Partially matched or conflicting POI group; review required')
                            report['skippedStores']+=1;continue
                        sid=stable_id('store',tenant,batch+':'+pois[0])
                        masters.append((sid,tenant,row['name'],row['address'],row['publicPhone'],row['adminAreaCode'],version,row['areaResolution'],row['longitude'],row['latitude'],actor,actor))
                        summaries.append((tenant,sid,row['adminAreaCode'],version,row['areaResolution'],row['geoRegionCode'],row['provinceCode'],row['cityCode'],row['countyCode']))
                        checked_at=(row.get('verification') or {}).get('checkedAt')
                        observed_at=datetime.fromisoformat(checked_at).astimezone(timezone.utc).replace(tzinfo=None) if checked_at else None
                        for identity in row['identities']:
                            source=json.dumps({'zipSha256':manifest['sourceZipSha256'],**identity['source']},ensure_ascii=False,separators=(',',':'))
                            if len(source)>500:raise ValueError('Source reference exceeds schema capacity')
                            identities.append((stable_id('amap-identity',tenant,identity['poi']),tenant,sid,identity['poi'],batch,source,observed_at,observed_at,actor,actor))
                        after=json.dumps({'sourceBatch':batch,'poiIds':pois,'adminAreaVersion':version,'adminAreaCode':row['adminAreaCode'],'sourceCategory':row['sourceCategory'],'verification':row.get('verification')},ensure_ascii=False)
                        events.append((stable_id('import-event',tenant,batch+':'+pois[0]),tenant,sid,sid,actor,after,'高德文件清洗导入；仅门店资料，未关联历史客户档案或交易',batch+':'+pois[0]))
                    insert_many(q, "INSERT INTO crm_store_master(id,tenant_id,name,address,public_phone,admin_area_code,admin_area_version,area_resolution,longitude,latitude,coordinate_system,created_at,updated_at,created_by,updated_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'GCJ02',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),%s,%s)",masters)
                    insert_many(q, "INSERT INTO crm_store_summary(tenant_id,store_id,updated_at,record_status,admin_area_code,admin_area_version,area_resolution,geo_region_code,province_code,city_code,county_code,tier_unrated_reason) VALUES(%s,%s,UTC_TIMESTAMP(6),'ACTIVE',%s,%s,%s,%s,%s,%s,%s,'DATA_INCOMPLETE')",summaries)
                    insert_many(q, "INSERT INTO crm_store_identity(id,tenant_id,store_id,provider_code,identity_scope,external_id,acquisition_method,source_batch_ref,source_record_ref,observed_at,verified_at,created_at,updated_at,created_by,updated_by) VALUES(%s,%s,%s,'AMAP','CN',%s,'FILE_IMPORT',%s,%s,%s,COALESCE(%s,UTC_TIMESTAMP(6)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),%s,%s)",identities)
                    insert_many(q, "INSERT INTO crm_store_event(id,tenant_id,store_id,object_type,object_id,event_type,actor_user_id,occurred_at,after_data,reason,request_id,event_seq) VALUES(%s,%s,%s,'STORE',%s,'FILE_IMPORTED',%s,UTC_TIMESTAMP(6),%s,%s,%s,1)",events)
                connection.commit()
                report['insertedStores']+=len(masters);report['insertedIdentities']+=len(identities)
                (directory/'import-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
                print(json.dumps(report,ensure_ascii=False),flush=True)
            except Exception:
                connection.rollback();raise
            finally:rows.clear()
        with (directory/'accepted.jsonl').open() as accepted:
            for line in accepted:
                row=json.loads(line)
                pois=[i['poi'] for i in row['identities']]
                if all(p in existing for p in pois):
                    if len({existing[p] for p in pois})!=1:raise ValueError('POIs are linked to distinct stores')
                    report['skippedStores']+=1;continue
                rows.append(row)
                if len(rows)>=1000:flush()
        flush()
        (directory/'import-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        return report
    finally:
        with connection.cursor() as q:q.execute('SELECT RELEASE_LOCK(%s)',('crm-file-import:'+tenant,))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path)
    p.add_argument('--config',type=Path,required=True);p.add_argument('--tenant',required=True);p.add_argument('--actor',required=True)
    p.add_argument('--expected-sha256',required=True);p.add_argument('--apply',action='store_true');p.add_argument('--retire-dev-fixtures',action='store_true')
    a=p.parse_args()
    if not a.apply:p.error('Use the preparation manifest for preview; --apply is required for database import')
    c=admin_import.connect(a.config)
    try:
        with c.cursor() as q:
            q.execute("SELECT DATABASE()")
            if q.fetchone()[0] != "rigour_crm": raise ValueError("CRM data source required")
        print(json.dumps(apply(c,a.directory,a.tenant,a.actor,a.expected_sha256,a.retire_dev_fixtures),ensure_ascii=False,indent=2))
    finally:c.close()
