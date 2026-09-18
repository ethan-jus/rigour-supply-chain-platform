#!/usr/bin/env node
'use strict';
// Local mock APIs only. Browser File/Blob, multipart transport, DOM events and IndexedDB remain real.
const {chromium, webkit} = require(process.env.PW_MODULE_PATH || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {createHash} = require('node:crypto');
let base;
const api = '/sales-checkin/api/v1';
const engine = process.env.BROWSER_ENGINE || 'chromium';
const baseline = process.env.EXPECT_BASELINE_FAILURE === '1';
const staticRoot = path.resolve(process.env.CHECKIN_STATIC_ROOT || path.join(__dirname, '../src/main/resources/static/sales-checkin'));
const output = path.resolve(process.env.QA_DIR || path.join(__dirname, '../docs/qa-20260910/audio-recovery', `${engine}-${baseline ? 'before' : 'after'}`));
const id = n => `90000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const identity = {authenticated: true, enforcementEnabled: true, tenantId: id(99), salespersonId: id(501), salespersonName: '本地录音恢复验收', city: '杭州', expiresAt: new Date(Date.now()+86400000).toISOString()};
const owner = `${identity.tenantId}:${identity.salespersonId}`;
const store = {id: id(3), storeId: id(3), name: '本地录音恢复示例店', city: '杭州', address: '本地示例路1号', locationSummary: '本地示例路1号', attribute: '便利店', source: 'REGISTERED', checkinEligible: true, nextAction: 'CHECK_IN', locationVerificationStatus: 'VERIFIED'};
const options = {cities: ['杭州'], salespersons: [{id: id(501), name: identity.salespersonName, city: '杭州'}], maxAudioBytes: 268435456, storeAttributes: ['便利店'], operatingStatuses: ['营业中'], areaRanges: ['50㎡以下'], businessTypes: ['食品零售'], intendedBusinesses: ['休闲零食'], cooperationIntents: ['持续跟进'], storeGrades: ['A'], storeTags: ['社区店']};
const checks = [], cases = [];
function check(value, label) {checks.push({label, passed: !!value}); assert.ok(value, label);}
function wavBytes() {
    const data = Buffer.alloc(44+16000);
    data.write('RIFF', 0); data.writeUInt32LE(data.length-8, 4); data.write('WAVEfmt ', 8);
    data.writeUInt32LE(16, 16); data.writeUInt16LE(1, 20); data.writeUInt16LE(1, 22);
    data.writeUInt32LE(8000, 24); data.writeUInt32LE(16000, 28); data.writeUInt16LE(2, 32); data.writeUInt16LE(16, 34);
    data.write('data', 36); data.writeUInt32LE(16000, 40);
    for (let i = 0; i < 8000; i++) data.writeInt16LE(Math.round(Math.sin(i*2*Math.PI*440/8000)*1000), 44+i*2);
    return data;
}

(async () => {
    await fs.mkdir(output, {recursive: true});
    const sourceHashes = Object.fromEntries(await Promise.all(['app.js', 'personal-history.js', 'storage.js', 'index.html', 'styles.css'].map(async file =>
        [file, createHash('sha256').update(await fs.readFile(path.join(staticRoot, file))).digest('hex')])));
    const jpeg = await fs.readFile(path.join(__dirname, 'fixtures/demo-storefront.jpg'));
    const wav = wavBytes();
    async function scenario(name, run) {
        if (process.env.CASE_FILTER && !name.includes(process.env.CASE_FILTER)) return;
        const profile = await fs.mkdtemp(path.join(os.tmpdir(), 'checkin-audio-qa-'));
        const context = await (engine === 'webkit' ? webkit : chromium).launchPersistentContext(profile, {
            headless: true, ...(engine === 'webkit' ? {} : {channel: 'chrome'}), viewport: {width: 390, height: 844},
            isMobile: true, hasTouch: true, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', serviceWorkers: 'block'});
        const records = new Map(), byClient = new Map(), requests = [], errors = [], external = [];
        const fixture = {records, requests, audioMode: 'success', expectedAudioBytes: wav, audioAttempts: 0, createAttempts: 0, completeAttempts: 0, photoAttempts: 0, releaseAudio: null, holdAudio: false, faults: null};
        const receipt = record => ({...record, photos: [...record.photos], photoIds: record.photos.map(photo => photo.photoId),
            audioSegmentIds: [...record.audioSegmentIds], uploadedMedia: [...(record.photos.length ? ['storefront-photo'] : []), ...(record.audioSegmentIds.length ? ['audio'] : [])]});
        const send = (route, json, status = 200) => route.fulfill({status, json});
        await context.addInitScript(() => {
            window.__audioUiTrace = [];
            for (const event of ['focus', 'blur', 'pageshow', 'visibilitychange', 'click', 'play', 'pause', 'ended', 'error']) {
                window.addEventListener(event, value => {
                    if (window.__audioUiTrace.length >= 250) return;
                    window.__audioUiTrace.push({event, at: performance.now(), screen: document.body?.dataset.screen,
                        target: value.target?.id || value.target?.tagName, trusted: value.isTrusted});
                }, true);
            }
            new MutationObserver(() => {
                const screen = document.body?.dataset.screen;
                if (window.__audioUiTrace.at(-1)?.screenChange !== screen && screen) window.__audioUiTrace.push({screenChange: screen, at: performance.now()});
            }).observe(document, {subtree: true, attributes: true, attributeFilter: ['data-screen']});
            const denied = (_ok, fail) => setTimeout(() => fail?.({code: 1, message: 'Local synthetic geolocation denial'}), 0);
            Object.defineProperty(navigator, 'geolocation', {configurable: true, value: {getCurrentPosition: denied,
                watchPosition(_ok, fail) {denied(_ok, fail); return 1;}, clearWatch() {}}});
        });
        const serveRequest = async route => {
            const request = route.request(), url = new URL(request.url()), method = request.method();
            if (url.protocol === 'blob:' && url.origin === base) return route.continue();
            if (url.origin !== base) {external.push(url.origin); return route.abort();}
            if (url.pathname.startsWith(api)) {
                requests.push({method, path: url.pathname});
                if (method === 'POST' && /\/(diagnostics\/events|client-events)$/.test(url.pathname)) return send(route, {accepted: true});
                if (url.pathname === api+'/identity/me') return send(route, identity);
                if (url.pathname === api+'/options') return send(route, options);
                if (url.pathname === api+'/stores') return send(route, [store]);
                if (url.pathname === api+'/submissions' && method === 'POST') {
                    fixture.createAttempts++;
                    const body = request.postDataJSON();
                    let record = byClient.get(body.clientSubmissionId);
                    if (!record) {
                        record = {id: id(1000+records.size), clientSubmissionId: body.clientSubmissionId, status: 'DRAFT',
                            createdAt: new Date().toISOString(), city: body.city || '杭州', storeName: store.name,
                            salespersonId: identity.salespersonId, salespersonName: identity.salespersonName,
                            customerName: body.customerName, visitResult: body.visitResult, photos: [], audioSegmentIds: [], media: [],
                            locationQuality: 'MISSING', key: request.headers()['x-submission-key']};
                        records.set(record.id, record); byClient.set(record.clientSubmissionId, record);
                    }
                    return send(route, receipt(record));
                }
                if (url.pathname.startsWith(api+'/submissions/by-client/')) {
                    const record = byClient.get(decodeURIComponent(url.pathname.split('/').pop()));
                    return record ? send(route, receipt(record)) : send(route, {message: 'No mock visit yet'}, 404);
                }
                if (url.pathname === api+'/submissions/mine') {
                    const items = [...records.values()].filter(r => r.status === 'SUBMITTED').map(receipt);
                    return send(route, {items, page: 0, size: 20, totalElements: items.length, totalPages: items.length ? 1 : 0});
                }
                const match = url.pathname.match(/\/submissions\/([^/]+)\/(.*)$/);
                const record = match && records.get(match[1]), tail = match?.[2];
                if (!record) return send(route, {message: 'Unknown mock visit'}, 404);
                if (tail === 'mine') return send(route, receipt(record));
                if (tail.startsWith('mine/media/')) return route.fulfill({contentType: tail.includes('audio') ? 'audio/wav' : 'image/jpeg', body: tail.includes('audio') ? wav : jpeg});
                if (request.headers()['x-submission-key'] !== record.key) return send(route, {message: 'Wrong original record key'}, 403);
                if (tail === 'complete' && method === 'POST') {
                    fixture.completeAttempts++; assert.ok(record.photos.length, 'required photo arrived before mock completion');
                    record.status = 'SUBMITTED'; record.submittedAt = new Date().toISOString(); record.supplementUntil = new Date(Date.now()+86400000).toISOString();
                    return send(route, receipt(record));
                }
                if (tail.startsWith('media/photos/') && method === 'PUT') {
                    fixture.photoAttempts++;
                    const body = request.postDataBuffer(); assert.ok(body?.includes(jpeg), 'photo PUT contains the selected real JPEG bytes');
                    const photoId = tail.split('/').pop();
                    const photo = {id: record.id, kind: 'storefront-photo', photoId, mediaId: 'photo-'+photoId, contentType: 'image/jpeg',
                        originalFilename: 'demo-storefront.jpg', sizeBytes: jpeg.length, captureSource: 'CAMERA', uploadedAt: new Date().toISOString(),
                        thumbnailUrl: `${api}/submissions/${record.id}/mine/media/photo-${photoId}?variant=thumbnail`, originalUrl: `${api}/submissions/${record.id}/mine/media/photo-${photoId}?variant=original`};
                    if (!record.photos.some(item => item.photoId === photoId)) record.photos.push(photo);
                    return send(route, photo);
                }
                if (tail.startsWith('media/audio/') && method === 'PUT') {
                    fixture.audioAttempts++;
                    const body = request.postDataBuffer(); assert.ok(body?.includes(fixture.expectedAudioBytes), 'audio PUT contains every byte of the selected WAV file');
                    assert.match(request.headers()['content-type'], /^multipart\/form-data; boundary=/i);
                    if (fixture.holdAudio) await new Promise(resolve => {fixture.releaseAudio = resolve;});
                    if (fixture.audioMode === '503') return send(route, {message: '合成测试：上传服务暂不可用'}, 503);
                    if (fixture.audioMode === '400') return send(route, {message: '合成测试：录音内容无效，无法识别音轨，请重新选择原文件', reason: 'NO_AUDIO_TRACK', requestId: 'local-audio-qa-400'}, 400);
                    const segmentId = tail.split('/').pop();
                    if (!record.audioSegmentIds.includes(segmentId)) record.audioSegmentIds.push(segmentId);
                    const media = {id: record.id, kind: 'audio', segmentId, mediaId: segmentId, contentType: 'audio/wav', originalFilename: 'synthetic-tone.wav', sizeBytes: wav.length, clientDurationMs: 1000, captureSource: 'FILE_UPLOAD'};
                    if (!record.media.some(item => item.segmentId === segmentId)) record.media.push(media);
                    return send(route, media);
                }
                return send(route, {message: 'Unknown local route'}, 404);
            }
            if (method !== 'GET') return route.abort();
            if (url.pathname === '/favicon.ico') return route.fulfill({status: 204, body: ''});
            if (!url.pathname.startsWith('/sales-checkin/')) return route.abort();
            const suffix = url.pathname.slice('/sales-checkin/'.length) || 'index.html';
            const filename = path.resolve(staticRoot, suffix);
            if (!filename.startsWith(staticRoot+path.sep)) return route.abort();
            try {return await route.fulfill({path: filename});} catch {return route.fulfill({status: 404, body: 'Unknown local static file'});}
        };
        // Chromium interception omits file parts from postDataBuffer. A loopback HTTP receiver
        // verifies the actual multipart bytes instead of assuming a selected filename means upload.
        const server = http.createServer(async (incoming, response) => {
            try {
                const parts = []; let bytes = 0;
                for await (const part of incoming) {bytes += part.length; if (bytes > 2*1024*1024) throw new Error('Synthetic fixture request unexpectedly large'); parts.push(part);}
                const body = Buffer.concat(parts);
                const request = {url: () => base+incoming.url, method: () => incoming.method, headers: () => incoming.headers,
                    postDataBuffer: () => body, postDataJSON: () => JSON.parse(body.toString())};
                await serveRequest({request: () => request, abort: async () => {response.writeHead(403); response.end();},
                    fulfill: async options => {
                        response.writeHead(options.status || 200, {'content-type': options.json !== undefined ? 'application/json' : options.contentType || 'application/octet-stream'});
                        response.end(options.json !== undefined ? JSON.stringify(options.json) : options.body || '');
                    }});
            } catch (error) {errors.push('Local receiver: '+error.message); if (!response.headersSent) response.writeHead(500); response.end('Synthetic receiver failed');}
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        base = `http://127.0.0.1:${server.address().port}`;
        await context.route('**/*', async route => {
            const url = new URL(route.request().url());
            if (url.origin === base && url.pathname.startsWith(api)) return route.continue();
            return serveRequest(route);
        });
        const page = await context.newPage(); page.setDefaultTimeout(12000); page.on('pageerror', error => errors.push(error.message));
        const capture = label => page.screenshot({path: path.join(output, `${name}-${label}.png`), fullPage: true, animations: 'disabled'});
        async function fault(mode) {
            await page.evaluate(mode => {
                if (!window.__audioFault) {
                    // The production module is frozen. Replace only its public wrapper, keeping
                    // every non-faulted call delegated to the real IndexedDB implementation.
                    const store = {...window.SalesCheckinDraftStore};
                    window.SalesCheckinDraftStore = store;
                    const originals = {save: store.save, saveMedia: store.saveMedia, removeMedia: store.removeMedia};
                    window.__audioFault = {mode: '', saves: 0, realSaves: 0, metaFailures: 0, audioStoreFailures: 0, cleanupFailures: 0, realMediaSaves: 0, realMediaErrors: []};
                    store.save = async (...args) => {
                        const f = window.__audioFault; f.saves++;
                        if (f.mode === 'metadata') {f.metaFailures++; throw new DOMException('Synthetic quota: metadata checkpoint unavailable', 'QuotaExceededError');}
                        const result = await originals.save(...args); f.realSaves++; return result;
                    };
                    store.saveMedia = async (...args) => {
                        const f = window.__audioFault;
                        if (f.mode === 'audio-save' && String(args[2]).startsWith('audio:')) {f.audioStoreFailures++; throw new DOMException('Synthetic quota: audio Blob not saved', 'QuotaExceededError');}
                        try {const result = await originals.saveMedia(...args); f.realMediaSaves++; return result;}
                        catch (error) {f.realMediaErrors.push({name: error.name, code: error.code, message: error.message}); throw error;}
                    };
                    store.removeMedia = async (...args) => {
                        const f = window.__audioFault;
                        if (f.mode === 'cleanup' && String(args[2]).startsWith('audio:')) {f.cleanupFailures++; throw new DOMException('Synthetic local cleanup failure', 'UnknownError');}
                        return originals.removeMedia(...args);
                    };
                }
                window.__audioFault.mode = mode;
            }, mode);
        }
        async function prepare(mode) {
            await page.goto(base+'/sales-checkin/'); await page.locator('#checkin-workspace').waitFor({state: 'visible'});
            await page.locator('#store-search').fill('本地'); await page.locator('#store-search-results button').first().waitFor(); await page.locator('#store-search-results button').first().tap();
            await page.locator('#visit-step-1-next').tap(); await page.locator('#customer-name').fill('合成验收客户'); await page.locator('#visit-result').fill('仅本地模拟接口和合成文件，验证录音故障恢复。');
            await fault(mode);
            await page.locator('#audio-file').setInputFiles({name: 'synthetic-tone.wav', mimeType: 'audio/wav', buffer: wav});
            await page.locator('#audio-preview-list audio').waitFor();
            await page.locator('#visit-step-2-next').tap(); await page.locator('#storefront-photo').setInputFiles(path.join(__dirname, 'fixtures/demo-storefront.jpg'));
            await page.locator('[data-photo-thumbnail]').first().waitFor({state: 'visible'});
            await page.waitForFunction(() => !document.querySelector('#photo-camera-button').disabled);
            await capture('before-submit');
        }
        async function submit() {
            await page.locator('#submit-visit-button').tap(); await page.waitForFunction(() => document.body.dataset.screen === 'result');
        }
        async function syncIdle() {
            await page.waitForFunction(() => !document.querySelector('#success-retry-button').disabled);
        }
        async function acknowledged() {
            await page.waitForFunction(() => [...document.querySelectorAll('#success-audio-list strong')].some(item => item.textContent === '已收到'));
        }
        try {
            await run({page, fixture, prepare, submit, syncIdle, acknowledged, fault, capture});
            check(errors.length === 0, name+': no uncaught browser errors'); check(external.length === 0, name+': no external or map requests');
            const faults = await page.evaluate(() => window.__audioFault);
            cases.push({name, passed: true, requests, faults, errors, external, uiTrace: await page.evaluate(() => window.__audioUiTrace), records: [...records.values()].map(record => ({id: record.id, clientSubmissionId: record.clientSubmissionId, status: record.status, photos: record.photos.length, audio: record.audioSegmentIds.length})), audioAttempts: fixture.audioAttempts, creates: fixture.createAttempts, completes: fixture.completeAttempts});
        } catch (error) {
            await capture('FAILED').catch(() => {});
            cases.push({name, passed: false, error: error.stack, requests, errors, external, audioAttempts: fixture.audioAttempts,
                faults: await page.evaluate(() => window.__audioFault).catch(() => null), uiTrace: await page.evaluate(() => window.__audioUiTrace).catch(() => []),
                audio: await page.locator('#audio-preview-list audio').evaluateAll(items => items.map(item => ({src: item.src, currentTime: item.currentTime, paused: item.paused, duration: item.duration, readyState: item.readyState, error: item.error?.code}))), body: await page.locator('body').innerText().catch(() => '')});
            throw error;
        } finally {
            fixture.releaseAudio?.(); await context.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
            await fs.rm(profile, {recursive: true, force: true});
        }
    }
    try {
        await scenario('01-metadata-failure', async ({page, fixture, prepare, submit, syncIdle, acknowledged, capture}) => {
            await prepare('metadata'); await submit(); await syncIdle();
            check(fixture.createAttempts === 1 && fixture.completeAttempts === 1 && fixture.photoAttempts === 1, 'metadata failure retains one completed visit and required photo');
            if (baseline) {
                check(fixture.audioAttempts === 0, 'baseline: local metadata failure blocks all audio multipart PUTs');
                await page.locator('#success-retry-button').tap(); await syncIdle();
                check(fixture.audioAttempts === 0, 'baseline: success retry repeats the storage failure without uploading');
            } else {
                await acknowledged(); check(fixture.audioAttempts === 1, 'metadata failure still uploads the retained WAV once');
                check([...fixture.records.values()][0].audioSegmentIds.length === 1, 'server fixture acknowledges the audio on the same visit');
            }
            await capture('result');
        });
        await scenario('02-audio-quota-history', async ({page, fixture, prepare, submit, syncIdle, acknowledged, capture}) => {
            fixture.audioMode = '503'; await prepare('audio-save'); await submit(); await syncIdle();
            check(fixture.audioAttempts >= 1, 'audio Blob quota failure still attempts the first real multipart upload');
            const original = [...fixture.records.values()][0];
            const storage = await page.evaluate(async ({owner, id}) => ({drafts: (await window.SalesCheckinDraftStore.list(owner)).length,
                audio: (await window.SalesCheckinDraftStore.mediaFor(owner, id)).filter(item => item.mediaId.startsWith('audio:')).length}), {owner, id: original.clientSubmissionId});
            check(storage.drafts === 1 && storage.audio === 0, 'real IndexedDB contains the metadata and no falsely saved audio Blob');
            await page.locator('#my-records-button').tap(); await page.getByRole('tab', {name: /^待处理/}).tap();
            await page.locator('#history-content .history-record-card').first().waitFor(); await capture('pending');
            const failedAttempts = fixture.audioAttempts; fixture.audioMode = 'success';
            await page.locator('#history-content .history-record-card').first().tap();
            if (baseline) {
                await page.waitForFunction(() => document.body.dataset.screen === 'history-detail');
                check(fixture.audioAttempts === failedAttempts, 'baseline: submitted pending card opens read-only detail without retrying its audio');
                check(await page.locator('#history-detail-content button').filter({hasText: /补传|重试录音|继续处理/}).count() === 0, 'baseline: pending-record detail has no actionable upload recovery entry');
            } else {
                await page.locator('#visit-panel').waitFor({state: 'visible'}); await syncIdle();
                await acknowledged(); check(fixture.audioAttempts === failedAttempts+1, 'same-draft history recovery retains the File and uploads its exact WAV bytes');
                check(original.audioSegmentIds.length === 1, 'retry attaches exactly one recording to the original completed visit');
                check(await page.locator('[data-audio-status]').first().isVisible() && /已上传|已收到/.test(await page.locator('[data-audio-status]').first().innerText()), 'reopened evidence editor visibly acknowledges the recording');
            }
            check(fixture.createAttempts === 1 && fixture.completeAttempts === 1, 'history recovery never duplicates a visit or completion'); await capture('recovered');
        });
        await scenario('03-rejection-cleanup', async ({page, fixture, prepare, submit, syncIdle, acknowledged, fault, capture}) => {
            fixture.audioMode = '400'; await prepare(''); await submit(); await syncIdle();
            const visible = await page.locator('#success-media-note').innerText();
            if (baseline) check(!visible.includes('录音内容无效'), 'baseline: success page hides the real HTTP400 rejection behind generic pending wording');
            else check(visible.includes('录音内容无效'), 'HTTP400 audio-content rejection is explicit on the success page');
            check([...fixture.records.values()][0].status === 'SUBMITTED' && [...fixture.records.values()][0].photos.length === 1, 'invalid optional audio does not remove the visit or photo');
            await capture('specific-error');
            if (!baseline) {
                const rejectedAttempts = fixture.audioAttempts;
                await page.locator('#success-retry-button').tap();
                await page.locator('#audio-preview-list [data-audio-retry]').waitFor({state: 'visible'});
                check(fixture.audioAttempts === rejectedAttempts, 'processing a confirmed content error does not blindly resend the same recording');
                const player = page.locator('#audio-preview-list audio').first();
                check(await player.isVisible() && await player.getAttribute('src').then(value => value.startsWith('blob:')), 'rejected recording keeps a local replay control');
                await player.scrollIntoViewIfNeeded();
                const playRect = await player.boundingBox();
                // Locator action waits for scroll/layout stability before sending one trusted tap.
                await player.tap({position: {x: 22, y: playRect.height/2}});
                await page.waitForFunction(() => document.querySelector('#audio-preview-list audio').currentTime > 0);
                check(true, 'native audio playback advances using the original local WAV');
                const downloaded = page.waitForEvent('download'); await page.locator('[data-audio-download]').first().tap();
                const download = await downloaded; const destination = path.join(output, 'synthetic-original-download.wav'); await download.saveAs(destination);
                check((await fs.readFile(destination)).equals(wav), 'download preserves every byte of the rejected original recording');
                const replacement = Buffer.from(wav); replacement.writeInt16LE(2000, 44);
                fixture.expectedAudioBytes = replacement; fixture.audioMode = 'success'; await fault('cleanup');
                const chooser = page.waitForEvent('filechooser'); await page.locator('#audio-preview-list [data-audio-retry]').first().tap();
                await (await chooser).setFiles({name: 'synthetic-replacement.wav', mimeType: 'audio/wav', buffer: replacement});
                await page.waitForFunction(() => document.querySelector('#audio-preview-list').textContent.includes('synthetic-replacement.wav'));
                await page.locator('#submit-visit-button').tap(); await syncIdle(); await acknowledged();
                check(await page.evaluate(() => window.__audioFault.cleanupFailures) > 0, 'local cleanup failure was actually injected after upload acknowledgment');
                check(await page.locator('[data-audio-status]').first().isVisible() && /已上传|已收到/.test(await page.locator('[data-audio-status]').first().innerText()), 'cleanup failure leaves the visible evidence editor in an uploaded state');
                check(!await page.locator('[data-audio-retry]').first().isVisible(), 'cleanup failure does not offer a duplicate retry for acknowledged audio');
                check(fixture.createAttempts === 1 && [...fixture.records.values()][0].audioSegmentIds.length === 1, 'cleanup failure retains exactly one successful original visit and recording');
                await capture('cleanup-confirmed');
            }
        });
        if (!baseline) await scenario('04-next-visit-late-response', async ({page, fixture, prepare, submit, acknowledged, capture}) => {
            fixture.holdAudio = true; await prepare(''); await submit();
            await page.waitForFunction(() => document.querySelector('#success-retry-button').disabled);
            for (let i = 0; i < 100 && !fixture.releaseAudio; i++) await new Promise(resolve => setTimeout(resolve, 20));
            check(typeof fixture.releaseAudio === 'function', 'late-response case reached a real held multipart request');
            const original = [...fixture.records.values()][0];
            await page.locator('#new-submission-button').tap(); await page.waitForFunction(() => document.body.dataset.screen === 'visit-home');
            fixture.releaseAudio(); fixture.holdAudio = false;
            await page.waitForFunction(() => document.querySelector('#draft-save-status').textContent !== '');
            await page.getByRole('button', {name: '记录', exact: true}).tap(); await page.getByRole('tab', {name: '已提交', exact: true}).tap();
            await page.locator('#history-content .history-record-card').first().tap(); await page.waitForFunction(() => document.body.dataset.screen === 'history-detail');
            await page.waitForFunction(() => document.querySelector('#history-detail-content').textContent.includes('沟通录音 · 1 段'));
            check(fixture.records.size === 1 && original.audioSegmentIds.length === 1 && fixture.createAttempts === 1, 'late response remains attached only to the original visit after next-visit navigation');
            await capture('original-history');
        });
        if (!baseline) await scenario('05-replaced-file-reload', async ({page, fixture, prepare, submit, syncIdle, acknowledged, fault, capture}) => {
            fixture.audioMode = '400'; await prepare(''); await submit(); await syncIdle();
            await page.locator('#success-retry-button').tap();
            await page.locator('#audio-preview-list [data-audio-retry]').waitFor({state: 'visible'});
            const replacement = Buffer.from(wav); replacement.writeInt16LE(2400, 44);
            fixture.expectedAudioBytes = replacement; fixture.audioMode = '503';
            await fault('audio-save');
            const savesBeforeReplacement = await page.evaluate(() => window.__audioFault.realSaves);
            const picker = page.waitForEvent('filechooser'); await page.locator('#audio-preview-list [data-audio-retry]').first().tap();
            // The name and size are deliberately identical; the first PCM sample differs.
            await (await picker).setFiles({name: 'synthetic-tone.wav', mimeType: 'audio/wav', buffer: replacement});
            await page.waitForFunction(saves => window.__audioFault.audioStoreFailures > 0 && window.__audioFault.realSaves > saves, savesBeforeReplacement);
            const original = [...fixture.records.values()][0];
            const oldCopy = await page.evaluate(async ({owner, draftId}) => {
                const items = await window.SalesCheckinDraftStore.mediaFor(owner, draftId);
                const item = items.find(value => value.mediaId.startsWith('audio:'));
                return item ? Array.from(new Uint8Array(await item.file.arrayBuffer())) : null;
            }, {owner, draftId: original.clientSubmissionId});
            check(oldCopy && Buffer.from(oldCopy).equals(wav), 'replacement-save failure really leaves the old same-name same-size WAV in IndexedDB');
            await capture('replacement-not-saved');
            const beforeReloadAttempts = fixture.audioAttempts;
            await page.reload(); await page.waitForFunction(() => document.body.dataset.screen === 'result'); await syncIdle();
            const message = await page.locator('#success-media-note').innerText();
            check(/原文件|原件|找不到|重新选择|缺失/.test(message), 'after real reload the missing replacement is explained instead of claiming a saved file');
            check(fixture.audioAttempts === beforeReloadAttempts, 'reload never uploads the stale stored recording after an unsaved replacement');
            await capture('reload-needs-file');
            fixture.audioMode = 'success'; await fault('');
            await page.locator('#success-retry-button').tap();
            const reselect = page.waitForEvent('filechooser'); await page.locator('#audio-preview-list [data-audio-retry]').first().tap();
            await (await reselect).setFiles({name: 'synthetic-tone.wav', mimeType: 'audio/wav', buffer: replacement});
            await page.waitForFunction(() => window.__audioFault.realMediaSaves > 0);
            await page.locator('#submit-visit-button').tap(); await syncIdle(); await acknowledged();
            check(original.audioSegmentIds.length === 1 && fixture.createAttempts === 1 && fixture.completeAttempts === 1,
                'reselecting the real replacement after reload uploads new bytes to the original completed visit');
            await capture('replacement-recovered');
        });
        await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({passed: true, engine, baseline, staticRoot, sourceHashes, checks, cases,
            scope: 'Local mock APIs; real browser UI/File/Blob/multipart/IndexedDB. Synthetic WAV and fixture JPEG; no physical microphone/camera or production/map access.'}, null, 2));
        console.log(JSON.stringify({passed: true, engine, baseline, checks: checks.length, cases: cases.length, output}));
    } catch (error) {
        await fs.writeFile(path.join(output, 'results.json'), JSON.stringify({passed: false, engine, baseline, staticRoot, sourceHashes, error: error.stack, checks, cases}, null, 2)); throw error;
    }
})().catch(error => {console.error(error); process.exitCode = 1;});
