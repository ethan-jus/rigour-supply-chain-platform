#!/usr/bin/env python3
"""本地交互预览：实际工作树静态页面 + 虚构 API 数据，不访问生产或任何外部网络。
运行 python3 scripts/public-preview.py --port 8774。示例记录、上传只存于本进程内存，重启清空。
图片复用既有“示例便利店 / 演示素材”图片；音频是运行时生成的 4 秒测试音，不是真人沟通。
"""
import argparse
import datetime as dt
import email.parser
import email.policy
import http.server
import io
import json
import math
import pathlib
import re
import struct
import threading
import urllib.parse
import uuid
import wave

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'src/main/resources/static'
PHOTO = (pathlib.Path(__file__).resolve().parent / 'fixtures/demo-storefront.jpg').read_bytes()
API = '/sales-checkin/api/v1'
PERSON = '11111111-1111-4111-8111-111111111111'
TENANT = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
SHANGHAI = dt.timezone(dt.timedelta(hours=8))
LOCK = threading.RLock()
STORES = [
    {'id': '22222222-2222-4222-8222-222222222222', 'name': '悦邻便利店（西湖店 · 示例）', 'city': '杭州',
     'address': '西湖区文二路 186 号', 'attribute': '便利店', 'distanceMeters': 86},
    {'id': '33333333-3333-4333-8333-333333333333', 'name': '悦邻便利店（滨江店 · 示例）', 'city': '杭州',
     'address': '滨江区江南大道 588 号', 'attribute': '便利店', 'distanceMeters': 420},
    {'id': '44444444-4444-4444-8444-444444444444', 'name': '欣禾生活超市（示例）', 'city': '杭州',
     'address': '拱墅区莫干山路 108 号', 'attribute': '超市', 'distanceMeters': 1180},
]
for store in STORES:
    store.update(storeId=store['id'], locationSummary=store['address'], source='REGISTERED', checkinEligible=True,
                 nextAction='CHECK_IN', locationVerificationStatus='VERIFIED')
OPTIONS = {'cities': ['杭州', '苏州', '上海', '总部'], 'salespersons': [{'id': PERSON, 'name': '陈明 · 示例', 'city': '杭州'}],
    'maxAudioBytes': 268435456, 'storeAttributes': ['便利店', '超市'], 'operatingStatuses': ['营业中', '停业'],
    'areaRanges': ['50㎡以下', '50—100㎡'], 'businessTypes': ['食品零售'], 'intendedBusinesses': ['休闲零食'],
    'cooperationIntents': ['意向合作', '持续跟进'], 'storeGrades': ['A', 'B'], 'storeTags': ['社区店']}
SUBMISSIONS = {}
MEDIA = {}

def instant(value=None):
    return (value or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z')

def parse_instant(value):
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))

def identity():
    return {'authenticated': True, 'tenantId': TENANT, 'salespersonId': PERSON, 'salespersonName': '陈明 · 示例',
            'city': '杭州', 'enforcementEnabled': True, 'expiresAt': instant(dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=2))}

def make_wav():
    output = io.BytesIO()
    with wave.open(output, 'wb') as audio:
        audio.setnchannels(1); audio.setsampwidth(2); audio.setframerate(16000)
        # Standard PCM, quiet four-second test tone with a short fade to avoid clicks.
        audio.writeframes(b''.join(struct.pack('<h', round(1800 * min(1, i / 800, (64000 - i) / 800)
            * math.sin(2 * math.pi * 440 * i / 16000))) for i in range(64000)))
    return output.getvalue()

WAV = make_wav()

def put_media(item, media_id, kind, data, content_type, filename, duration=None):
    existing = next((media for media in item['media'] if media['mediaId'] == media_id), None)
    if existing:
        return existing
    base = f"{API}/submissions/{item['id']}/mine/media/{media_id}"
    media = {'mediaId': media_id, 'kind': kind, 'originalFilename': filename, 'contentType': content_type,
             'sizeBytes': len(data), 'uploadedAt': instant(), 'parsedDurationMs': duration,
             'playbackStatus': 'READY' if kind == 'audio' and duration is not None else 'PENDING',
             'thumbnailUrl': base + '?variant=thumbnail' if kind != 'audio' else None,
             'playbackUrl': base + '?variant=playback' if kind == 'audio' and duration is not None else None,
             'originalUrl': base + '?variant=original'}
    item['media'].append(media)
    if kind not in item['uploadedMedia']: item['uploadedMedia'].append(kind)
    if kind == 'audio' and media_id not in item['audioSegmentIds']: item['audioSegmentIds'].append(media_id)
    MEDIA[(item['id'], media_id)] = (data, content_type)
    return media

