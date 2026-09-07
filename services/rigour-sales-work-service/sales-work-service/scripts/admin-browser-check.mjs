#!/usr/bin/env node
// 通过真实无头 Chrome 验证后台流程；只使用本地模拟 API，不读取或写入业务数据。
import { createServer } from 'node:http';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { runInNewContext } from 'node:vm';
import assert from 'node:assert/strict';

const run = promisify(execFile);
const folder = resolve(dirname(fileURLToPath(import.meta.url)), '../src/main/resources/static/sales-checkin/admin');
const [html, script, styles] = await Promise.all(['index.html', 'admin.js', 'admin.css'].map(name => readFile(join(folder, name), 'utf8')));
const chrome = process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

function checkLocationGeometry() {
    const start = script.indexOf('    function locationComparisonGeometry(item) {');
    const end = script.indexOf('    function renderLocationComparison(item)', start);
    assert.ok(start > 0 && end > start, 'load actual pure geometry implementation');
    const geometry = runInNewContext(`${script.slice(start, end)}; locationComparisonGeometry`);
    const base = { longitude:0, latitude:0, storeLongitude:0, storeLatitude:0, locationSource:'BROWSER', accuracyMeters:20 };
    let checks = 0;
    const check = (value, description) => { assert.ok(value, description); checks++; };
    const visible = value => ['deviceX','deviceY','storeX','storeY','radius'].every(key => Number.isFinite(value[key]))
        && value.deviceX - value.radius >= 34.99 && value.deviceX + value.radius <= 365.01
        && value.deviceY - value.radius >= 34.99 && value.deviceY + value.radius <= 185.01
        && value.storeX >= 34.99 && value.storeX <= 365.01 && value.storeY >= 34.99 && value.storeY <= 185.01;
    check(geometry(base).reason === null, 'zero longitude and latitude remain valid coordinates');
    check(visible(geometry(base)), 'coincident points and precision circle fit viewbox');
    check(geometry({...base, longitude:null}).reason === 'MISSING_DEVICE', 'no invented device point');
    check(geometry({...base, storeLatitude:''}).reason === 'MISSING_STORE', 'no invented store point');
    check(geometry({...base, latitude:91}).reason === 'MISSING_DEVICE', 'reject out of range latitude');
    check(geometry({...base, longitude:'NaN'}).reason === 'MISSING_DEVICE', 'reject nonnumeric longitude');
    check(geometry({...base, longitude:' '}).reason === 'MISSING_DEVICE', 'blank coordinate is not zero');
    check(geometry({...base, longitude:false}).reason === 'MISSING_DEVICE', 'boolean coordinate is not zero');
    check(geometry({...base, locationSource:'WECHAT'}).reason === 'UNCONFIRMED_CRS', 'unconfirmed coordinate system not plotted');
    check(geometry({...base, accuracyMeters:null}).accuracy === null, 'missing precision does not become zero');
    check(geometry({...base, accuracyMeters:-1}).accuracy === null, 'negative precision is unknown');
    check(geometry({...base, accuracyMeters:1e308}).accuracy === null, 'unsupported precision cannot overflow geometry');
    check(geometry({...base, accuracyMeters:0}).radius === 0, 'zero precision retained without invented radius');
    const dateline = geometry({...base, longitude:179.9, storeLongitude:-179.9});
    check(dateline.computedMeters > 22000 && dateline.computedMeters < 23000, 'short arc across date line');
    check(visible(dateline), 'date line crossing fits viewport');
    check(visible(geometry({...base, longitude:116.4, latitude:39.9, storeLongitude:121.5, storeLatitude:31.2})), 'cross city distance fits viewport');
    check(visible(geometry({...base, accuracyMeters:10000000, storeLongitude:130, storeLatitude:40})), 'huge uncertainty circle fits viewport');
    check(visible(geometry({...base, latitude:89.9, storeLongitude:170, storeLatitude:89.9})), 'polar coordinates remain finite');
    check(geometry({...base, storeLongitude:180}).reason === 'AMBIGUOUS_DIRECTION', 'antipodal direction not invented');
    check(geometry({...base, storeLongitude:179.9}).reason === 'AMBIGUOUS_DIRECTION', 'near antipodal direction not invented');
    console.log(JSON.stringify({ok:true,geometryChecks:checks}));
}
checkLocationGeometry();
if (process.argv.includes('--geometry-only')) process.exit(0);

