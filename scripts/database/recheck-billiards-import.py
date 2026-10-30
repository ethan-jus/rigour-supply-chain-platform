#!/usr/bin/env python3
"""Recheck the original rejected rows with explicit evidence; never infer missing addresses."""
import argparse
from collections import Counter
import csv
import hashlib
import importlib.util
import json
import math
from difflib import SequenceMatcher
from pathlib import Path
import re

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare-billiards-import.py'))
prepare = importlib.util.module_from_spec(spec); spec.loader.exec_module(prepare)
HALL = re.compile(r'(?:台球|桌球|撞球|斯诺克).*(?:俱乐部|球厅|球馆|球室|会所)|台球厅|桌球室|台球室', re.I)
# Only concordant explicit name AND category identify a non-billiards venue.
NON_VENUES = [
    (r'篮球|足球|羽毛球|乒乓球|网球|游泳|跆拳道|拳击|武术|瑜伽|健身', r'运动场馆|健身中心|培训机构|体育休闲'),
    (r'网吧|网咖|电竞', r'网吧'),
    (r'烟酒店|烟酒商行|烟酒专卖', r'烟酒专卖店'),
    (r'理发店|理发馆|美发店', r'美容美发'),
]
STRONG_BILLIARDS = re.compile(r'台球|桌球|撞球|斯诺克|九球|黑八|黑8|球房|billiards?|snooker', re.I)
REASONS = {
    'VENUE_CATEGORY_CONFLICT': '名称指向台球但来源分类明确指向另一运动场馆，需核实',
    'API_NOT_VERIFIED': '尚无成功的实时POI核验结果',
    'POI_NOT_FOUND': '高德详情未返回对应POI；不能据此判断已经停业',
    'POI_NAME_CHANGED': '同一POI名称明显改变，需核验是否同一家门店',
    'POI_LOCATION_CHANGED': '同一POI位置变化超过150米，需核实迁址或数据错误',
    'POI_IDENTITY_CONFLICT': '高德返回身份信息不完整，需人工核验',
    'OPERATING_STATUS_CHANGED': '历史停业标记已消失，但仍需确认是否恢复营业',
    'SOURCE_MARKED_CLOSED': '来源记录明确标记暂停或停止营业，暂不进入活跃公海',
    'NON_BILLIARDS_VENUE': '名称与分类一致指向其他业态，未发现台球经营依据',
    'EQUIPMENT_ONLY': '名称与购物或企业分类一致指向器材供应，未发现营业球厅依据',
    'MIXED_HALL_AND_EQUIPMENT': '球厅经营名称与体育休闲分类一致，器材词属于兼营业态或分店描述',
    'BILLIARDS_NAME_AND_SPORTS_CATEGORY': '明确球房或台球名称与体育休闲分类相符',
    'MISSING_OR_INVALID_ADDRESS': '缺少可用地址，需按准确POI核验补全',
    'ADMIN_CODE_UNMATCHED': '源行政编码尚无已核准行政区划映射',
    'VENUE_TYPE_UNCONFIRMED': '门店性质或名称分类冲突，需进一步核验',
    'POSSIBLE_EQUIPMENT_SUPPLIER': '器材供应或营业球厅性质尚不明确',
    'DUPLICATE_EXISTING_VENUE': '与已入库门店物理信息相同，需核验POI别名关系',
}


