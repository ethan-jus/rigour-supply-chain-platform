const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/sales-checkin/personal-history.js'), 'utf8');
const helpers = require('../../main/resources/static/sales-checkin/personal-history.js');
const NOW = Date.parse('2026-09-07T10:00:00Z');
const tick = () => new Promise((resolve) => setImmediate(resolve));
const deferred = () => { let resolve; let reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return {promise, resolve, reject}; };
const receipt = (id = 'record1', submittedAt = '2026-09-07T08:00:00Z') => ({id, clientSubmissionId: `client-${id}`, status: 'SUBMITTED', submittedAt,
    storeName: '杭州门店', city: '杭州', uploadedMedia: ['storefront-photo'], audioSegmentIds: [], visitResult: '客户计划补货'});
const page = (items, current = 0, total = items.length, totalPages = total ? 1 : 0) => ({items, page: current, size: 20, totalElements: total, totalPages});
const detail = (id = 'record1', extra = {}) => ({...receipt(id), salespersonId: 'sales1', salespersonName: '王销售', customerName: '李店长',
    locationQuality: 'MISSING', media: [], canSupplement: true, supplementUntil: '2026-09-08T08:00:00Z', ...extra});
const local = (extra = {}, owner = 'tenant1:sales1') => ({owner, updatedAt: NOW, snapshot: {savedAt: new Date(NOW).toISOString(),
    visit: {salespersonId: 'sales1', selectedStore: {id: 'store1', name: '杭州门店'}, visitResult: '拜访完成'},
    submission: {clientSubmissionId: 'client-record1', submissionKey: 'local-private-key', status: 'DRAFT', audioSegments: [], ...extra}}});

function harness(options = {}) {
    const elements = new Map();
    let activeElement = null;
    class Element {
        constructor(tag = 'div') { this.tagName = tag.toUpperCase(); this.children = []; this.attributes = new Map(); this.listeners = new Map();
            this.className = ''; this.dataset = {}; this.hidden = false; this.disabled = false; this.value = ''; this.paused = true; this.pauseCount = 0; this.text = ''; }
        get textContent() { return this.text + this.children.map((child) => child.textContent || '').join(''); }
        set textContent(value) { this.text = String(value); this.children = []; }
        append(...nodes) { nodes.forEach((node) => { this.children.push(node); node.parentElement = this; }); }
        prepend(...nodes) { this.children.unshift(...nodes); }
        replaceChildren(...nodes) { this.text = ''; this.children = []; this.append(...nodes); }
        setAttribute(name, value) { this.attributes.set(name, String(value)); if (name === 'open') this.open = true; }
        getAttribute(name) { return this.attributes.get(name) ?? null; }
        removeAttribute(name) { this.attributes.delete(name); if (name === 'src') this.src = ''; if (name === 'open') this.open = false; }
        addEventListener(name, fn) { if (!this.listeners.has(name)) this.listeners.set(name, []); this.listeners.get(name).push(fn); }
        removeEventListener(name, fn) { this.listeners.set(name, (this.listeners.get(name) || []).filter((item) => item !== fn)); }
        dispatch(name, extra = {}) { const event = {target: this, currentTarget: this, preventDefault() {}, ...extra}; (this.listeners.get(name) || []).forEach((fn) => fn(event)); }
        focus() { activeElement = this; }
        close() { this.open = false; }
        showModal() { this.open = true; }
        pause() { this.paused = true; this.pauseCount += 1; }
        load() {}
        matches(selector) {
            if (selector.startsWith('.')) return this.className.split(' ').includes(selector.slice(1));
            if (selector === '[data-history-action]') return !!this.dataset.historyAction;
            if (selector === 'a[href]') return this.tagName === 'A' && !!this.href;
            return this.tagName.toLowerCase() === selector;
        }
        querySelectorAll(selector) {
            const selectors = selector.split(',').map((item) => item.trim()); const found = [];
            const walk = (node) => node.children.forEach((child) => { if (selectors.some((s) => child.matches(s))) found.push(child); walk(child); });
            walk(this); return found;
        }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
        closest(selector) { let current = this; while (current) { if (current.matches(selector)) return current; current = current.parentElement; } return null; }
    }
    for (const id of ['personal-history-page', 'history-content', 'history-detail-page', 'history-detail-content',
        'history-calendar-dialog', 'history-calendar-content', 'history-photo-dialog', 'history-photo-content']) elements.set(id, new Element(id.includes('dialog') ? 'dialog' : 'div'));
    elements.get('personal-history-page').append(elements.get('history-content'));
    elements.get('history-detail-page').append(elements.get('history-detail-content'));
    elements.get('history-calendar-dialog').append(elements.get('history-calendar-content'));
    elements.get('history-photo-dialog').append(elements.get('history-photo-content'));
    const document = {getElementById: (id) => elements.get(id), createElement: (tag) => new Element(tag), get activeElement() { return activeElement; }};
    const requests = []; const views = []; const resumes = []; const supplements = [];
    const window = {document, location: {origin: 'https://sales.example.test'}, AbortController, scrollY: 0,
        scrollTo(x, y) { window.scrollY = y; }, requestAnimationFrame(fn) { fn(); }};
    vm.runInNewContext(source, {window, URL, URLSearchParams, Intl, Date, Set, Map, console});
    let identity = {authenticated: true, tenantId: 'tenant1', salespersonId: 'sales1', salespersonName: '王销售'};
    let records = options.records || [];
    let handler = options.request || (async (url) => url.includes('/mine?') ? page([receipt()]) : detail());
    const controller = window.SalesCheckinHistory.init({requestJson: (url, args) => { requests.push({url, args}); return handler(url, args); },
        now: () => NOW, getIdentity: () => identity, getLocalRecords: async () => records,
        onViewChange: (view) => views.push(view), onResumeLocal: async (...args) => resumes.push(args),
        onSupplement: options.onSupplement || (async (...args) => supplements.push(args))});
    const click = (selector, text, container = 'history-content') => {
        const node = elements.get(container).querySelectorAll(selector).find((item) => text === undefined || item.textContent.includes(text));
        assert.ok(node, `missing ${selector}: ${text}`); node.dispatch('click'); return node;
    };
    return {controller, requests, views, resumes, supplements, elements, window, click,
        setIdentity(value) { identity = value; }, setRecords(value) { records = value; }, setRequest(value) { handler = value; },
        text(id = 'history-content') { return elements.get(id).textContent; }};
}