def create_item(client_id, store, data=None, submitted=None, record_id=None):
    data = data or {}
    created = submitted or instant()
    location = data.get('location') or {}
    return {'id': record_id or str(uuid.uuid4()), 'clientSubmissionId': client_id, 'status': 'SUBMITTED' if submitted else 'DRAFT',
        'city': data.get('city') or store['city'], 'salespersonId': PERSON, 'salespersonName': '陈明 · 示例', 'storeId': store['id'], 'storeName': store['name'],
        'customerName': data.get('customerName') or '李店长（示例）', 'customerPhone': data.get('customerPhone') or '',
        'visitResult': data.get('visitResult') or '示例：沟通新品陈列和补货计划，客户希望周五前安排下一次送货。',
        'createdAt': created, 'submittedAt': submitted, 'uploadedMedia': [], 'audioSegmentIds': [], 'media': [],
        'supplementUntil': instant(parse_instant(submitted) + dt.timedelta(hours=24)) if submitted else None,
        'locationQuality': 'GOOD' if location.get('latitude') is not None else 'MISSING',
        'locationCapturedAt': location.get('capturedAt'), 'locationRawTimestamp': location.get('rawTimestamp'),
        'locationReceivedAt': created, 'locationSource': location.get('source') or 'BROWSER',
        'locationAddress': location.get('address'), 'locationNote': data.get('locationFailureReason'),
        'longitude': location.get('longitude'), 'latitude': location.get('latitude'), 'accuracyMeters': location.get('accuracyMeters'),
        'storeLongitude': 120.1383, 'storeLatitude': 30.2858, 'distanceMeters': 86 if location else None}

def seed():
    local_now = dt.datetime.now(SHANGHAI)
    for day_offset, count in [(0, 22), (1, 3), (5, 2)]:
        day = local_now.date() - dt.timedelta(days=day_offset)
        first = dt.datetime.combine(day, dt.time(9, 12), SHANGHAI)
        for index in range(count):
            number = day_offset * 100 + index + 1
            submitted = min(first + dt.timedelta(minutes=index * 8), local_now - dt.timedelta(minutes=1))
            item = create_item(f'preview-{number}', STORES[index % len(STORES)], submitted=instant(submitted),
                record_id=str(uuid.UUID(int=(0x50000000000040008000000000000000 + number))))
            item.update(locationQuality='STALE' if index % 3 == 0 else 'GOOD', longitude=120.1389, latitude=30.2863,
                accuracyMeters=38, distanceMeters=86, locationAddress='示例设备报告地址：杭州市西湖区文二路',
                locationCapturedAt=instant(submitted - dt.timedelta(minutes=5 if index % 3 == 0 else 1)),
                locationRawTimestamp=str(int(submitted.timestamp() * 1000)), locationNote=None)
            put_media(item, 'storefront-photo', 'storefront-photo', PHOTO, 'image/jpeg', '示例门店照片.jpg')
            if index % 3 != 1:
                audio_id = str(uuid.UUID(int=0x60000000000040008000000000000000 + number))
                put_media(item, audio_id, 'audio', WAV, 'audio/wav', '示例测试音_4秒.wav', 4000)
            SUBMISSIONS[item['clientSubmissionId']] = item
seed()

def photos(item):
    result = []
    for media in item['media']:
        if media['kind'] != 'storefront-photo': continue
        photo_id = media['mediaId'].removeprefix('photo-') if media['mediaId'].startswith('photo-') else item['id']
        media_id = 'photo-' + photo_id
        base = f"{API}/submissions/{item['id']}/mine/media/{media_id}"
        result.append(dict(media, photoId=photo_id, mediaId=media_id, captureSource=media.get('captureSource'),
            thumbnailUrl=base + '?variant=thumbnail', originalUrl=base + '?variant=original'))
    return result