def decide(record):
    d = record['data']; name = prepare.clean(d.get('门店名称')); category = prepare.clean(d.get('高德分类'))
    if prepare.CLOSED.search(name): return 'EXCLUDED', 'SOURCE_MARKED_CLOSED'
    billiards = bool(STRONG_BILLIARDS.search(name))
    if not billiards and '台球厅' not in category:
        for name_pattern, category_pattern in NON_VENUES:
            if re.search(name_pattern, name) and re.search(category_pattern, category):
                return 'EXCLUDED', 'NON_BILLIARDS_VENUE'
    if '台球厅' not in category and re.search(r';(?:篮球场馆|足球场|羽毛球场|乒乓球馆|网球场|游泳馆|健身中心)(?:\||$)', category):
        return 'PENDING', 'VENUE_CATEGORY_CONFLICT'
    supplier = bool(prepare.SUPPLIER.search(name))
    if supplier and not HALL.search(name) and '台球厅' not in category and re.search(r'购物服务|公司企业', category):
        return 'EXCLUDED', 'EQUIPMENT_ONLY'
    if supplier:
        if HALL.search(name) and '体育休闲服务' in category:
            return 'CANDIDATE', 'MIXED_HALL_AND_EQUIPMENT'
        return 'PENDING', 'POSSIBLE_EQUIPMENT_SUPPLIER'
    if '台球厅' in category or (billiards and '体育休闲服务' in category):
        return 'CANDIDATE', 'BILLIARDS_NAME_AND_SPORTS_CATEGORY'
    return 'PENDING', 'VENUE_TYPE_UNCONFIRMED'


def enrich(record, result):
    """Use only the exact POI; preserve blanks and flag identity/location changes for review."""
    d = record['data']; updated = dict(d)
    verification = {'method':'AMAP_POI_DETAIL', 'checkedAt':result.get('checkedAt') if result else None, 'changes':{}, 'result':'UNCONFIRMED'}
    if not result or result.get('response',{}).get('status')!='1':
        return record, verification, 'API_NOT_VERIFIED'
    pois = [p for p in result['response'].get('pois',[]) if p.get('id')==d.get('高德 POI ID')]
    if len(pois)!=1: return record, verification, 'POI_NOT_FOUND'
    poi=pois[0]
    verification['result']='EXACT_POI_FOUND'
    verification['providerTypeCode']=poi.get('typecode')
    if not isinstance(poi.get('name'),str) or not poi['name'].strip():
        return record, verification, 'POI_IDENTITY_CONFLICT'
    similarity=SequenceMatcher(None,prepare.key(d['门店名称']),prepare.key(poi['name'])).ratio()
    if similarity<0.65: return record, verification, 'POI_NAME_CHANGED'
    if poi.get('location'):
        try:
            old_lng,old_lat=map(float,d['经纬度'].split(','));lng,lat=map(float,poi['location'].split(','))
            distance=6371000*2*math.asin(min(1,math.sqrt(math.sin(math.radians(lat-old_lat)/2)**2+math.cos(math.radians(lat))*math.cos(math.radians(old_lat))*math.sin(math.radians(lng-old_lng)/2)**2)))
            verification['positionDifferenceMetres']=round(distance,2)
            if distance>150: return record, verification, 'POI_LOCATION_CHANGED'
        except (ValueError,TypeError):return record, verification, 'INVALID_API_COORDINATES'
    if poi.get('adcode') and str(poi['adcode'])[:2]!=str(d['行政区划代码'])[:2]:
        return record, verification, 'PROVINCE_CONFLICT'
    for source,target in [('name','门店名称'),('address','地址'),('type','高德分类'),('location','经纬度'),('adcode','行政区划代码'),('tel','电话')]:
        value=poi.get(source)
        if isinstance(value,str) and value.strip():
            value=prepare.clean(value)
            if value!=prepare.clean(d.get(target)):
                verification['changes'][target]={'before':d.get(target),'after':value}
                updated[target]=value
    updated_record={**record,'data':updated}
    if prepare.CLOSED.search(d['门店名称']) and not prepare.CLOSED.search(updated['门店名称']):
        return updated_record, verification, 'OPERATING_STATUS_CHANGED'
    return updated_record, verification, None


def signature(row):
    return (row['adminAreaCode'], prepare.key(row['name']), prepare.key(row['address']), row['longitude'], row['latitude'])


