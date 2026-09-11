#!/usr/bin/env node
// Real Nginx 1.18 + Chromium against synthetic loopback routes; no Java, accounts or business writes.
import assert from 'node:assert/strict';
import { createServer, request } from 'node:http';
import { readFile, writeFile, mkdir, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createRequire } from 'node:module';

const run = promisify(execFile), require = createRequire(import.meta.url);
const { chromium } = require(process.env.PW_MODULE_PATH || 'playwright');
const moduleRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const repo = resolve(moduleRoot, '../../..');
const configPath = 'services/rigour-sales-work-service/sales-work-service/deploy/nginx/';
const dockerImage = process.env.NGINX_TEST_IMAGE || 'nginx:1.18-alpine@sha256:93baf2ec1bfefd04d29eb070900dd5d79b0f79863653453397e55a5b663a6cb1';
const previousRef = process.env.NGINX_PREVIOUS_REF || 'db8bc09';
const output = resolve(process.env.QA_OUTPUT || join(repo, 'docs/qa-20260908/admin-refresh-proxy'));
const temp = await mkdtemp(join(tmpdir(), 'checkin-admin-proxy-'));
const fixture = randomUUID(), submission = '00000000-0000-4000-8000-000000000001';
const admin = '/sales-checkin/admin/', api = `${admin}api/v1/`;
const photo = index => `${api}submissions/${submission}/media/photos/00000000-0000-4000-8000-${String(index).padStart(12, '0')}`;
const legacy = `${admin}submissions/${submission}/media/storefront-photo/thumbnail`;
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV5EAAAAASUVORK5CYII=', 'base64');
const checks = [], active = new Map(), containers = new Set();
let browser;
const check = (condition, description) => { assert.ok(condition, description); checks.push(description); };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const tagged = path => `${path}${path.includes('?') ? '&' : '?'}fixture=${fixture}`;
const script = `const fixture=new URL(location.href).searchParams.get('fixture');
const tagged=p=>p+(p.includes('?')?'&':'?')+'fixture='+fixture;
document.querySelector('#detail').href=tagged('/sales-checkin/admin/index.html?view=detail');
const images=Array.from({length:20},(_,i)=>new Promise(resolve=>{
  const img=document.createElement('img');img.alt='合成图片 '+(i+1);img.width=72;img.height=72;
  img.onload=()=>resolve(img.naturalWidth>0);img.onerror=()=>resolve(false);
  img.src=tagged('/sales-checkin/admin/api/v1/submissions/${submission}/media/photos/00000000-0000-4000-8000-'+String(i+1).padStart(12,'0')+'?thumbnail=true');
  document.querySelector('#photos').append(img);
}));
const reads=['options','submissions','submissions/attendance-summary'].map(p=>fetch(tagged('/sales-checkin/admin/api/v1/'+p)).then(r=>r.ok));
Promise.all([...images,...reads]).then(results=>{window.fixtureReady=results.every(Boolean);document.querySelector('#status').textContent=window.fixtureReady?'20张图片和统计读取成功':'资源读取失败';});`;
const html = `<!doctype html><html lang="zh"><meta charset="utf-8"><title>后台代理回归演示</title>
<link rel="stylesheet" href="${tagged(admin + 'admin.css')}"><script defer src="${tagged(admin + 'admin.js')}"></script>
<h1>后台代理回归演示</h1><p>仅合成路由和图片，用于验证刷新与限流。</p><a id="detail">打开演示详情</a>
<p id="status">正在读取资源</p><div id="photos"></div></html>`;
const upstream = createServer((incoming, outgoing) => {
    const url = new URL(incoming.url, 'http://fixture');
    if (url.searchParams.get('fixture') !== fixture) { outgoing.writeHead(404).end(); return; }
    const group = url.searchParams.get('holdGroup');
    if (group) active.set(group, (active.get(group) || 0) + 1);
    incoming.resume();
    incoming.on('end', () => {
        const image = incoming.method === 'GET' && (url.pathname.includes('/media/') || url.pathname.startsWith(admin + 'submissions/'));
        const delay = Math.min(3000, Number(url.searchParams.get('hold')) || (image ? 80 : 0));
        setTimeout(() => {
            let type = 'application/json', body = JSON.stringify({ synthetic: true, items: [], totalVisits: 20 });
            if (url.pathname === admin || url.pathname === admin + 'index.html') { type = 'text/html; charset=utf-8'; body = html; }
            else if (url.pathname.endsWith('.css')) { type = 'text/css'; body = 'body{font-family:sans-serif;padding:24px;background:#eef7ff}h1{color:rgb(19,60,63)}#photos{display:grid;grid-template-columns:repeat(5,72px);gap:12px}img{background:#133c3f}'; }
            else if (url.pathname === admin + 'admin.js') { type = 'text/javascript'; body = script; }
            else if (url.pathname.endsWith('.js')) { type = 'text/javascript'; body = '/* synthetic public asset */'; }
            else if (image) { type = 'image/png'; body = png; }
            outgoing.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' }); outgoing.end(body);
            if (group) active.set(group, active.get(group) - 1);
        }, delay);
    });
});
await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));