function browserFixture() {
    const uuid = index => `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`;
    const items = Array.from({length:24}, (_, index) => ({
        id:uuid(index + 1), status:'SUBMITTED', city:index % 2 ? '乙城市' : '甲城市', cityName:index % 2 ? '乙城市' : '甲城市',
        salespersonId:uuid(70 + index % 2), salespersonName:index % 2 ? '销售乙' : '销售甲',
        storeName:`测试门店 ${String(index).padStart(2,'0')}`, customerName:'测试客户', customerPhone:'',
        visitResult:'本地测试记录', visitOrdinal:1, visitType:'FIRST_VISIT',
        completedAt:`2026-09-07T${String(10 + Math.floor(index / 12)).padStart(2,'0')}:${String(index % 12).padStart(2,'0')}:00Z`,
        submittedAt:`2026-09-07T${String(10 + Math.floor(index / 12)).padStart(2,'0')}:${String(index % 12).padStart(2,'0')}:00Z`,
        createdAt:'2026-09-07T09:00:00Z', locationCapturedAt:'2026-09-07T09:00:00Z',
        locationReceivedAt:'2026-09-07T09:00:02Z', locationRawTimestamp:index===23?'1788771600':'1788771600000', locationSource:'BROWSER',
        locationQuality:index === 0 ? 'MISSING' : (index===23?'TIME_UNKNOWN':'STALE'), locationVerificationStatus:'UNVERIFIED', locationFailureReason:'TIMEOUT',
        longitude:index === 0 ? null : 121.123, latitude:index === 0 ? null : 31.234, accuracyMeters:500,
        storeLongitude:121.456, storeLatitude:31.456, distanceMeters:3200,
        storefrontPhotoAvailable:true, wechatScreenshotAvailable:false, audioAvailable:true,
        audioSegments:[{segmentId:uuid(100+index),available:true,originalFilename:'测试录音.m4a',parsedDurationMs:index===23?null:222000,
            contentType:'audio/mp4',playbackStatus:'READY',playbackUrl:`/sales-checkin/admin/submissions/${uuid(index+1)}/media/audio/${uuid(100+index)}?playback=true`,
            captureSource:'BROWSER_RECORDER',clientStartedAt:'2026-09-07T09:00:00Z',clientDurationMs:222000}],
        reviewStatus:'PENDING', riskLevel:'MEDIUM', riskFlags:['LOCATION_UNVERIFIED']
    }));
    window.testCalls = [];
    window.testReviewFail = true;
    const reviews = new Map();
    const identity = {username:'测试管理员',allCities:true,csrfToken:'csrf-test',canDeleteSubmissions:true,canManageSalespersons:true,canManageCities:true};
    const response = (value, status=200) => Promise.resolve(new Response(JSON.stringify(value), {status,headers:{'content-type':'application/json'}}));
    window.fetch = async (input, options={}) => {
        const url = new URL(input, location.origin);
        window.testCalls.push({url:url.href,method:options.method || 'GET',body:options.body,credentials:options.credentials,headers:options.headers});
        if (url.pathname.endsWith('/auth/me')) return response(identity);
        if (url.pathname.endsWith('/options')) return response({scope:identity,cities:['甲城市','乙城市'],salespersons:[{id:uuid(70),name:'销售甲',city:'甲城市'},{id:uuid(71),name:'销售乙',city:'乙城市'}],audioIntelligenceEnabled:false});
        if (url.pathname.endsWith('/reviews')) return response(reviews.get(url.pathname.split('/').at(-2)) || []);
        if (url.pathname.endsWith('/review')) {
            if (window.testReviewFail) { window.testReviewFail = false; return response({message:'模拟临时失败，请重试'},503); }
            const body=JSON.parse(options.body); const id=url.pathname.split('/').at(-2);
            const item=items.find(item=>item.id===id); item.reviewStatus=body.status;
            item.reviewedBy='测试管理员'; item.reviewedAt='2026-09-07T12:00:00Z';
            const event={id:body.clientEventId,status:body.status,note:body.note,reviewedBy:item.reviewedBy,reviewedAt:item.reviewedAt};
            reviews.set(id,[event]); return response(event);
        }
        if (url.pathname.endsWith('/submissions')) {
            let rows=items.filter(item=>!url.searchParams.get('reviewStatus') || item.reviewStatus===url.searchParams.get('reviewStatus'));
            const field=url.searchParams.get('sortBy')||'completedAt'; const direction=url.searchParams.get('sortDirection')==='asc'?1:-1;
            rows.sort((a,b)=>String(a[field]).localeCompare(String(b[field]))*direction || a.id.localeCompare(b.id));
            const page=Number(url.searchParams.get('page')||0); const size=Number(url.searchParams.get('size')||20);
            return response({scope:identity,items:rows.slice(page*size,(page+1)*size),page,total:rows.length,totalElements:rows.length,totalPages:Math.ceil(rows.length/size),firstVisitTotal:rows.length,revisitTotal:0,locationAttentionTotal:rows.length,reviewPendingTotal:rows.filter(item=>item.reviewStatus==='PENDING').length,missingAudioTotal:0});
        }
        return response({message:'Unexpected test route'},404);
    };
    HTMLMediaElement.prototype.play=function(){return Promise.resolve();};
    HTMLMediaElement.prototype.pause=function(){};
    HTMLMediaElement.prototype.load=function(){};
    window.browserErrors=[];
    window.addEventListener('error',event=>window.browserErrors.push(event.message));
    window.addEventListener('unhandledrejection',event=>window.browserErrors.push(String(event.reason)));
}