def build(original, catalog_path, output, api_cache=None):
    catalog = json.loads(catalog_path.read_text()); nodes = {n['code']: n for n in catalog['nodes']}
    prior_manifest = json.loads((original/'manifest.json').read_text())
    prior = [json.loads(line) for line in (original/'accepted.jsonl').open()]
    existing = {signature(r): r for r in prior}
    existing_pois = {i['poi'] for r in prior for i in r['identities']}
    records = [json.loads(line) for line in (original/'review.jsonl').open()]
    api = {}
    if api_cache:
        with api_cache.open() as responses:
            for line in responses:
                result=json.loads(line);api[result['poiId']]=result
    decisions = []; candidates = []
    for record in records:
        original_record = record
        verification = {'method':'SOURCE_REVIEW'}; blocked = None
        if api_cache:
            record,verification,blocked = enrich(record,api.get(record['data']['高德 POI ID']))
        state, reason = decide(record)
        if blocked and state!='EXCLUDED': state,reason = 'PENDING',blocked
        row = None; structural = []
        if state == 'CANDIDATE':
            province = next((n['name'] for n in nodes.values() if n['level']=='PROVINCE' and Path(record['file']).name.startswith(n['name']+'_')), None)
            if province is None: raise ValueError('Unknown source province')
            row, structural = prepare.normalize(record, nodes, province, venue_verified=True)
            if structural: state = 'PENDING'; reason = structural[0]
            elif row['identities'][0]['poi'] in existing_pois or signature(row) in existing:
                state = 'PENDING'; reason = 'DUPLICATE_EXISTING_VENUE'
            else:
                state = 'ACCEPTED'; row['verification'] = {**verification,'reason':reason}
                candidates.append(row)
        decisions.append({**record, 'originalData':original_record['data'], 'verification':verification, 'decision':state, 'decisionReason':reason,
                          'evidence':REASONS.get(reason, reason), 'remainingIssues':structural,
                          'verificationMethod':verification['method'], 'sourceZipSha256':prior_manifest['sourceZipSha256']})
    accepted, duplicates = prepare.deduplicate(candidates)
    output.mkdir(parents=True, exist_ok=True)
    for name, rows in [('accepted',accepted),('decisions',decisions),('excluded',[r for r in decisions if r['decision']=='EXCLUDED']),('review',[r for r in decisions if r['decision']=='PENDING'])]:
        (output/(name+'.jsonl')).write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows))
    for state, filename in [('ACCEPTED','可导入'),('EXCLUDED','已排除'),('PENDING','待核验')]:
        with (output/(filename+'.csv')).open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.writer(f);w.writerow(['处理结果','处理原因','原异常原因','门店名称','高德POI','高德分类','公开电话','地址','经纬度','行政编码','来源文件','工作表','行号','核验方式'])
            for r in decisions:
                if r['decision']!=state: continue
                d=r['data'];values=[state,r['evidence'],';'.join(r['reasons']),d.get('门店名称'),d.get('高德 POI ID'),d.get('高德分类'),d.get('电话'),d.get('地址'),d.get('经纬度'),d.get('行政区划代码'),r['file'],r['sheet'],r['row'],r['verificationMethod']]
                w.writerow(["'"+v if isinstance(v,str) and v.startswith(('=','+','-','@')) else v for v in values])
    sha=hashlib.sha256((output/'accepted.jsonl').read_bytes()).hexdigest()
    manifest={'batchRef':'AMAP_RECHECK_'+prior_manifest['sourceZipSha256'][:16], 'sourceZipSha256':prior_manifest['sourceZipSha256'], 'catalogVersion':catalog['version'],
              'acceptedSha256':sha,'inputRows':len(records),'decisions':dict(Counter(r['decision'] for r in decisions)),
              'byReason':dict(Counter(r['decisionReason'] for r in decisions)), 'acceptedStores':len(accepted),
              'acceptedIdentities':sum(len(r['identities']) for r in accepted),'duplicates':duplicates}
    manifest['ruleVersion']='20261029.1'
    manifest['sourceReviewSha256']=hashlib.sha256((original/'review.jsonl').read_bytes()).hexdigest()
    manifest['apiResponseSha256']=hashlib.sha256(api_cache.read_bytes()).hexdigest() if api_cache else None
    assert sum(manifest['decisions'].values())==len(records)
    (output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(manifest,ensure_ascii=False,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('original',type=Path);p.add_argument('catalog',type=Path);p.add_argument('output',type=Path);p.add_argument('--api-cache',type=Path);a=p.parse_args();build(a.original,a.catalog,a.output,a.api_cache)