test('Shanghai business days and exact timestamps do not depend on client timezone', () => {
    assert.equal(helpers.dayKey('2026-09-06T15:59:59Z'), '2026-09-06');
    assert.equal(helpers.dayKey('2026-09-06T16:00:00Z'), '2026-09-07');
    assert.equal(helpers.timestamp('2026-09-06T16:00:00Z'), '2026-09-07 00:00:00');
    assert.equal(helpers.timestamp(null), '时间未提供');
    assert.equal(helpers.shiftDay('2024-03-01', -1), '2024-02-29');
    assert.equal(helpers.validDay('2026-02-29'), false);
    assert.equal(helpers.validRange('2026-09-07', '2026-09-06', '2026-09-07'), false);
    assert.equal(helpers.validRange('2026-09-07', '2026-09-08', '2026-09-07'), false);
    assert.equal(helpers.duration(null), '时长待解析');
    assert.equal(helpers.duration(691000), '11:31');
});

test('media allowlist only accepts own authenticated same-origin variant paths', () => {
    const path = helpers.mediaUrl('r1', 'audio1', 'original');
    assert.equal(helpers.safeMediaUrl(path, 'r1', 'audio1', 'original', 'https://sales.test'), path);
    for (const unsafe of ['https://foreign.test' + path, 'javascript:alert(1)', path + '&key=secret', path.replace('/mine/', '/admin/'),
        path.replace('r1/', 'other/'), path.replace('original', 'playback')]) {
        assert.equal(helpers.safeMediaUrl(unsafe, 'r1', 'audio1', 'original', 'https://sales.test'), '');
    }
});

test('local drafts are filtered by tenant and verified salesperson; keys are not rendered', async () => {
    const h = harness({records: [local(), local({}, 'tenant2:sales1'), {...local(), snapshot: {...local().snapshot, visit: {salespersonId: 'other', selectedStore: {name: 'foreign'}}}}]});
    await h.controller.open({pendingOnly: true});
    assert.equal(h.controller.getState().pendingCount, 1);
    assert.doesNotMatch(h.text(), /local-private-key|foreign/);
    assert.match(h.text(), /本机草稿/);
});