async function browserTests() {
    const output=document.createElement('pre'); output.id='browser-check-result'; document.body.append(output);
    let checks=0;
    const assert=(test,message)=>{if(!test)throw new Error(message);checks++;};
    const wait=async test=>{for(let n=0;n<300;n++){if(test())return;await new Promise(resolve=>setTimeout(resolve,10));}throw new Error('Timed out waiting for browser state');};
    const $=selector=>document.querySelector(selector);
    const lastList=()=>window.testCalls.filter(call=>new URL(call.url).pathname.endsWith('/submissions')).at(-1);
    const query=()=>new URL(lastList().url).searchParams;
    const idle=()=>!$('#search-button').disabled;
    try {
        await wait(()=>$('#submission-rows').children.length===20 && idle());
        assert(query().get('sortBy')==='completedAt' && query().get('sortDirection')==='desc','default server sort');
        assert($('#submission-rows').firstElementChild.querySelector('[data-field="store"]').textContent==='测试门店 23','default latest first');
        assert(document.querySelectorAll('audio').length===1 && !$('#shared-audio').getAttribute('src'),'one player with no initial audio request');
        assert($('#submission-rows').firstElementChild.querySelector('[data-field="audio"]').textContent.includes('时长待解析'),'unknown duration must not be zero');
        assert(document.querySelectorAll('.records-table thead th').length===11,'time city salesperson and store are distinct columns');
        const initialCalls=window.testCalls.length;
        $('#next-page').click(); await wait(()=>query().get('page')==='1'&&idle());
        assert($('#submission-rows').children.length===4,'pagination uses server page');
        for(const field of ['cityName','salespersonName','storeName','completedAt']) {
            $(`[data-sort-by="${field}"]`).click(); await wait(()=>query().get('sortBy')===field&&query().get('page')==='0'&&idle());
            assert(query().get('sortDirection')==='asc',`ascending ${field}`);
            assert($(`[data-sort-by="${field}"]`).closest('th').getAttribute('aria-sort')==='ascending','aria ascending');
            $(`[data-sort-by="${field}"]`).click(); await wait(()=>query().get('sortDirection')==='desc'&&idle());
            assert($(`[data-sort-by="${field}"]`).closest('th').getAttribute('aria-sort')==='descending','aria descending');
        }
        $('#filter-location-status').value='STALE'; $('#filter-review-status').value='PENDING'; $('#filter-media-status').value='HAS_AUDIO';
        $('#filter-query').value='测试'; $('#filter-form').requestSubmit(); await wait(()=>query().get('q')==='测试'&&idle());
        const exported=new URL($('#export-link').href).searchParams;
        for(const field of ['sortBy','sortDirection','locationStatus','reviewStatus','mediaStatus','q']) assert(exported.get(field)===query().get(field),`export consistency ${field}`);
        assert(!exported.has('page')&&!exported.has('size'),'export covers full filtered results');
        assert(new URL(location.href).searchParams.get('mediaStatus')==='HAS_AUDIO','filters persist in URL');
        assert(window.testCalls.slice(initialCalls).every(call=>call.credentials==='same-origin'),'same origin cookie credential flow');
        const image=$('.row-thumbnail-button img'); image.dispatchEvent(new Event('error')); image.dispatchEvent(new Event('error')); image.dispatchEvent(new Event('error'));
        assert($('.row-thumbnail-button').textContent.includes('预览不可用'),'broken thumbnail does not block row');
        $('.row-thumbnail-button').click(); assert($('#image-preview-dialog').open,'photo opens preview');
        $('#image-preview-content').dispatchEvent(new Event('error')); assert(!$('#image-preview-error').hidden,'preview error fallback');
        $('#image-preview-close').click(); await new Promise(resolve=>setTimeout(resolve,10));
        $('.row-audio-play').click(); assert($('#shared-audio').getAttribute('preload')==='none','on demand audio only');
        assert($('#shared-audio').getAttribute('src').endsWith('?playback=true'),'authenticated playback derivative preferred');
        $('#shared-audio').dispatchEvent(new Event('error')); assert($('#shared-audio-status').textContent.includes('暂时无法播放'),'audio error is recoverable');
        $('#table-wrap').scrollLeft=140;
        const priorScroll=$('#table-wrap').scrollLeft;
        $('[data-field="detail"]').click(); await wait(()=>$('#submission-detail-dialog').open);
        assert($('#shared-audio-panel').parentElement.id==='detail-audio-dock','one player moved into accessible modal');
        assert($('#detail-store-point').textContent.includes('121.456'),'store point retained separately');
        assert($('#detail-device-point').textContent.includes('121.123'),'reported device point retained separately');
        assert($('#detail-location-received-at').textContent!=='未记录','server receipt timestamp shown');
        assert($('#detail-distance').textContent.includes('不作为当前判断'),'unverified time not reported as current distance');
        assert($('#detail-raw-timestamp').textContent==='1788771600（设备原始值，时间未核验）','raw epoch seconds preserved without guessing milliseconds');
        assert($('#detail-captured-at').textContent.includes('未核验'),'unknown capture time not rendered as trusted date');
        assert($('#location-comparison-chart svg').getAttribute('viewBox')==='0 0 400 220','relative coordinate diagram renders responsive viewbox');
        assert($('#location-comparison-note').textContent.includes('3,200') && $('#location-comparison-note').textContent.includes('不代表当前到店距离'),'diagram uses backend distance and marks historical evidence');
        assert($('#location-comparison-chart').querySelectorAll('[href],[src],image,foreignObject').length===0,'relative diagram has no external requests');
        $('#review-note').value='测试：核对现场照片后确认拜访。'; $('#review-status').value='APPROVED'; $('#review-form').requestSubmit();
        await wait(()=>!$('#review-submit').disabled&&!$('#review-error').hidden);
        const firstReview=window.testCalls.filter(call=>new URL(call.url).pathname.endsWith('/review')).at(-1);
        assert(firstReview.headers['X-CSRF-Token']==='csrf-test','review keeps CSRF');
        $('#review-form').requestSubmit(); await wait(()=>window.testCalls.filter(call=>new URL(call.url).pathname.endsWith('/review')).length===2&&!$('#review-submit').disabled);
        const secondReview=window.testCalls.filter(call=>new URL(call.url).pathname.endsWith('/review')).at(-1);
        assert(JSON.parse(firstReview.body).clientEventId===JSON.parse(secondReview.body).clientEventId,'uncertain review retry preserves idempotency key');
        assert($('#result-review-pending').textContent==='23','statistics refresh after review');
        assert(!$('#submission-detail-dialog').open,'filtered-out reviewed row closes detail');
        await wait(()=>$('#shared-audio-panel').parentElement.id==='record-audio-dock');
        assert($('#table-wrap').scrollLeft===priorScroll || innerWidth>1520,'drawer preserves table horizontal context');
        assert($('#shared-audio-panel').parentElement.id==='record-audio-dock','player returns from modal');
        $('#shared-audio-close').click(); assert(!$('#shared-audio').getAttribute('src'),'stop releases media');
        assert(document.documentElement.scrollWidth<=innerWidth+1,`no page horizontal overflow at ${innerWidth}`);
        assert(getComputedStyle($('.records-table thead')).display==='table-header-group','sortable headings remain accessible on mobile');
        assert(window.browserErrors.length===0,`no browser errors: ${window.browserErrors.join(';')}`);
        const narrowLayouts=[];
        for (const width of [390,320]) {
            const frame=document.createElement('iframe');
            frame.style.cssText=`position:absolute;left:-10000px;top:0;width:${width}px;height:1000px;border:0`;
            frame.src=`${location.pathname}?layout-width=${width}`;
            document.body.append(frame);
            await wait(()=>Boolean(frame.contentDocument?.querySelector('#browser-layout-check-result')?.textContent));
            const result=JSON.parse(frame.contentDocument.querySelector('#browser-layout-check-result').textContent);
            if(!result.ok) throw new Error(`Nested viewport ${width}: ${result.error}`);
            narrowLayouts.push(result); frame.remove();
        }
        output.textContent=JSON.stringify({ok:true,checks,width:innerWidth,narrowLayouts});
    } catch(error) {output.textContent=JSON.stringify({ok:false,checks,width:innerWidth,error:error.message,browserErrors:window.browserErrors});}
}