def receipt(item):
    result = {key: value for key, value in item.items() if key != 'media'}
    audios = [media for media in item['media'] if media['kind'] == 'audio']
    result['photos'] = photos(item)
    result['photoIds'] = [photo['photoId'] for photo in result['photos']]
    result['audioDurationMs'] = sum(media['parsedDurationMs'] for media in audios) if audios and all(media['parsedDurationMs'] is not None for media in audios) else None
    return result

def detail(item):
    result = dict(item)
    result['photos'] = photos(item)
    result['canSupplement'] = item['status'] == 'SUBMITTED' and bool(item.get('supplementUntil')) and parse_instant(item['supplementUntil']) > dt.datetime.now(dt.timezone.utc)
    return result

class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs): super().__init__(*args, directory=str(ROOT), **kwargs)
    def log_message(self, *args): pass
    def end_headers(self):
        self.send_header('Cache-Control', 'private, no-store')
        self.send_header('X-Preview-Fixture', 'local-example-only')
        super().end_headers()
    def payload(self, value, status=200):
        body = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status); self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body))); self.end_headers()
        if self.command != 'HEAD': self.wfile.write(body)
    def binary(self, data, content_type):
        start, end, status = 0, len(data) - 1, 200
        requested = self.headers.get('Range')
        if requested:
            match = re.fullmatch(r'bytes=(\d*)-(\d*)', requested)
            if not match or not any(match.groups()):
                return self.payload({'message': '示例媒体范围无效'}, 416)
            if match[1]: start = int(match[1]); end = min(end, int(match[2])) if match[2] else end
            else: start = max(0, len(data) - int(match[2]))
            if start > end or start >= len(data): return self.payload({'message': '示例媒体范围越界'}, 416)
            status = 206
        self.send_response(status); self.send_header('Content-Type', content_type)
        self.send_header('Accept-Ranges', 'bytes'); self.send_header('Content-Length', str(end - start + 1))
        if status == 206: self.send_header('Content-Range', f'bytes {start}-{end}/{len(data)}')
        self.end_headers()
        if self.command != 'HEAD': self.wfile.write(data[start:end + 1])
    def find_item(self, submission_id):
        return next((item for item in SUBMISSIONS.values() if item['id'] == submission_id), None)
    def do_HEAD(self):
        if urllib.parse.urlsplit(self.path).path.startswith(API): return self.do_GET()
        return super().do_HEAD()
    def do_GET(self):
        parsed = urllib.parse.urlsplit(self.path); route = parsed.path; query = urllib.parse.parse_qs(parsed.query)
        first = lambda key, default='': query.get(key, [default])[0]
        with LOCK:
            # Synthetic admin fixture only: these fixed IDs never touch a database or production media.
            if re.fullmatch(r'/sales-checkin/admin/api/v1/submissions/20000000-0000-4000-8000-000000000001/media/photos/20000000-0000-4000-8000-00000000002[0-8]', route):
                self.send_response(200)
                self.send_header('Content-Type', 'image/jpeg')
                self.send_header('Content-Length', str(len(PHOTO)))
                if first('download') == 'true': self.send_header('Content-Disposition', 'attachment; filename="fixture-storefront.jpg"')
                self.end_headers()
                if self.command != 'HEAD': self.wfile.write(PHOTO)
                return
            if route == API + '/identity/me': return self.payload(identity())
            if route == API + '/options': return self.payload(OPTIONS)
            if route == API + '/stores':
                rows = [store for store in STORES if first('q').lower() in (store['name'] + store['address']).lower()]
                return self.payload(sorted(rows, key=lambda item: item.get('distanceMeters', math.inf))[:50])
            if route.startswith(API + '/submissions/by-client/'):
                item = SUBMISSIONS.get(route.rsplit('/', 1)[-1])
                return self.payload(receipt(item), 200) if item else self.payload({'message': '示例记录未创建'}, 404)
            if route == API + '/submissions/mine':
                if first('salespersonId', PERSON) != PERSON: return self.payload({'message': '只可查询示例本人'}, 403)
                try:
                    number = int(first('page', '0')); size = int(first('size', '20'))
                    if number < 0 or size < 1 or size > 100: raise ValueError()
                    from_day = dt.date.fromisoformat(first('dateFrom')) if first('dateFrom') else None
                    to_day = dt.date.fromisoformat(first('dateTo')) if first('dateTo') else None
                    if from_day and to_day and from_day > to_day: raise ValueError()
                except ValueError: return self.payload({'message': '示例日期或分页无效'}, 400)
                rows = []
                for item in SUBMISSIONS.values():
                    if first('status') and item['status'] != first('status'): continue
                    if from_day or to_day:
                        if item['status'] != 'SUBMITTED': continue
                        day = parse_instant(item['submittedAt']).astimezone(SHANGHAI).date()
                        if from_day and day < from_day or to_day and day > to_day: continue
                    rows.append(item)
                rows.sort(key=lambda item: (item.get('submittedAt') or item['createdAt'], item['id']), reverse=first('sortDir', 'desc') != 'asc')
                return self.payload({'items': [receipt(item) for item in rows[number * size:(number + 1) * size]],
                    'page': number, 'size': size, 'totalElements': len(rows), 'totalPages': math.ceil(len(rows) / size)})
            match = re.fullmatch(API + r'/submissions/([^/]+)/mine', route)
            if match:
                item = self.find_item(match[1])
                return self.payload(detail(item)) if item else self.payload({'message': '示例记录不存在'}, 404)
            match = re.fullmatch(API + r'/submissions/([^/]+)/(?:mine/)?media/(.+)', route)
            if match:
                media_id = match[2].removeprefix('audio/')
                if media_id == 'photo-' + match[1]: media_id = 'storefront-photo'
                media = MEDIA.get((match[1], media_id))
                return self.binary(*media) if media else self.payload({'message': '示例媒体不存在'}, 404)
            if route.startswith(API): return self.payload({'message': '该 API 尚未加入本地示例'}, 404)
        return super().do_GET()
    def read_body(self):
        length = int(self.headers.get('Content-Length', '0'))
        if length > 12 * 1024 * 1024:
            self.close_connection = True
            raise ValueError('本地预览只接受 12 MiB 以内测试文件；生产上限由真实服务配置。')
        return self.rfile.read(length)
    def do_PUT(self): return self.do_POST()
    def do_POST(self):
        try: raw = self.read_body()
        except ValueError as error: return self.payload({'message': str(error)}, 413)
        try: data = json.loads(raw)
        except (ValueError, UnicodeDecodeError): data = {}
        route = urllib.parse.urlsplit(self.path).path
        with LOCK:
            if route.endswith('/identity/verify'): return self.payload(identity())
            if route.endswith('/identity/logout'): return self.payload({})
            if route.endswith('/client-events'): return self.payload({'accepted': True})
            if route.endswith('/locations/resolve'):
                return self.payload({'locationVerificationStatus': 'VERIFIED', 'address': '示例：杭州市西湖区文二路186号',
                    'formattedAddress': '示例：杭州市西湖区文二路186号', 'city': '杭州', 'resolvedCity': '杭州',
                    'geocodeStatus': 'RESOLVED', 'accuracyAccepted': True, 'freshnessAccepted': True,
                    'nearbyStores': STORES, 'nearbyPois': [], 'locationVerificationToken': 'preview-token',
                    'maxCheckinDistanceMeters': 300, 'maxCheckinAccuracyMeters': 100, 'maxLocationAgeMinutes': 5,
                    'capturedAt': (data.get('location') or {}).get('capturedAt')})
            if route.endswith('/locations/search-new-store'):
                candidates = [dict(source='AMAP_POI', poiId='B-PREVIEW-1', name='悦邻便利店（地图示例）',
                    address='西湖区示例路1号', distanceMeters=180, longitude=120.1383, latitude=30.2858,
                    checkinEligible=False, nextAction='COMPLETE_STORE_PROFILE', selectionToken='local-preview-token')]
                return self.payload({'nearbyStores': candidates, 'poiLookupStatus': 'AVAILABLE'})
            if route.endswith('/stores/new-location-candidates'): return self.payload({'items': []})
            if route in (API + '/stores', API + '/stores/unverified-location'):
                store = {'id': data.get('clientStoreId') or str(uuid.uuid4()), 'name': data.get('name') or '新增示例门店',
                    'city': data.get('city') or '杭州', 'locationSummary': data.get('address') or '示例新建门店',
                    'address': data.get('address') or '示例新建门店', 'locationVerificationStatus': 'UNVERIFIED'}
                store.update(storeId=store['id'], source='REGISTERED', checkinEligible=True, nextAction='CHECK_IN')
                STORES.append(store); return self.payload(store)
            if route in (API + '/submissions', API + '/submissions/unverified-location'):
                client_id = data.get('clientSubmissionId')
                if not client_id: return self.payload({'message': '缺少示例提交编号'}, 400)
                if client_id not in SUBMISSIONS:
                    store = next((store for store in STORES if store['id'] == data.get('storeId')), STORES[0])
                    SUBMISSIONS[client_id] = create_item(client_id, store, data)
                return self.payload(receipt(SUBMISSIONS[client_id]))
            match = re.fullmatch(API + r'/submissions/([^/]+)/media/(storefront-photo|wechat-screenshot|audio/([^/]+)|photos/([^/]+))', route)
            if match:
                item = self.find_item(match[1])
                if not item: return self.payload({'message': '示例记录不存在'}, 404)
                message = email.parser.BytesParser(policy=email.policy.default).parsebytes(
                    f"Content-Type: {self.headers.get('Content-Type', '')}\r\nMIME-Version: 1.0\r\n\r\n".encode() + raw)
                part = next((part for part in message.iter_parts() if part.get_param('name', header='content-disposition') == 'file'), None) if message.is_multipart() else None
                if not part: return self.payload({'message': '示例上传缺少文件'}, 400)
                content = part.get_payload(decode=True); mime = part.get_content_type(); filename = part.get_filename() or '示例附件'
                kind = 'audio' if match[3] else 'storefront-photo' if match[4] else match[2]
                media_id = 'photo-' + match[4] if match[4] else match[3] or kind
                duration = None
                if match[4] and len(photos(item)) >= 9 and not any(p['photoId'] == match[4] for p in photos(item)):
                    return self.payload({'message': '最多9张照片'}, 400)
                if kind == 'audio':
                    try:
                        with wave.open(io.BytesIO(content)) as audio: duration = round(audio.getnframes() / audio.getframerate() * 1000)
                    except (wave.Error, EOFError): pass
                media = put_media(item, media_id, kind, content, mime, filename, duration)
                if match[4]: media['captureSource'] = next((part.get_content() for part in message.iter_parts() if part.get_param('name', header='content-disposition') == 'captureSource'), None)
                return self.payload({'id': item['id'], 'kind': kind, 'status': item['status'], 'segmentId': media_id if kind == 'audio' else None,
                    'photoId': match[4], 'originalFilename': filename, 'sizeBytes': len(content), 'parsedDurationMs': duration, 'contentType': mime})
            match = re.fullmatch(API + r'/submissions/([^/]+)/complete', route)
            if match:
                item = self.find_item(match[1])
                if not item: return self.payload({'message': '示例记录不存在'}, 404)
                if 'storefront-photo' not in item['uploadedMedia']: return self.payload({'message': '请先上传现场照片'}, 400)
                if item['status'] != 'SUBMITTED':
                    item.update(status='SUBMITTED', submittedAt=instant(), supplementUntil=instant(dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=24)))
                return self.payload(receipt(item))
            return self.payload({'message': '该 API 尚未加入本地示例'}, 404)
    def do_DELETE(self):
        route = urllib.parse.urlsplit(self.path).path
        with LOCK:
            match = re.fullmatch(API + r'/submissions/([^/]+)/media/(storefront-photo|wechat-screenshot|audio/([^/]+)|photos/([^/]+))', route)
            if not match: return self.payload({'message': '该 API 尚未加入本地示例'}, 404)
            item = self.find_item(match[1])
            if not item: return self.payload({'message': '示例记录不存在'}, 404)
            if item['status'] == 'SUBMITTED' and match[4]: return self.payload({'message': '已提交原件不可删除'}, 409)
            media_id = 'photo-' + match[4] if match[4] else match[3] or match[2]
            if media_id == 'photo-' + item['id']: media_id = 'storefront-photo'
            item['media'] = [media for media in item['media'] if media['mediaId'] != media_id]
            item['audioSegmentIds'] = [value for value in item['audioSegmentIds'] if value != media_id]
            item['uploadedMedia'] = list(dict.fromkeys(media['kind'] for media in item['media']))
            MEDIA.pop((item['id'], media_id), None)
            return self.payload({'id': item['id'], 'status': item['status'], 'photoId': match[4]})

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=8774)
    port = parser.parse_args().port
    print(f'本地示例数据，不访问生产：http://127.0.0.1:{port}/sales-checkin/\n静态资源：{ROOT}', flush=True)
    http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()