test('history uses real server pagination and total with explicit own submitted/date filters', async () => {
    const h = harness({request: async (url) => {
        const query = new URL(url, 'https://sales.test').searchParams;
        return query.get('page') === '0' ? page([receipt('one')], 0, 21, 2) : page([receipt('two')], 1, 21, 2);
    }});
    await h.controller.open();
    const params = new URL(h.requests[0].url, 'https://sales.test').searchParams;
    assert.equal(params.get('status'), 'SUBMITTED'); assert.equal(params.get('salespersonId'), 'sales1');
    assert.equal(params.get('dateFrom'), '2026-09-07'); assert.equal(params.get('dateTo'), '2026-09-07'); assert.equal(params.get('sortDir'), 'desc');
    assert.equal(h.controller.getState().totalElements, 21);
    h.click('.history-more'); await tick();
    assert.deepEqual(Array.from(h.controller.getState().ids), ['one', 'two']);
    assert.equal(h.controller.getState().page, 1);
    assert.match(h.text(), /共 21 次拜访/); assert.match(h.text(), /全部记录/);
});

test('fast date changes cannot let an older response overwrite the new filter', async () => {
    const first = deferred(); const second = deferred();
    const h = harness({request: (url) => url.includes('dateFrom=2026-09-07') ? first.promise : second.promise});
    const opening = h.controller.open();
    h.controller.setDateRange('2026-09-06', '2026-09-06');
    second.resolve(page([receipt('new-day', '2026-09-06T09:00:00Z')])); await tick();
    first.resolve(page([receipt('old-day')])); await opening;
    assert.deepEqual(Array.from(h.controller.getState().ids), ['new-day']);
    assert.equal(h.controller.getState().filters.from, '2026-09-06');
});

test('failed history is distinct from a successful empty date', async () => {
    const h = harness({request: async () => { throw new Error('offline'); }});
    await h.controller.open();
    assert.equal(h.controller.getState().totalElements, null);
    assert.match(h.text(), /记录加载失败/); assert.doesNotMatch(h.text(), /所选日期没有已提交/);
    h.setRequest(async () => page([])); await h.controller.refresh();
    assert.equal(h.controller.getState().totalElements, 0); assert.match(h.text(), /所选日期没有已提交/);
});

test('next-page failure retains prior page and retries the same next page', async () => {
    let fail = true;
    const h = harness({request: async (url) => {
        if (url.includes('page=0')) return page([receipt('one')], 0, 21, 2);
        if (fail) throw new Error('offline');
        return page([receipt('two')], 1, 21, 2);
    }});
    await h.controller.open(); h.click('.history-more'); await tick();
    assert.deepEqual(Array.from(h.controller.getState().ids), ['one']); assert.ok(h.controller.getState().nextError);
    fail = false; h.click('.history-retry'); await tick();
    assert.deepEqual(Array.from(h.controller.getState().ids), ['one', 'two']);
    assert.equal(h.requests.filter((request) => request.url.includes('page=1')).length, 2);
});

test('foreign and mixed draft responses are rejected rather than displayed as valid history', async () => {
    const h = harness({request: async () => page([{...receipt(), salespersonId: 'other'}])});
    await h.controller.open(); assert.equal(h.controller.getState().totalElements, null); assert.ok(h.controller.getState().error);
    h.setRequest(async () => page([{...receipt(), status: 'DRAFT'}])); await h.controller.refresh();
    assert.equal(h.controller.getState().ids.length, 0); assert.ok(h.controller.getState().error);
});

test('identity reset erases DOM, local state and ignores an in-flight previous identity response', async () => {
    const pending = deferred(); const h = harness({request: () => pending.promise, records: [local()]});
    const opening = h.controller.open(); h.setIdentity({authenticated: true, tenantId: 'tenant2', salespersonId: 'sales2'});
    h.controller.resetIdentity(); pending.resolve(page([receipt()])); await opening;
    assert.equal(h.text(), ''); assert.equal(h.controller.getState().ids.length, 0); assert.equal(h.controller.getState().pendingCount, 0);
});