async function browserNarrowLayoutTests() {
    const output=document.createElement('pre'); output.id='browser-layout-check-result'; document.body.append(output);
    const expected=Number(new URL(location.href).searchParams.get('layout-width'));
    let checks=0;
    const assert=(value,message)=>{if(!value)throw new Error(message);checks++;};
    try {
        for(let attempt=0;attempt<300 && !document.querySelector('#submission-rows')?.children.length;attempt++)
            await new Promise(resolve=>setTimeout(resolve,10));
        assert(innerWidth===expected,'innerWidth must equal requested nested viewport');
        assert(document.documentElement.clientWidth===expected,'document client width must match');
        assert(document.documentElement.scrollWidth<=expected,'page must not overflow horizontally');
        const form=document.querySelector('#filter-form');
        assert(getComputedStyle(form).gridTemplateColumns.split(' ').length===2,'mobile filter stays two bounded columns');
        for(const element of form.querySelectorAll('input,select')) {
            const bounds=element.getBoundingClientRect();
            assert(bounds.left>=0 && bounds.right<=expected,'filter control stays within narrow viewport');
        }
        const table=document.querySelector('#table-wrap');
        assert(table.scrollWidth>table.clientWidth && getComputedStyle(table).overflowX==='auto','wide table keeps its own horizontal scroll');
        output.textContent=JSON.stringify({ok:true,checks,width:innerWidth,viewport:'same-origin iframe'});
    } catch(error) {output.textContent=JSON.stringify({ok:false,checks,width:innerWidth,error:error.message});}
}