function get(port, path, method = 'GET') {
    return new Promise((resolve, reject) => {
        const outgoing = request({ hostname: '127.0.0.1', port, path: tagged(path), method, agent: false,
            headers: method === 'GET' || method === 'HEAD' ? {} : { 'Content-Length': 2, 'Content-Type': 'application/json' } }, incoming => {
            incoming.resume(); incoming.on('end', () => resolve(incoming.statusCode));
        });
        outgoing.on('error', reject); outgoing.setTimeout(12000, () => outgoing.destroy(new Error('Local fixture timeout')));
        outgoing.end(method === 'GET' || method === 'HEAD' ? undefined : '{}');
    });
}

async function configure(mode) {
    const folder = join(temp, mode); await mkdir(join(folder, 'snippets'), { recursive: true });
    for (const filename of ['sales-checkin-limits.conf', 'sales-checkin-locations.conf']) {
        const content = mode === 'old' ? (await run('git', ['show', `${previousRef}:${configPath}${filename}`], { cwd: repo })).stdout
            : await readFile(join(moduleRoot, 'deploy/nginx', filename), 'utf8');
        await writeFile(join(folder, filename), content.replaceAll('http://127.0.0.1:26886', `http://host.docker.internal:${upstream.address().port}`));
    }
    await writeFile(join(folder, 'snippets/sales-checkin-proxy-marker.conf'), 'proxy_set_header X-Sales-Checkin-Proxy-Marker local-fixture;\n');
    await writeFile(join(folder, 'nginx.conf'), `worker_processes 1;\nerror_log /dev/stderr warn;\nevents {worker_connections 1024;}\nhttp {access_log off; include /fixture/sales-checkin-limits.conf; server {listen 8080; server_name localhost; include /fixture/sales-checkin-locations.conf;}}\n`);
    const mounts = ['-v', `${folder}:/fixture:ro`, '-v', `${join(folder, 'snippets')}:/etc/nginx/snippets:ro`, '-v', `${join(folder, 'nginx.conf')}:/etc/nginx/nginx.conf:ro`];
    const syntax = await run('docker', ['run', '--rm', ...mounts, dockerImage, 'nginx', '-t'], { timeout: 30000 });
    check(syntax.stderr.includes('test is successful'), `${mode}: actual Nginx template syntax`);
    return mounts;
}

async function proxy(mode, mounts, test) {
    const name = `checkin-admin-proxy-${mode}-${randomUUID()}`;
    await run('docker', ['run', '--rm', '--name', name, '-d', '-p', '127.0.0.1::8080', ...mounts, dockerImage], { timeout: 30000 });
    containers.add(name);
    try {
        const port = Number((await run('docker', ['inspect', '--format', '{{(index (index .NetworkSettings.Ports "8080/tcp") 0).HostPort}}', name])).stdout.trim());
        await test(port);
    } finally { await run('docker', ['rm', '-f', name]); containers.delete(name); }
}

async function browserWorkload(port, old = false) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const origin = `http://127.0.0.1:${port}`, statuses = [];
    await context.route('**/*', route => new URL(route.request().url()).origin === origin ? route.continue() : route.abort());
    const page = await context.newPage();
    page.on('response', response => statuses.push({ path: new URL(response.url()).pathname, status: response.status() }));
    try {
        if (old) {
            await page.goto(origin + tagged(admin), { waitUntil: 'load' });
            await sleep(700); await page.reload({ waitUntil: 'load' }); await sleep(300);
            check(statuses.some(item => item.status === 503), 'old template reproduces 503 during normal page/media load and refresh');
            await page.screenshot({ path: join(output, 'old-503.png') });
            await writeFile(join(output, 'old-responses.json'), JSON.stringify(statuses, null, 2));
            return;
        }
        async function ready(label) {
            await page.waitForFunction(() => window.fixtureReady === true, null, { timeout: 10000 });
            check(await page.locator('#photos img').evaluateAll(images => images.length === 20 && images.every(image => image.complete && image.naturalWidth > 0)), `${label}: 20 images decoded`);
            check(await page.locator('h1').evaluate(node => getComputedStyle(node).color) === 'rgb(19, 60, 63)', `${label}: stylesheet applied`);
            check((await page.locator('#status').innerText()).includes('统计读取成功'), `${label}: JS and statistics finished`);
            await page.waitForTimeout(750); // Normal operator pace; not a rate-limit bypass or retry.
        }
        for (let round = 1; round <= 4; round++) {
            await page.goto(origin + tagged(admin), { waitUntil: 'load' }); await ready(`round ${round} open`);
            await page.reload({ waitUntil: 'load' }); await ready(`round ${round} refresh`);
            await page.locator('#detail').click(); await ready(`round ${round} detail`);
            await page.goBack({ waitUntil: 'load' }); await ready(`round ${round} back`);
        }
        check(statuses.every(item => item.status === 200), 'all normal browser responses are 200, with no 429/503');
        for (const path of [admin + 'admin.css', admin + 'admin.js', api + 'submissions/attendance-summary'])
            check(statuses.filter(item => item.path === path).length >= 4, `${path}: actually requested repeatedly`);
        await page.screenshot({ path: join(output, 'new-normal.png') });
        await writeFile(join(output, 'new-responses.json'), JSON.stringify(statuses, null, 2));
    } finally { await context.close(); }
}