test('returning from detail preserves date, sort, cached pages and list scroll', async () => {
    const h = harness(); await h.controller.open(); h.controller.setSort('asc'); await tick();
    h.window.scrollY = 426; await h.controller.showDetail('record1');
    assert.equal(h.window.scrollY, 0); assert.match(h.text('history-detail-content'), /2026-09-07 16:00:00/);
    const before = h.requests.length; h.controller.backToList();
    assert.equal(h.window.scrollY, 426); assert.equal(h.controller.getState().filters.sort, 'asc');
    assert.equal(h.requests.length, before); assert.deepEqual(Array.from(h.controller.getState().ids), ['record1']);
});

test('pending result first queries the same client ID receipt and does not blindly resume', async () => {
    const h = harness({records: [local({syncState: 'UNKNOWN'})], request: async (url) => {
        if (url.includes('/by-client/')) return receipt();
        if (url.includes('/mine?')) return page([receipt()]);
        return detail();
    }});
    await h.controller.open({pendingOnly: true}); h.click('.history-record-card'); await tick();
    assert.equal(h.resumes.length, 0); assert.equal(h.controller.getState().view, 'detail');
    const lookup = h.requests.find((request) => request.url.includes('/by-client/'));
    assert.equal(lookup.args.headers['X-Submission-Key'], 'local-private-key');
});

test('unknown result and an unavailable receipt keep the local draft intact without replay', async () => {
    const h = harness({records: [local({syncState: 'UNKNOWN'})], request: async (url) => {
        if (url.includes('/by-client/')) throw new Error('timeout'); return page([]);
    }});
    await h.controller.open({pendingOnly: true}); h.click('.history-record-card'); await tick();
    assert.equal(h.resumes.length, 0); assert.match(h.text(), /暂时无法确认/); assert.equal(h.controller.getState().pendingCount, 1);
});

test('a confirmed missing receipt allows resuming the existing local request identity', async () => {
    const h = harness({records: [local({syncState: 'UNKNOWN'})], request: async (url) => {
        if (url.includes('/by-client/')) throw Object.assign(new Error('absent'), {status: 404}); return page([]);
    }});
    await h.controller.open({pendingOnly: true}); h.click('.history-record-card'); await tick();
    assert.equal(h.resumes.length, 1); assert.equal(h.resumes[0][0].snapshot.submission.clientSubmissionId, 'client-record1');
    assert.equal(h.resumes[0][1].receipt, null);
});

test('supplement entry requires server device permission, deadline and original local key', async () => {
    const h = harness({records: [local({status: 'SUBMITTED', serverId: 'record1', submissionKey: ''})]});
    await h.controller.open(); await h.controller.showDetail('record1');
    assert.doesNotMatch(h.text('history-detail-content'), /补充照片、录音或截图/); assert.match(h.text('history-detail-content'), /仅供查看/);
    h.setRecords([local({status: 'SUBMITTED', serverId: 'record1'})]); await h.controller.refresh();
    assert.match(h.text('history-detail-content'), /补充照片、录音或截图/);
    h.setRequest(async (url) => url.includes('/mine?') ? page([receipt()]) : detail('record1', {canSupplement: false}));
    h.click('.primary-button', '补充照片、录音或截图', 'history-detail-content'); await tick();
    assert.equal(h.supplements.length, 0); assert.match(h.text('history-detail-content'), /已不满足补传条件/);
});

test('an uncertain supplement reconciles server detail and does not claim successful upload', async () => {
    let detailCalls = 0;
    const h = harness({records: [local({status: 'SUBMITTED', serverId: 'record1'})], onSupplement: async () => { throw new Error('network result unknown'); },
        request: async (url) => { if (url.includes('/mine?')) return page([receipt()]); detailCalls += 1; return detail(); }});
    await h.controller.open(); await h.controller.showDetail('record1');
    h.click('.primary-button', '补充照片、录音或截图', 'history-detail-content'); await tick();
    assert.ok(detailCalls >= 3); assert.match(h.text('history-detail-content'), /结果暂未确认/);
    assert.doesNotMatch(h.text('history-detail-content'), /补传成功/);
});