const server=createServer((request,response)=>{
    const url=new URL(request.url,'http://127.0.0.1');
    if(url.pathname.endsWith('/admin.css')) {response.setHeader('Content-Type','text/css');response.end(styles);return;}
    if(url.pathname.endsWith('/admin.js')) {response.setHeader('Content-Type','text/javascript');response.end(script);return;}
    if(url.pathname.includes('/media/')) {
        if (process.argv.includes('--serve') && !url.pathname.includes('000000000001/media/')) {
            response.setHeader('Content-Type','image/svg+xml');
            response.end('<svg xmlns="http://www.w3.org/2000/svg" width="320" height="240" viewBox="0 0 320 240"><rect width="320" height="240" fill="#edf3ef"/><rect x="45" y="40" width="230" height="160" rx="8" fill="#133c3f"/><rect x="65" y="102" width="86" height="98" fill="#bad2c5"/><rect x="168" y="102" width="86" height="72" fill="#e8dfcc"/><text x="160" y="78" fill="white" text-anchor="middle" font-family="sans-serif" font-size="23">测试照片</text></svg>');
        } else {response.writeHead(403);response.end();}
        return;
    }
    response.setHeader('Content-Type','text/html; charset=utf-8');
    const fixture=`<script>(${browserFixture.toString()})();</script>`;
    const testDriver=url.searchParams.has('layout-width')?browserNarrowLayoutTests:browserTests;
    const driver=`<script>document.addEventListener('DOMContentLoaded',()=>(${testDriver.toString()})());</script>`;
    response.end(html.replace('<head>','<head>'+fixture).replace('</body>',(process.argv.includes('--serve') ? '' : driver)+'</body>'));
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const address=server.address();
if (process.argv.includes('--serve')) {
    console.log(`LOCAL_FIXTURE http://127.0.0.1:${address.port}/sales-checkin/admin/`);
    await new Promise(resolve=>process.once('SIGINT',resolve));
    server.close();
    process.exit(0);
}
try {
    for(const width of (process.env.BROWSER_TEST_WIDTHS || '1440,980,500').split(',').map(Number)) {
        const profile=await mkdtemp(join(tmpdir(),'rigour-admin-browser-'));
        try {
            const {stdout}=await run(chrome,['--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check',`--user-data-dir=${profile}`,`--window-size=${width},1000`,'--force-device-scale-factor=1','--virtual-time-budget=12000','--dump-dom',`http://127.0.0.1:${address.port}/sales-checkin/admin/`],{timeout:30000,maxBuffer:4*1024*1024});
            const result=stdout.match(/<pre id="browser-check-result">([^<]*)<\/pre>/)?.[1];
            if(!result) throw new Error(`Browser did not finish at ${width}`);
            const decoded=JSON.parse(result.replaceAll('&quot;','"').replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>'));
            console.log(JSON.stringify(decoded));
            if(!decoded.ok) process.exitCode=1;
        } finally {await rm(profile,{recursive:true,force:true,maxRetries:4,retryDelay:100});}
    }
} finally {server.close();}
