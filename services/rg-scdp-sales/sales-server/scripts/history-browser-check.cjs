#!/usr/bin/env node
'use strict';
// Real mobile Chromium against the local fixture only. Production static files are never replaced.
// PW_MODULE_PATH may point to an installed Playwright module when it is not in normal Node resolution.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
let playwright;
try { playwright = require('playwright'); }
catch (error) { if (!process.env.PW_MODULE_PATH) throw new Error('Install Playwright or set PW_MODULE_PATH to its module directory.', {cause: error}); playwright = require(process.env.PW_MODULE_PATH); }
const BASE = 'http://127.0.0.1:8774';
const API = `${BASE}/sales-checkin/api/v1`;
const OUTPUT = path.resolve(__dirname, '../../../../docs/qa-20260907/history-check');
const CHROME = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const delay = () => { let resolve; const promise = new Promise((done) => { resolve = done; }); return {promise, resolve}; };
const shiftDay = (day, count) => new Date(Date.parse(`${day}T00:00:00Z`) + count * 86400000).toISOString().slice(0, 10);
const report = {baseUrl: BASE, startedAt: new Date().toISOString(), fixtureOnly: true, widths: [], passed: false};

async function runWidth(browser, width) {
    const context = await browser.newContext({viewport: {width, height: 844}, deviceScaleFactor: 1, isMobile: true, hasTouch: true,
        locale: 'zh-CN', timezoneId: 'Asia/Shanghai', permissions: ['geolocation'], geolocation: {latitude: 30.2863, longitude: 120.1389},
        reducedMotion: 'reduce'});
    const page = await context.newPage();
    const calls = []; const errors = []; const external = [];
    let failNext = false; let heldRoute; let holdReady;
    const results = {width, checks: [], screenshots: [], passed: false};
    report.widths.push(results);
    const check = (condition, description) => { assert.ok(condition, `${width}px: ${description}`); results.checks.push(description); };
    const screenshot = async (name, fullPage = false) => {
        const filename = `${width}-${name}.png`;
        await page.waitForLoadState('networkidle', {timeout: 5000});
        await page.screenshot({path: path.join(OUTPUT, filename), fullPage, animations: 'disabled'});
        results.screenshots.push(filename);
    };
    const count = (expected) => page.waitForFunction((total) => {
        const node = document.querySelector('#history-content .history-count');
        return node && node.textContent === `共 ${total} 次拜访` && !document.querySelector('#history-content .history-loading');
    }, expected);
    const cards = () => page.locator('#history-content .history-record-card');
    const noOverflow = async (description) => check(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), description);
    // Dropping cancellation is enabled only for the explicit out-of-order case. This tests the epoch guard
    // even when a transport/browser cannot actually cancel the older in-flight response.
    await page.addInitScript(() => {
        const nativeFetch = window.fetch.bind(window);
        window.__historyTestIgnoreAbort = false;
        window.__historyTestCompletions = [];
        window.fetch = (input, options) => {
            const history = new URL(typeof input === 'string' ? input : input.url, location.href).pathname.endsWith('/submissions/mine');
            const next = history && window.__historyTestIgnoreAbort ? {...options, signal: undefined} : options;
            return nativeFetch(input, next).then(async (response) => {
                if (history && window.__historyTestIgnoreAbort) {
                    await response.clone().text(); window.__historyTestCompletions.push(response.url);
                }
                return response;
            });
        };
    });
    page.on('pageerror', (error) => errors.push(error.message));
    await page.route('**/*', async (route) => {
        const url = new URL(route.request().url());
        if (!['data:', 'blob:'].includes(url.protocol) && url.origin !== BASE) { external.push(url.origin); await route.abort(); return; }
        if (url.pathname.endsWith('/submissions/mine')) {
            calls.push({query: Object.fromEntries(url.searchParams), method: route.request().method()});
            if (failNext) { failNext = false; await route.fulfill({status: 503, contentType: 'application/json', body: JSON.stringify({message: '本地验收：模拟暂时网络失败'})}); return; }
            if (holdReady) { const waiting = holdReady; holdReady = null; heldRoute = route; waiting.resolve(); return; }
        }
        await route.continue();
    });
    try {
        await page.goto(`${BASE}/sales-checkin/`, {waitUntil: 'domcontentloaded'});
        await page.locator('#nav-records-button').waitFor({state: 'visible'});
        const today = await page.evaluate(() => {
            const parts = new Intl.DateTimeFormat('en-CA', {timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit'}).formatToParts(new Date());
            const p = Object.fromEntries(parts.map((part) => [part.type, part.value])); return `${p.year}-${p.month}-${p.day}`;
        });
        const yesterday = shiftDay(today, -1); const weekStart = shiftDay(today, -6);
        const identityResponse = await context.request.get(`${API}/identity/me`); const identity = await identityResponse.json();
        const queryPage = async (from, to, sort = 'desc', number = 0) => {
            const url = `${API}/submissions/mine?${new URLSearchParams({salespersonId: identity.salespersonId, status: 'SUBMITTED', dateFrom: from, dateTo: to, sortDir: sort, page: String(number), size: '20'})}`;
            const response = await context.request.get(url); assert.equal(response.status(), 200); return response.json();
        };
        // Exercise the actual success-page entry before ever opening history, using only a new local fixture visit.
        await page.locator('#store-search-results button').first().waitFor({state: 'visible'});
        await page.locator('#store-search-results button').first().click();
        await page.locator('#visit-step-1-next').click();
        const customer = `历史入口回归 ${width} · 示例`;
        await page.locator('#customer-name').fill(customer);
        await page.locator('#visit-result').fill('本地示例：验证首次从提交成功查看明细，再返回本人历史。');
        await page.locator('#visit-step-2-next').click();
        await page.locator('#storefront-photo').setInputFiles(path.join(__dirname, 'fixtures/demo-storefront.jpg'));
        await page.locator('#photo-grid [data-photo-thumbnail]').first().waitFor({state: 'visible'});
        await page.locator('#submit-visit-button').click();
        await page.locator('#success-view-record-button').waitFor({state: 'visible'});
        check(calls.length === 0, 'first successful visit has never opened a history list');
        await page.locator('#success-view-record-button').click();
        await page.locator('#history-detail-content .history-detail-heading').waitFor();
        check((await page.locator('#history-detail-content').innerText()).includes(customer), 'success-page direct detail shows the newly submitted own visit');
        check(calls.length === 0, 'direct detail entry does not depend on a previously loaded list');
        const initial = await queryPage(today, today); const prior = await queryPage(yesterday, yesterday); const week = await queryPage(weekStart, today, 'asc');
        await page.locator('#app-back-button').click(); await count(initial.totalElements);
        check(calls.length === 1 && await cards().count() === 20, 'return from first direct detail initializes the server history page');
        check(initial.totalElements > 20, 'fixture contains more than one page today');
        check(prior.totalElements > 0, 'fixture contains yesterday records');
        await page.locator('#nav-records-button').click(); await count(initial.totalElements);
        check(await page.locator('#hero-title').innerText() === '我的打卡记录', 'real app navigation opens history title');
        check(await page.locator('.history-date-trigger').innerText() === today, 'default date is today in Shanghai');
        check(await cards().count() === 20, 'initial history renders exactly the first 20 rows');
        check(calls.at(-1).query.salespersonId === identity.salespersonId && calls.at(-1).query.status === 'SUBMITTED', 'list request is explicitly own submitted records');
        check(await page.locator('.history-identity').innerText() === `${identity.salespersonName} · 仅本人记录`, 'verified salesperson is shown without other identities');
        await page.locator('#history-content .history-record-image img').first().waitFor();
        await page.waitForFunction(() => { const image = document.querySelector('#history-content .history-record-image img'); return image?.complete && image.naturalWidth > 0; });
        await noOverflow('today list fits mobile viewport'); await screenshot('today');
        while (await page.locator('.history-more').count()) {
            const previous = await cards().count(); await page.locator('.history-more').click();
            await page.waitForFunction((before) => document.querySelectorAll('#history-content .history-record-card').length > before, previous);
        }
        check(await cards().count() === initial.totalElements, 'load-more reaches the real server total across pages');
        check(calls.some((call) => call.query.page === '1'), 'pagination requests page 1 instead of truncating client records');
        await page.getByRole('button', {name: '日期往前一天', exact: true}).click(); await count(prior.totalElements);
        check(await page.locator('.history-date-trigger').innerText() === yesterday, 'previous-day button selects yesterday');
        check((await page.locator('.history-record-time').allTextContents()).every((time) => time.startsWith(yesterday)), 'yesterday never mixes today records');
        await screenshot('yesterday');
        await page.getByRole('button', {name: '近 7 天', exact: true}).click(); await count(week.totalElements);
        check(calls.at(-1).query.dateFrom === weekStart && calls.at(-1).query.dateTo === today, '7-day shortcut includes today and six previous Shanghai days');
        await page.locator('.history-sort').selectOption('asc'); await count(week.totalElements);
        await page.waitForFunction((expected) => document.querySelector('.history-record-name')?.textContent === expected,
            week.items[0].storeName);
        check(calls.at(-1).query.sortDir === 'asc', 'sort is sent to server');
        check((await page.locator('.history-record-time').first().innerText()).startsWith(week.items[0].submittedAt ?
            new Date(Date.parse(week.items[0].submittedAt) + 8 * 3600000).toISOString().slice(0, 10) : ''), 'ascending order starts at oldest filtered day');
        await page.locator('.history-date-trigger').click(); await page.locator('#history-calendar-dialog').waitFor({state: 'visible'});
        check(await page.locator('.history-calendar-day:disabled').count() > 0, 'future dates are disabled in actual calendar');
        await noOverflow('calendar fits mobile viewport'); await screenshot('calendar');
        await page.getByRole('button', {name: '关闭日期选择', exact: true}).click();
        check(await page.locator('#history-calendar-dialog').evaluate((node) => !node.open), 'calendar close control works');
        await page.locator('.history-date-trigger').click();
        await page.getByRole('button', {name: '单日', exact: true}).click();
        await page.locator('.history-calendar-range input[type=date]').fill(yesterday);
        await page.getByRole('button', {name: '确认日期', exact: true}).click(); await count(prior.totalElements);
        check(await page.locator('.history-date-trigger').innerText() === yesterday, 'calendar single-day input applies date');
        await page.locator('.history-date-trigger').click();
        await page.getByRole('button', {name: '日期范围', exact: true}).click();
        await page.locator('.history-calendar-range input[type=date]').nth(0).fill(weekStart);
        await page.locator('.history-calendar-range input[type=date]').nth(1).fill(today);
        await page.getByRole('button', {name: '确认日期', exact: true}).click(); await count(week.totalElements);
        check(calls.at(-1).query.dateFrom === weekStart && calls.at(-1).query.dateTo === today, 'calendar range sends inclusive business date boundaries');
        check(await page.locator('.history-sort').inputValue() === 'asc', 'changing dates preserves chosen ordering');
        const withAudioIndex = week.items.findIndex((item, index) => index >= 4 && item.audioSegmentIds.length);
        check(withAudioIndex >= 0, 'filtered page contains a playable fixture record below the fold');
        const selected = week.items[withAudioIndex]; const selectedCard = cards().nth(withAudioIndex);
        await selectedCard.scrollIntoViewIfNeeded();
        const listScroll = await page.evaluate(() => window.scrollY);
        await selectedCard.click(); await page.locator('#history-detail-content .history-detail-heading').waitFor();
        check(await page.locator('#hero-title').innerText() === '打卡明细', 'real app detail heading opens');
        check(await page.locator('.history-detail-heading h2').innerText() === selected.storeName, 'detail belongs to selected history record');
        check(await page.locator('.history-detail-facts').first().innerText().then((text) => text.includes(identity.salespersonName)), 'detail keeps own verified salesperson');
        const fullTime = await page.locator('.history-detail-facts dd').first().innerText();
        check(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(fullTime), 'check-in time has exact yyyy-MM-dd HH:mm:ss formatting');
        await noOverflow('detail fits mobile viewport'); await screenshot('detail', true);
        await page.locator('.history-detail-photo').first().click();
        await page.waitForFunction(() => { const image = document.querySelector('#history-photo-content img'); return image?.complete && image.naturalWidth > 0; });
        check(await page.locator('#history-photo-dialog').evaluate((node) => node.open), 'photo opens native modal with real image');
        await screenshot('photo'); await page.getByRole('button', {name: '关闭照片', exact: true}).click();
        const player = page.locator('audio.history-player').first(); await player.scrollIntoViewIfNeeded();
        check(await player.getAttribute('preload') === 'none', 'audio is not preloaded automatically');
        await player.click({position: {x: 18, y: 18}});
        await page.waitForFunction(() => { const audio = document.querySelector('audio.history-player'); return audio && !audio.paused && audio.currentTime > 0; }, null, {timeout: 10000});
        const audioState = await player.evaluate((audio) => ({duration: audio.duration, currentTime: audio.currentTime, networkState: audio.networkState, error: audio.error?.code || null}));
        check(audioState.error === null && Math.abs(audioState.duration - 4) < 0.05, 'native player decodes and plays the four-second PCM fixture');
        results.audioPlayback = audioState; await screenshot('audio-playing');
        await page.locator('#app-back-button').click(); await page.locator('#personal-history-page').waitFor({state: 'visible'});
        await page.waitForFunction((expected) => Math.abs(window.scrollY - expected) < 3, listScroll);
        check(await page.locator('.history-sort').inputValue() === 'asc', 'back keeps sorting');
        check((await page.locator('.history-date-trigger').innerText()).includes(weekStart), 'back keeps date range');
        check(await page.locator('audio.history-player').evaluateAll((nodes) => nodes.every((audio) => audio.paused && !audio.getAttribute('src'))), 'leaving detail stops and releases audio');
        failNext = true;
        await page.getByRole('button', {name: '今天', exact: true}).click(); await page.locator('#history-content .history-error').waitFor();
        check(await cards().count() === 0, 'failed filter does not retain misleading previous rows');
        check(!(await page.locator('#history-content').innerText()).includes('所选日期没有已提交记录'), 'network failure is distinct from empty history');
        check((await page.locator('.history-count').innerText()).includes('暂不可用'), 'failed request does not fabricate a zero total');
        await screenshot('error'); await page.getByRole('button', {name: '重试', exact: true}).click(); await count(initial.totalElements);
        check(await cards().count() === 20, 'retry recovers actual server page');
        await page.evaluate(() => { window.__historyTestIgnoreAbort = true; window.__historyTestCompletions = []; });
        const older = delay(); holdReady = older;
        await page.getByRole('button', {name: '日期往前一天', exact: true}).click(); await older.promise;
        await page.getByRole('button', {name: '今天', exact: true}).click(); await count(initial.totalElements);
        await heldRoute.fulfill({status: 200, contentType: 'application/json', body: JSON.stringify(prior)}); heldRoute = null;
        await page.waitForFunction(() => window.__historyTestCompletions.length >= 2);
        await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
        check(await page.locator('.history-count').innerText() === `共 ${initial.totalElements} 次拜访`, 'late prior-date response cannot overwrite current count');
        check((await page.locator('.history-record-time').allTextContents()).every((value) => value.startsWith(today)), 'late prior-date response cannot overwrite current rows even without transport abort');
        check(await page.locator('.history-date-trigger').innerText() === today, 'late response keeps currently selected date');
        await screenshot('after-out-of-order');
        check(external.length === 0, 'all browser requests remain on local fixture origin');
        check(errors.length === 0, 'no uncaught browser application errors');
        results.serverTotals = {today: initial.totalElements, yesterday: prior.totalElements, last7Days: week.totalElements};
        results.requestCount = calls.length; results.pageErrors = errors; results.passed = true;
        process.stdout.write(JSON.stringify({width, passed: true, checks: results.checks.length, serverTotals: results.serverTotals}) + '\n');
    } catch (error) {
        results.error = error.stack || String(error); results.pageErrors = errors;
        await screenshot('FAILED', true).catch(() => {});
        throw error;
    } finally {
        if (heldRoute) await heldRoute.abort().catch(() => {});
        await context.close();
    }
}

(async () => {
    await fs.mkdir(OUTPUT, {recursive: true});
    const browser = await playwright.chromium.launch({executablePath: CHROME, headless: true,
        args: ['--no-first-run', '--no-default-browser-check', '--autoplay-policy=no-user-gesture-required']});
    try {
        for (const width of [390, 320]) await runWidth(browser, width);
        report.passed = true;
    } finally {
        report.finishedAt = new Date().toISOString();
        await browser.close();
        await fs.writeFile(path.join(OUTPUT, 'result.json'), JSON.stringify(report, null, 2) + '\n');
        const lines = ['# 本人历史手机浏览器本地验收', '', `结果：${report.passed ? '通过' : '未通过'}`, `运行时间：${report.finishedAt}`,
            `地址：${BASE}/sales-checkin/（仅本地示例数据）`, '',
            '使用真实 app.js / personal-history.js / styles.css 与独立 Chromium context；未修改生产页面来替代 UI。',
            '网络失败和乱序响应由 Playwright 仅对本人历史读请求注入。乱序场景主动忽略传输取消，以验证页面请求版本保护。',
            '音频为本地 4 秒 PCM 测试音，已从原生控件点击播放并验证解码/进度。', ''];
        for (const item of report.widths) {
            lines.push(`## ${item.width}px`, '', `${item.passed ? '通过' : '失败'}：${item.checks.length} 项`, '');
            lines.push(...item.checks.map((check) => `- ${check}`));
            if (item.error) lines.push('', '```', item.error, '```');
            lines.push('', ...item.screenshots.map((name) => `![${name}](${name})`), '');
        }
        await fs.writeFile(path.join(OUTPUT, 'README.md'), lines.join('\n'));
    }
})().catch((error) => { console.error(error.stack || error); process.exitCode = 1; });