test('native audio players use no preload, own URL, and pause their predecessor', async () => {
    const media = ['a1', 'a2'].map((id) => ({mediaId: id, kind: 'audio', originalFilename: `${id}.wav`, parsedDurationMs: null,
        playbackStatus: 'PENDING', originalUrl: helpers.mediaUrl('record1', id, 'original')}));
    const h = harness({request: async (url) => url.includes('/mine?') ? page([receipt()]) : detail('record1', {media})});
    await h.controller.open(); await h.controller.showDetail('record1');
    const audios = h.elements.get('history-detail-content').querySelectorAll('audio'); assert.equal(audios.length, 2);
    assert.equal(audios[0].preload, 'none'); assert.match(audios[0].src, /\/mine\/media\/a1\?variant=original/);
    audios[0].dispatch('play'); audios[1].dispatch('play'); assert.equal(audios[0].pauseCount, 1);
    h.controller.close(); assert.equal(audios[1].pauseCount, 1); assert.equal(audios[1].src, '');
});

test('calendar today/7-day presets use actual date filters and reject future days', async () => {
    const h = harness({request: async () => page([])}); await h.controller.open();
    h.click('.history-preset', '近 7 天'); await tick();
    assert.equal(h.controller.getState().filters.from, '2026-09-01'); assert.equal(h.controller.getState().filters.to, '2026-09-07');
    assert.equal(h.controller.setDateRange('2026-09-08', '2026-09-08'), false);
    h.click('.history-date-trigger'); assert.equal(h.elements.get('history-calendar-dialog').open, true);
    const days = h.elements.get('history-calendar-content').querySelectorAll('.history-calendar-day');
    assert.ok(days.some((day) => day.getAttribute('aria-label') === '2026-09-08' && day.disabled));
    assert.ok(days.some((day) => day.getAttribute('aria-label') === '2026-09-07' && !day.disabled));
});

test('pending retry button actually rechecks the uncertain receipt before resuming', async () => {
    let offline = true;
    const h = harness({records: [local({syncState: 'UNKNOWN'})], request: async (url) => {
        if (url.includes('/by-client/')) throw Object.assign(new Error('absent'), offline ? {} : {status: 404});
        return page([]);
    }});
    await h.controller.open({pendingOnly: true}); h.click('.history-record-card'); await tick();
    assert.equal(h.resumes.length, 0);
    offline = false; h.click('.history-retry'); await tick();
    assert.equal(h.resumes.length, 1); assert.equal(h.requests.filter((request) => request.url.includes('/by-client/')).length, 2);
});

test('new submission invalidation keeps filters but reloads real server history on next open', async () => {
    const h = harness(); await h.controller.open(); h.controller.setSort('asc'); await tick();
    h.window.scrollY = 120; h.controller.close(); h.controller.invalidateList();
    h.setRequest(async () => page([receipt('new-completion')]));
    await h.controller.open();
    assert.deepEqual(Array.from(h.controller.getState().ids), ['new-completion']);
    assert.equal(h.controller.getState().filters.sort, 'asc'); assert.equal(h.window.scrollY, 120);
});

test('new-store drafts remain available in the local pending tab', async () => {
    const record = local(); record.snapshot.visit.selectedStore = null; record.snapshot.store = {name: '新店示例'};
    const h = harness({records: [record]}); await h.controller.open({pendingOnly: true});
    assert.equal(h.controller.getState().pendingCount, 1); assert.match(h.text(), /新店示例/);
});

test('calendar uses the actual 4, 5 or 6 weeks required by each month', async () => {
    const h = harness({request: async () => page([])}); await h.controller.open();
    for (const [day, cells] of [['2026-09-07', 35], ['2026-08-15', 42], ['2021-02-15', 28]]) {
        h.controller.setDateRange(day, day); await tick(); h.click('.history-date-trigger');
        assert.equal(h.elements.get('history-calendar-content').querySelectorAll('.history-calendar-day').length, cells, day);
    }
});

test('a first-ever direct receipt detail loads history when returning without a prior list visit', async () => {
    const h = harness(); await h.controller.showDetail('record1');
    assert.equal(h.requests.filter((request) => request.url.includes('/submissions/mine?')).length, 0);
    assert.equal(h.controller.getState().view, 'detail');
    h.controller.backToList(); await tick();
    assert.equal(h.requests.filter((request) => request.url.includes('/submissions/mine?')).length, 1);
    assert.equal(h.controller.getState().totalElements, 1);
    assert.deepEqual(Array.from(h.controller.getState().ids), ['record1']);
    await h.controller.showDetail('record1'); h.controller.backToList(); await tick();
    assert.equal(h.requests.filter((request) => request.url.includes('/submissions/mine?')).length, 1, 'a loaded list still returns from cache');
});