async function burst(port, path, method, count, rejection) {
    const statuses = [];
    for (let i = 0; i < count; i++) statuses.push(await get(port, path, method));
    check(statuses.includes(200) && statuses.includes(rejection) && statuses.every(status => status === 200 || status === rejection), `${method} ${path}: excess gets ${rejection}`);
    return statuses;
}

async function concurrency(port, label, path, method, maximum) {
    const group = randomUUID(), suffix = `${path.includes('?') ? '&' : '?'}hold=1600&holdGroup=${group}`;
    const pending = Array.from({ length: maximum }, () => get(port, path + suffix, method));
    const started = Date.now();
    while ((active.get(group) || 0) < maximum && Date.now() - started < 1000) await sleep(10);
    check(active.get(group) === maximum, `${label}: ${maximum} requests occupy their allowed slots`);
    check(await get(port, path, method) === 429, `${label}: next active request returns 429`);
    check(await get(port, admin + 'admin.css') === 200 && await get(port, admin + 'admin.js') === 200, `${label}: full business slots cannot block CSS/JS`);
    check((await Promise.all(pending)).every(status => status === 200), `${label}: accepted held requests complete`);
}

try {
    await mkdir(output, { recursive: true });
    const old = await configure('old'), current = await configure('new');
    const version = (await run('docker', ['run', '--rm', dockerImage, 'nginx', '-v'])).stderr.trim();
    check(version.includes('nginx/1.18.'), 'use real Nginx 1.18');
    browser = await chromium.launch({ headless: true, ...(process.env.CHROME_BIN ? { executablePath: process.env.CHROME_BIN } : {}) });
    await proxy('old', old, port => browserWorkload(port, true)); console.log('PASS old 503 reproduction');
    await proxy('normal', current, port => browserWorkload(port)); console.log('PASS 4 browser rounds: open/20 images/refresh/detail/back');
    await proxy('write', current, async port => {
        for (let i = 0; i < 100; i++) check(await get(port, admin + (i % 2 ? 'admin.css' : 'admin.js')) === 200, 'static does not consume write quota');
        const statuses = await burst(port, api + `submissions/${submission}/review`, 'POST', 16, 429);
        check(statuses.slice(0, 11).every(status => status === 200), 'write burst remains 10 plus initial request after 100 static reads');
        for (const [path, method] of [[photo(1), 'PUT'], [legacy, 'DELETE'], [admin + 'admin.js', 'POST'], [admin + 'index.html', 'POST']])
            check(await get(port, path, method) === 429, 'non-GET cannot bypass write quota through media or static paths');
        check(await get(port, admin + 'admin.css') === 200 && await get(port, api + 'submissions/attendance-summary') === 200 && await get(port, photo(1)) === 200, 'exhausted writes leave static/read/media quotas independent');
    });
    await proxy('read', current, async port => { await burst(port, api + 'submissions/attendance-summary', 'GET', 90, 429); });
    await proxy('media', current, async port => {
        await burst(port, photo(1) + '?hold=1', 'GET', 140, 429);
        check(await get(port, legacy + '?hold=1') === 429, 'legacy and current media share the same media quota');
        check(await get(port, api + 'options') === 200 && await get(port, admin + 'admin.js') === 200, 'exhausted media cannot block options or JS');
    });
    for (const [label, path, method, maximum] of [['read', api + 'options', 'GET', 20], ['media', photo(1), 'GET', 20], ['write', api + `submissions/${submission}/review`, 'POST', 5]])
        await proxy(`conn-${label}`, current, port => concurrency(port, label, path, method, maximum));
    await proxy('public', current, async port => {
        for (let i = 0; i < 60; i++) check(await get(port, '/sales-checkin/app.js') === 200, 'public static remains available');
        const statuses = await burst(port, '/sales-checkin/api/v1/options', 'GET', 70, 503);
        check(statuses.slice(0, 21).every(status => status === 200), 'public static does not consume unchanged API burst 20');
        check(await get(port, '/sales-checkin/internal/test') === 403, 'internal route remains denied');
    });
    for (const [path, accepted, count] of [['/sales-checkin/api/v1/identity/verify', 4, 8], ['/sales-checkin/api/v1/locations/resolve', 11, 16]])
        await proxy('protected', current, async port => { const statuses = await burst(port, path, 'POST', count, 503); check(statuses.slice(0, accepted).every(status => status === 200) && statuses[accepted] === 503, `${path}: previous exact burst retained`); });
    await writeFile(join(output, 'result.json'), JSON.stringify({ passed: true, version, previousRef, syntheticOnly: true, checks: checks.length, descriptions: checks }, null, 2));
    console.log(`PASS ${checks.length} checks; results ${output}`);
} finally {
    await browser?.close();
    for (const name of containers) await run('docker', ['rm', '-f', name]).catch(() => {});
    upstream.closeAllConnections(); await new Promise(resolve => upstream.close(resolve));
    await rm(temp, { recursive: true, force: true });
}
