#!/usr/bin/env node
'use strict';
// Production frontend + local fictional HTTP contract. No production calls or real identities.
const { chromium, webkit } = require(process.env.PW_MODULE_PATH || 'playwright');
const assert = require('node:assert/strict'), fs = require('node:fs/promises'), path = require('node:path');
const base = process.env.CHECKIN_PREVIEW_URL || 'http://127.0.0.1:8774';
if (!['127.0.0.1', 'localhost'].includes(new URL(base).hostname)) throw Error('Local fixture only');
const output = path.resolve(process.env.QA_DIR || 'docs/qa-20260908/admin-risk');
const engine = process.env.BROWSER_ENGINE || 'chromium';
const api = '/sales-checkin/admin/api/v1', risk = api + '/risk';
const id = n => `50000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const checks = [], requests = [], errors = [];
const check = (ok, name) => { checks.push({ name, passed: !!ok }); assert.ok(ok, name); };
const group = (kind, extra = {}) => ({ id: kind === 'DEVICE' ? '1' : '2', code: kind === 'DEVICE' ? 'DEV-0001' : 'AUD-0001', kind, historyCount: 26, filterCount: 4, salespersonCount: 2, storeCount: 9, dateCount: 3, firstSubmittedAt: '2026-09-01T00:01:02Z', lastSubmittedAt: '2026-09-08T01:02:03Z', firstSalespersonName: '销售甲', reviewStatus: 'PENDING', evidenceVersion: 'b'.repeat(64), newEvidence: false, durationMs: kind === 'AUDIO' ? 701000 : null, durationSource: 'SERVER_PARSED', otherCount: 25, earlierCount: 3, segmentIds: [id(90)], ...extra });
const item = (n = 1) => ({ id: id(n), status: 'SUBMITTED', city: '杭州', salespersonId: id(501), salespersonName: '销售甲', storeName: '示例门店' + n, customerName: '示例客户', completedAt: '2026-09-08T01:02:03Z', createdAt: '2026-09-08T00:00:00Z', locationAddress: n === 1 ? '设备街道88号' : '', storeAddress: '门店地址不可冒充设备地址', longitude: 120.15, latitude: 30.25, accuracyMeters: 80, locationQuality: 'LOW_ACCURACY', photos: [], audioSegments: [], reviewStatus: 'APPROVED', riskFlags: [] });
(async () => {
 await fs.mkdir(output, { recursive: true });
 const browser = await (engine === 'webkit' ? webkit : chromium).launch(engine === 'webkit' ? { headless: true } : { headless: true, channel: 'chrome' });
 const context = await browser.newContext({ viewport: { width: 1440, height: 1080 }, locale: 'zh-CN', timezoneId: 'America/Los_Angeles' });
 const state = { reviews: [], assignments: [], address: '', conflict: false, unknown: false, slow: false, expired: false, historyEvents: null, identityEmpty: false };
 const identity = { username: '本地验收账号', allCities: true, city: '', canDeleteSubmissions: false, canManageSalespersons: false, canManageCities: false, csrfToken: 'fictional-csrf' };
 await context.route('**/*', async route => {
  const req = route.request(), url = new URL(req.url());
  if (url.origin !== new URL(base).origin) { requests.push({ external: true, url: url.origin }); return route.abort(); }
  if (!url.pathname.startsWith(api)) return route.continue();
  const p = url.pathname, params = url.searchParams, body = req.postDataJSON(); requests.push({ path: p, method: req.method(), query: [...params], body, csrf: req.headers()['x-csrf-token'] });
  const send = (json, status = 200) => route.fulfill({ status, json }).catch(() => {});
  if (p === api + '/auth/me') return send(identity);
  if (p === api + '/options') return send({ scope: identity, cities: ['杭州', '苏州'], salespersons: [{ id: id(501), name: '销售甲', city: '杭州' }, { id: id(502), name: '销售乙', city: '苏州' }] });
  if (p === api + '/submissions') return send({ scope: identity, items: [item(), { ...item(2), locationAddress: state.address }], page: 0, size: 20, totalElements: 26, totalPages: 2, firstVisitTotal: 26, revisitTotal: 0, locationAttentionTotal: 2, reviewPendingTotal: 0, missingAudioTotal: 0 });
  if (p === api + '/submissions/attendance-summary') return send({ totalVisits: 26, checkedInSalespeople: 2, pendingReviewTotal: 0, items: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
  if (p === risk + '/summaries') return send({ rulesVersion: 'DEVICE_AUDIO_V1', items: [1, 2].map(n => ({ submissionId: id(n), device: group('DEVICE'), audios: [group('AUDIO')], riskReasons: ['SHARED_DEVICE'], riskLevel: 'HIGH', reviewPending: true })) });
  if (p === risk + '/devices' || p === risk + '/audios') { if (state.expired) return send({message:'会话已失效'},401); if (params.get('q') === 'slow') await new Promise(r => setTimeout(r, 600)); return send({ items: [group(p.endsWith('/devices') ? 'DEVICE' : 'AUDIO', { code: params.get('q') === 'slow' ? 'STALE' : p.endsWith('/devices') ? 'DEV-0001' : 'AUD-0001' })], page: Number(params.get('page') || 0), size: 20, totalElements: 41, totalPages: 3 }); }
  if (p === risk + '/devices/1/identity-events') {
   const n=Number(params.get('page')||0), changes=params.get('salespersonChangesOnly')==='true', empty=state.identityEmpty;
   return send({items:empty?[]:[{id:String(100+n),occurredAt:'2026-09-08T01:02:03Z',salespersonId:id(changes?502:501),salespersonName:changes?'销售乙':'销售甲',city:'杭州',eventType:changes?'VISIBLE_ACCOUNT_CHANGED':'IDENTITY_VERIFIED',previousEventId:changes?'99':null,previousSalespersonId:changes?id(501):null,previousSalespersonName:changes?'销售甲':null,previousOccurredAt:changes?'2026-09-08T00:01:02Z':null}],page:n,size:20,totalElements:empty?0:changes?2:21,totalPages:empty?0:changes?1:2,availableEventCount:empty?0:21,firstAvailableEventAt:empty?null:'2026-09-08T00:01:02Z',completeHistory:false});
  }
  if (p.match(/\/risk\/(devices|audios)\/[12]$/)) {
   const kind = p.includes('/devices/') ? 'DEVICE' : 'AUDIO', page = Number(params.get('page') || 0), filtered = params.get('timelineScope') === 'FILTERED', changes = params.get('salespersonChangesOnly') === 'true';
   return send({ summary: group(kind, { evidenceVersion: state.conflict ? 'c'.repeat(64) : 'b'.repeat(64), reviewStatus: kind === 'DEVICE' && state.reviews.length ? state.reviews.at(-1).status : 'PENDING', otherCount: params.has('submissionId') ? 25 : null, earlierCount: params.has('submissionId') ? 3 : null }), salespeople: [{ salespersonId: id(501), salespersonName: '销售甲', count: 20, firstSubmittedAt: '2026-09-01T00:01:02Z', lastSubmittedAt: '2026-09-08T01:02:03Z' }, { salespersonId: id(599), salespersonName: '历史销售丙', count: 6, firstSubmittedAt: '2026-09-01T00:01:02Z', lastSubmittedAt: '2026-09-08T01:02:03Z' }],
    visits: { items: [{ submissionId: id(page ? 3 : 1), submittedAt: '2026-09-08T01:02:03Z', city: '杭州', salespersonId: id(501), salespersonName: '销售甲', storeName: changes ? '服务端账号变化门店' : page ? '历史门店3' : '示例门店1', locationAddress: '设备街道88号', deviceCode: kind === 'DEVICE' ? 'DEV-0001' : 'DEV-0099', matchesFilter: true, audios: [{ code: 'AUD-0001', originalFilename: '演示元数据.wav', durationMs: 701000, durationSource: 'SERVER_PARSED' }] }], page, size: 20, totalElements: changes ? 2 : filtered ? 4 : 26, totalPages: changes || filtered ? 1 : 2 },
    assignments: state.assignments, assignmentsTotal: state.assignments.length, reviews: kind === 'DEVICE' ? (state.historyEvents ? state.historyEvents.slice(0,50) : state.reviews) : [], reviewsTotal: kind === 'DEVICE' ? (state.historyEvents ? state.historyEvents.length : state.reviews.length) : 0, sha256: 'a'.repeat(64), sizeBytes: 123456, browserSummaries: ['Android / Mobile browser'] });
  }
  if (p === risk + '/devices/1/assignments' && req.method() === 'POST') { const event = { ...body, id: id(601), actor: '本地管理员', assignedAt: '2026-09-08T03:04:05Z', salespersonName: body.salespersonId ? '销售乙' : null }; state.assignments.unshift(event); return send(event); }
  if (p === risk + '/groups/DEVICE/1/reviews' && req.method() === 'GET') {const n=Number(params.get('page')||0), events=state.historyEvents||state.reviews;return send({items:events.slice(n*50,n*50+50),page:n,size:50,totalElements:events.length,totalPages:Math.ceil(events.length/50)});}
  if (p === risk + '/groups/DEVICE/1/reviews' && req.method() === 'POST') {
   if (state.unknown) { state.unknown = false; return send({ message: '测试结果不确定' }, 503); }
   if (state.conflict && body.evidenceVersion === 'b'.repeat(64)) return send({ message: '证据已经变化', code: 'TEMP_CHECKIN_CONFLICT' }, 409);
   const event = { ...body, id: id(602), actor: '本地管理员', reviewedAt: '2026-09-08T03:05:06Z' }; state.reviews.unshift(event); return send(event);
  }
  if (p.match(/\/submissions\/[a-f0-9-]+\/address\/resolve$/)) { state.address = '历史坐标补解析路66号'; return send({ submissionId: id(2), status: 'RESOLVED', locationAddress: state.address, addressSource: 'HISTORICAL_COORDINATES_RESOLVED_LATER', addressResolvedAt: '2026-09-08T03:06:07Z' }); }
  if (p.match(/\/submissions\/[a-f0-9-]+\/address$/)) return send({ status: p.includes(id(1)) ? 'RESOLVED' : 'UNRESOLVED', locationAddress: p.includes(id(1)) ? '设备街道88号' : state.address, addressSource: p.includes(id(1)) ? 'CAPTURE_SNAPSHOT' : null });
  if (p.match(/\/submissions\/[a-f0-9-]+\/reviews$/)) return send({ items: [] });
  if (p === api + '/submissions/' + id(3)) return send(item(3));
  return send({ message: 'Unknown fictional API' }, 404);
 });
 const page = await context.newPage(); page.setDefaultTimeout(12000); page.on('pageerror', e => errors.push(e.message));
 const latest = suffix => requests.filter(r => r.path === suffix).at(-1);
 try {
  await page.goto(base + '/sales-checkin/admin/'); await page.locator('#submission-rows .risk-code-button').first().waitFor();
  check((await page.locator('#filter-risk-review-status').innerText()).includes('含待复核线索'),'visit risk review selector states any matching group rather than exclusive visit verdict');
  check(await page.locator('#submission-rows tr').count() === 2, 'original visit rows remain present');
  check((await page.locator('#submission-rows').innerText()).includes('设备街道88号') && (await page.locator('#submission-rows').innerText()).includes('仅保存坐标'), 'address column displays actual device address and coordinate-only fallback');
  check(!(await page.locator('#submission-rows').innerText()).includes('门店地址不可冒充'), 'store address never substitutes for device coordinates');
  check(requests.filter(r => r.method === 'POST').length === 0, 'initial list and association loads issue no writes or geocoding');
  check((await page.locator('#submission-rows').innerText()).includes('当前：高风险'), 'batch summary displays current risk separately from original visit verdict');
  check(latest(risk + '/summaries').query.find(([k]) => k === 'submissionIds')[1].split(',').length === 2, 'one batch requests current visible submission identifiers');
  await page.locator('[data-risk-quick="device"]').click(); await page.waitForFunction(() => new URL(location.href).searchParams.get('deviceRisk') === 'SHARED');
  await page.locator('[data-risk-quick="high"]').click(); await page.locator('.risk-flag-filter summary').click();
  await page.locator('[name="riskFlags"][value="DEVICE_MULTIPLE_SALES"]').check(); await page.locator('[name="riskFlags"][value="LOCATION_UNVERIFIED"]').check();
  await page.locator('#filter-from').fill('2026-09-01'); await page.locator('#filter-city').selectOption('杭州'); await page.locator('#search-button').click();
  await page.waitForFunction(() => new URL(location.href).searchParams.getAll('riskFlags').length === 2);
  const url = new URL(page.url()), exp = new URL(await page.locator('#export-link').getAttribute('href'), base);
  check(url.searchParams.getAll('riskFlags').length === 2 && exp.searchParams.getAll('riskFlags').length === 2, 'risk flags serialize as repeated query parameters in URL and Excel');
  check(exp.searchParams.get('riskLevel') === 'HIGH' && exp.searchParams.get('deviceRisk') === 'SHARED', 'quick risk and full form conditions reach Excel');
  await page.waitForTimeout(120); check(latest(api + '/submissions/attendance-summary').query.filter(([k]) => k === 'riskFlags').length === 2, 'attendance shares complete new risk filter scope');
  await page.locator('#submission-rows [data-field="device-risk"] .risk-code-button').first().click(); await page.locator('#risk-detail-title').getByText('DEV-0001').waitFor();
  check((await page.locator('#risk-detail-body').innerText()).includes('26 次拜访') && (await page.locator('#risk-detail-body').innerText()).includes('4 次拜访'), 'drawer distinguishes complete authorized history and date city sales scope');
  check((await page.locator('#risk-detail-body').innerText()).includes('不是手机序列号'), 'browser association does not claim physical phone ownership');
  check((await page.locator('#risk-detail-body').innerText()).includes('11:41'), 'saved audio duration remains visible in visit timeline');
  await page.screenshot({ path: path.join(output, '01-device-desktop.png') });
  check(!requests.some(r=>r.path===risk+'/devices/1/identity-events'),'neither main rows nor drawer open automatically fetch identity events');
  await page.locator('.risk-account-timeline-mode[aria-label="打卡账号变化筛选"]').getByRole('button',{name:'仅看账号变化'}).click();
  await page.locator('.risk-timeline').getByText(/服务端账号变化门店/).waitFor();
  check(latest(risk+'/devices/1').query.some(([k,v])=>k==='salespersonChangesOnly'&&v==='true')&&(await page.locator('.risk-section').filter({has:page.locator('.risk-timeline')}).innerText()).includes('共 2 条'),'visit account change mode uses server full-set filtering and total, not current-page filtering');
  await page.locator('.risk-account-timeline-mode[aria-label="打卡账号变化筛选"]').getByRole('button',{name:'全部拜访',exact:true}).click();await page.locator('.risk-timeline').getByText(/示例门店1/).waitFor();
  check(!latest(risk+'/devices/1').query.some(([k])=>k==='salespersonChangesOnly'),'all visits restores unfiltered chronological context');
  await page.locator('.risk-identity-disclosure>summary').click();await page.locator('.risk-identity-events li').waitFor();
  check((await page.locator('.risk-identity-content').innerText()).includes('已采集 21 条')&&(await page.locator('.risk-identity-content').innerText()).includes('2026-09-08 09:02:03'),'explicit identity expansion displays server collected count and exact event time');
  check((await page.locator('.risk-identity-content').innerText()).includes('不代表手机归属或真实操作者')&&(await page.locator('.risk-identity-content').innerText()).includes('记录可能不完整'),'identity events are not presented as hardware ownership or complete activity history');
  await page.locator('.risk-identity-content .risk-pagination').getByRole('button',{name:'下一页'}).click();await page.locator('.risk-identity-content .risk-pagination').getByText('第 2', {exact:false}).waitFor();
  check(latest(risk+'/devices/1/identity-events').query.some(([k,v])=>k==='page'&&v==='1'),'identity events paginate independently on the server');
  await page.locator('.risk-account-timeline-mode[aria-label="身份验证事件筛选"]').getByRole('button',{name:'仅看账号变化'}).click();await page.locator('.risk-identity-events').getByText(/^上一条可见验证/).waitFor();
  check(latest(risk+'/devices/1/identity-events').query.some(([k,v])=>k==='salespersonChangesOnly'&&v==='true')&&(await page.locator('.risk-identity-events').innerText()).includes('不表示两条事件之间没有其他操作'),'identity change view uses authorized predecessor supplied by server without inventing continuity');
  await page.locator('.risk-identity-disclosure').evaluate(e=>e.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'06-identity-events.png')});
  await page.setViewportSize({width:390,height:844});await page.locator('.risk-identity-disclosure').evaluate(e=>e.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'07-identity-mobile.png')});
  check(await page.locator('.risk-identity-content').evaluate(e=>e.scrollWidth<=e.clientWidth),'390px identity events and filter controls fit inside drawer');await page.setViewportSize({width:1440,height:1080});
  state.identityEmpty=true;await page.locator('.risk-account-timeline-mode[aria-label="身份验证事件筛选"]').getByRole('button',{name:'全部验证记录'}).click();await page.locator('.risk-identity-empty').waitFor();
  check((await page.locator('.risk-identity-empty').innerText()).includes('旧版未采集')&&(await page.locator('.risk-identity-empty').innerText()).includes('不能推断未切换'),'legacy empty identity events explain collection gaps instead of claiming no account changes');
  await page.locator('.risk-identity-disclosure>summary').click();state.identityEmpty=false;

  await page.locator('.risk-ownership-disclosure:not(.risk-identity-disclosure) > summary').click();
  const af = page.locator('[data-risk-form="assignment"]'); await af.locator('[name="assignmentType"]').selectOption('PERSONAL');
  check(await af.locator('[name="salespersonId"] option').count() === 3,'ownership choices use current authorized options, not historical or inactive member snapshots');
  check(await af.locator('[name="salespersonId"]').getAttribute('required') !== null, 'personal assignment requires an explicitly selected salesperson');
  await af.getByRole('button', { name: '保存归属说明' }).click(); check(state.assignments.length === 0, 'empty assignment fields do not issue writes');
  await af.locator('[name="salespersonId"]').selectOption(id(502)); await af.locator('[name="note"]').fill('经联系，管理员登记销售乙使用。'); await af.getByRole('button', { name: '保存归属说明' }).click();
  await page.locator('.risk-operation-notice').getByText(/已保存/).waitFor();
  check(state.assignments.length === 1 && latest(risk + '/devices/1/assignments').csrf === 'fictional-csrf', 'explicit assignment sends authenticated CSRF protected write');
  check((await page.locator('.risk-section').filter({has:page.getByRole('heading',{name:/归属操作历史/})}).locator('.risk-event-list').innerText()).includes('2026-09-08 11:04:05'), 'assignment displays server audit time using Shanghai clock');
  const rf = page.locator('[data-risk-form="review"]'); await rf.locator('[name="status"]').selectOption('EXPLAINED'); await rf.locator('[name="note"]').fill('共用情况已说明，关联本身不改变原拜访结论。');
  state.conflict = true; await rf.getByRole('button', { name: '保存线索复核' }).click(); await page.locator('.risk-operation-notice').getByText(/关联证据已变化/).waitFor();
  check(state.reviews.length === 0 && (await rf.locator('[name="note"]').inputValue()).includes('共用情况'), '409 refresh preserves note and does not mark a review saved');
  state.unknown = true; await rf.getByRole('button', { name: '保存线索复核' }).click(); await rf.locator('.risk-form-feedback').getByText(/手动重试/).waitFor();
  const firstEventId = latest(risk + '/groups/DEVICE/1/reviews').body.clientEventId;
  const writeCount = requests.filter(r => r.path === risk + '/groups/DEVICE/1/reviews').length; await page.waitForTimeout(800);
  check(requests.filter(r => r.path === risk + '/groups/DEVICE/1/reviews').length === writeCount, '503 review write is never automatically retried');
  await rf.getByRole('button', { name: '保存线索复核' }).click(); await page.locator('.risk-operation-notice').getByText(/已保存/).waitFor();
  check(latest(risk + '/groups/DEVICE/1/reviews').body.clientEventId === firstEventId, 'manual retry of unchanged review reuses event idempotency key');
  check((await page.locator('#submission-rows tr').first().innerText()).includes('已核实拜访'), 'group verdict does not replace original approved visit verdict');
  await page.locator('.risk-timeline-switch').getByRole('button', { name: '日期/城市/销售筛选内' }).click(); await page.locator('#risk-detail-body .risk-pagination').getByText('共 4 条', { exact: false }).waitFor();
  check(latest(risk + '/devices/1').query.some(([k, v]) => k === 'timelineScope' && v === 'FILTERED'), 'timeline scope filter executes on server before pagination');
  await page.locator('.risk-timeline-switch').getByRole('button', { name: '可见历史', exact: true }).click(); await page.locator('#risk-detail-body .risk-pagination').getByRole('button', { name: '下一页' }).click();
  await page.locator('.risk-timeline').getByText(/历史门店3/).waitFor(); await page.locator('.risk-timeline').getByRole('button', { name: '查看对应打卡' }).click(); await page.locator('#submission-detail-dialog').waitFor({ state: 'visible' });
  check(requests.some(r => r.path === api + '/submissions/' + id(3)), 'history record absent from current list uses authenticated exact submission GET');
  await page.locator('#detail-close').click(); await page.locator('#risk-detail-close').click();
  await page.locator('#submission-rows [data-field="audio-risk"] .risk-code-button').first().click(); await page.locator('#risk-detail-title').getByText('AUD-0001').waitFor();
  check((await page.locator('.risk-context-note').innerText()).includes('更早关联打卡 3'), 'row audio context uses server prior count tied to exact submission');
  await page.keyboard.press('Escape'); check(await page.locator('#risk-detail-dialog').isHidden(), 'Escape closes native drawer');
  await page.locator('#audio-groups-tab').click(); await page.locator('#risk-audios-content .risk-code-button').waitFor();
  check(!(await page.locator('#filter-risk-review-status').innerText()).includes('含待复核线索'),'group view uses its own single review status wording');
  check(await page.locator('#filter-risk-level').isHidden() && await page.locator('#filter-device-risk').isHidden() && await page.locator('#filter-city').isVisible(), 'audio tab shows only supported group filters and retains common scope');
  check(await page.locator('#export-link').isHidden(), 'group view does not mislabel visit workbook as a group export');
  await page.locator('#risk-audios-content .risk-code-button').click(); await page.locator('#risk-detail-title').getByText('AUD-0001').waitFor();
  check((await page.locator('.risk-detail-intro').innerText()).includes('待复核'),'audio group verdict remains independent from reviewed device group');
  check(await page.locator('.risk-context-note').count() === 0 && !latest(risk + '/audios/2').query.some(([k]) => k === 'submissionId'), 'audio tab without selected visit does not invent current/prior counts');
  check((await page.locator('#risk-detail-body').innerText()).includes('11:41') && (await page.locator('#risk-detail-body').innerText()).includes('SHA-256'), 'audio evidence keeps exact duration and original-file hash');
  check((await page.locator('#risk-detail-body').innerText()).includes('DEV-0099'), 'same audio group can display distinct browser identifiers');
  await page.screenshot({ path: path.join(output, '02-audio-desktop.png') }); await page.keyboard.press('Escape');
  check(await page.locator('#risk-audios-content .risk-code-button').evaluate(e => e === document.activeElement), 'drawer Escape restores focus to the opening row');
  await page.locator('#devices-tab').click(); await page.locator('#risk-devices-content .risk-code-button').waitFor();
  await page.locator('#filter-risk-review-status').selectOption('PENDING'); await page.locator('#search-button').click(); await page.locator('#risk-devices-content .risk-code-button').waitFor();
  check(latest(risk + '/devices').query.some(([k,v])=>k==='reviewStatus'&&v==='PENDING'),'group review filter maps to reviewStatus independent of visit verdict');
  check(latest(risk + '/devices').query.some(([k, v]) => k === 'multiSalespersonOnly' && v === 'true'), 'device shared filter maps to group API without unsupported list-only flags');
  await page.locator('#risk-devices-content .risk-pagination').getByRole('button', { name: '下一页' }).click(); await page.locator('#risk-devices-content .risk-pagination').getByText('第 2', { exact: false }).waitFor();
  check(latest(risk + '/devices').query.some(([k, v]) => k === 'page' && v === '1'), 'group pagination uses whole server collection');
  await page.locator('#risk-devices-content [aria-label="关联档案排序"]').selectOption('salespersonCount'); await page.locator('#risk-devices-content .risk-pagination').getByText('第 1', { exact: false }).waitFor();
  check(latest(risk + '/devices').query.some(([k, v]) => k === 'sortBy' && v === 'salespersonCount'), 'group sorting is server-side and resets page');
  await page.locator('#filter-risk-query').fill('slow'); await page.locator('#search-button').click(); await page.locator('#filter-risk-query').fill('fresh'); await page.locator('#search-button').click(); await page.locator('#risk-devices-content .risk-code-button').getByText('DEV-0001').waitFor(); await page.waitForTimeout(700);
  check(!(await page.locator('#risk-devices-content').innerText()).includes('STALE'), 'late prior group request cannot replace current filters');
  await page.locator('#records-tab').click(); await page.locator('#submission-rows tr').nth(1).getByRole('button', { name: '查看位置', exact: true }).click();
  await page.locator('#detail-resolve-address').waitFor({ state: 'visible' });
  check(requests.filter(r => r.path?.endsWith('/address/resolve')).length === 0, 'opening address evidence remains read-only');
  await page.locator('#detail-resolve-address').click(); await page.locator('#detail-address').getByText('历史坐标补解析路66号').waitFor();
  check(requests.filter(r => r.path?.endsWith('/address/resolve')).length === 1 && (await page.locator('#detail-address-source').innerText()).includes('历史保存坐标补解析'), 'one explicit resolve attaches provenance rather than replacing original evidence');
  await page.locator('#detail-close').click();
  check((await page.locator('#submission-rows tr').nth(1).innerText()).includes('历史坐标补解析路66号'), 'resolved device address updates the exact visible row');
  await page.screenshot({ path: path.join(output, '03-visits-desktop.png'), fullPage: true });
  await page.locator('#devices-tab').click(); await page.locator('#risk-devices-content .risk-code-button').click(); await page.locator('#risk-detail-title').getByText('DEV-0001').waitFor();
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: path.join(output, '04-device-mobile.png') });
  check(await page.evaluate(() => document.querySelector('#risk-detail-dialog').getBoundingClientRect().width <= innerWidth && document.querySelector('#risk-detail-body').scrollWidth <= innerWidth), '390px drawer fits without horizontal body overflow');
  await page.setViewportSize({ width: 320, height: 740 });
  check(await page.evaluate(() => document.querySelector('#risk-detail-body').scrollWidth <= innerWidth), '320px drawer retains usable evidence and forms');
  await page.screenshot({ path: path.join(output, '05-device-small.png') });
  await page.keyboard.press('Escape');
  state.historyEvents=Array.from({length:53},(_,i)=>({id:id(700+i),status:'INCONCLUSIVE',note:'本地历史复核 '+i,actor:'本地管理员',reviewedAt:'2026-09-08T01:02:03Z'}));
  await page.locator('#risk-devices-content .risk-code-button').click(); await page.locator('#risk-detail-body').getByRole('heading',{name:'线索复核历史 · 53 条'}).waitFor();
  check(await page.locator('.risk-event-list').last().locator('li').count()===50,'first audit history renders returned 50 events without pretending that is full history');
  await page.locator('#risk-detail-body').getByRole('button',{name:'读取完整操作历史'}).click();await page.locator('#risk-detail-body').getByRole('button',{name:'读取更多历史'}).click();
  await page.waitForFunction(()=>document.querySelectorAll('.risk-event-list:last-child').length>=0 && [...document.querySelectorAll('.risk-event-list')].at(-1).children.length===53);
  check(await page.locator('.risk-event-list').last().locator('li').count()===53,'paged audit history obtains all authorized events without duplicating initial page');
  await page.keyboard.press('Escape'); state.expired=true; await page.locator('#devices-tab').click(); await page.locator('#login-dialog').waitFor({state:'visible'});
  check(await page.locator('#admin-main').isHidden() && await page.locator('#risk-detail-dialog').isHidden() && await page.locator('#risk-devices-content .risk-code-button').count()===0, 'expired risk session hides protected data and clears prior association caches');
  check(errors.length === 0, 'no uncaught page JavaScript errors'); check(!requests.some(r => r.external), 'all test requests remain local with no provider calls');
  await fs.writeFile(path.join(output, `${engine}-results.json`), JSON.stringify({ engine, checks, errors, requests }, null, 2));
  console.log(JSON.stringify({ engine, passed: checks.length, output }));
 } catch (error) { await fs.writeFile(path.join(output, `${engine}-failure.json`), JSON.stringify({ error: error.stack, checks, errors, requests }, null, 2)); await page.screenshot({ path: path.join(output, `${engine}-failure.png`), fullPage: true }).catch(() => {}); throw error; }
 finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
