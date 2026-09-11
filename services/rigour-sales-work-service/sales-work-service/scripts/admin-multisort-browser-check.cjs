#!/usr/bin/env node
'use strict';
// Real static frontend; localhost-only fictional APIs sort all fixture rows before pagination.
// Validates browser transport and rendering, not the database ORDER BY implementation.
const {chromium,webkit}=require(process.env.PW_MODULE_PATH||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path');
const base=process.env.CHECKIN_PREVIEW_URL||'http://127.0.0.1:8774';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw Error('Local fixture only');
const engine=process.env.BROWSER_ENGINE||'chromium';
const output=path.resolve(process.env.QA_DIR||'/tmp/checkin-admin-multisort-'+engine);
const staticRoot=path.resolve(__dirname,'../src/main/resources/static');
const api='/sales-checkin/admin/api/v1';
const id=n=>`60000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const checks=[],requests=[],errors=[],blocked=[];
const check=(ok,name)=>{checks.push({name,passed:!!ok});assert.ok(ok,name);};
const supported=['completedAt','cityName','salespersonName','storeName','deviceId','deviceSalespersonCount','deviceVisitCount','audioDuplicateCount'];
const rows=Array.from({length:45},(_,i)=>({id:id(i+1),status:'SUBMITTED',city:i%2?'苏州':'杭州',cityName:i%2?'苏州':'杭州',salespersonId:id(501+i%4),salespersonName:['销售甲','销售甲','销售乙','销售丙'][i%4],storeName:`本地示例门店${String(i+1).padStart(2,'0')}`,customerName:'虚构客户',visitResult:'本地排序验证',createdAt:'2026-09-08T00:00:00Z',completedAt:`2026-09-${String(1+i%7).padStart(2,'0')}T${String(8+i%4).padStart(2,'0')}:02:03Z`,deviceId:`DEV-${String(1+i%4).padStart(4,'0')}`,deviceSalespersonCount:1+i%3,deviceVisitCount:2+i%5,audioDuplicateCount:i%4,photos:[],audioSegments:[],reviewStatus:'APPROVED',riskFlags:[]}));
function sorted(sort){return [...rows].sort((a,b)=>{
 for(const entry of sort){const [field,direction]=entry.split(':');const sign=direction==='desc'?-1:1,x=a[field],y=b[field];const order=typeof x==='number'?x-y:String(x).localeCompare(String(y),'zh-CN');if(order)return order*sign;if(field==='salespersonName'){const identity=a.salespersonId.localeCompare(b.salespersonId);if(identity)return identity*sign;}}
 const explicitTime=sort.some(x=>x.startsWith('completedAt:'));
 if(!explicitTime){const time=b.completedAt.localeCompare(a.completedAt);if(time)return time;}
 return a.id.localeCompare(b.id)*(explicitTime&&sort.at(-1).endsWith(':asc')?1:-1);
});}
function readSort(params){return params.getAll('sort').length?params.getAll('sort'):[`${params.get('sortBy')||'completedAt'}:${params.get('sortDirection')||'desc'}`];}
const queryOf=r=>new URLSearchParams(r.query);
(async()=>{
 await fs.mkdir(output,{recursive:true});
 const browser=await(engine==='webkit'?webkit:chromium).launch({headless:true,...(engine==='webkit'?{}:{channel:'chrome'})});
 const context=await browser.newContext({viewport:{width:1920,height:1100},locale:'zh-CN',timezoneId:'Asia/Shanghai',serviceWorkers:'block'});
 const identity={username:'本地多级排序验收',allCities:true,city:'',canDeleteSubmissions:false,canManageSalespersons:false,canManageCities:false,csrfToken:'fictional-token'};
 await context.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(url.origin!==new URL(base).origin||req.method()!=='GET'){blocked.push({method:req.method(),path:url.pathname});return route.abort();}
  if(url.pathname.startsWith(api)){
   const p=url.pathname,q=url.searchParams;requests.push({path:p,method:req.method(),query:[...q]});
   const send=json=>route.fulfill({json});
   if(p===api+'/auth/me')return send(identity);
   if(p===api+'/options')return send({scope:identity,cities:['杭州','苏州'],salespersons:[0,1,2,3].map(i=>({id:id(501+i),name:['销售甲','销售甲','销售乙','销售丙'][i],city:i===1?'苏州':'杭州'}))});
   if(p===api+'/submissions'){
    const n=Number(q.get('page')||0),size=Number(q.get('size')||20);
    return send({scope:identity,items:sorted(readSort(q)).slice(n*size,(n+1)*size),page:n,size,totalElements:rows.length,totalPages:Math.ceil(rows.length/size),firstVisitTotal:45,revisitTotal:0,locationAttentionTotal:0,reviewPendingTotal:0,missingAudioTotal:45});
   }
   if(p===api+'/submissions/attendance-summary')return send({totalVisits:45,checkedInSalespeople:4,pendingReviewTotal:0,items:[{date:'2026-09-08',city:'杭州',salespersonId:id(501),salespersonName:'销售甲',visitCount:8,storeCount:7,audioCount:5,pendingReviewCount:0,firstCheckinAt:'2026-09-08T01:02:03Z',lastCheckinAt:'2026-09-08T08:02:03Z'},{date:'2026-09-07',city:'苏州',salespersonId:id(502),salespersonName:'销售乙',visitCount:37,storeCount:20,audioCount:0,pendingReviewCount:0,firstCheckinAt:'2026-09-07T01:02:03Z',lastCheckinAt:'2026-09-07T08:02:03Z'}],page:0,size:50,totalElements:2,totalPages:1});
   if(p===api+'/risk/summaries')return send({items:[],rulesVersion:'DEVICE_AUDIO_V1'});
   return route.fulfill({status:404,json:{message:'Unknown local fixture API'}});
  }
  if(url.pathname==='/favicon.ico')return route.fulfill({status:204,body:''});
  if(url.pathname==='/local-return-target')return route.fulfill({contentType:'text/html',body:'<!doctype html><title>Local return target</title><p>本地返回测试</p>'});
  const relative=url.pathname==='/sales-checkin/admin/'?'/sales-checkin/admin/index.html':url.pathname;
  const filename=path.resolve(staticRoot,'.'+relative);
  if(!filename.startsWith(staticRoot+path.sep)||!relative.startsWith('/sales-checkin/'))return route.abort();
  try{return await route.fulfill({path:filename});}catch{return route.fulfill({status:404,body:'Unknown local static path'});}
 });
 const page=await context.newPage();page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
 const latest=p=>requests.filter(r=>r.path===p).at(-1);
 const rowIds=()=>page.locator('#submission-rows [data-field="select"]').evaluateAll(nodes=>nodes.map(n=>n.value));
 const ready=()=>page.waitForFunction(()=>!document.querySelector('#search-button').disabled&&document.querySelector('#attendance-rows').children.length===2&&!document.querySelector('#table-wrap').hidden);
 const stable=async locator=>{await locator.scrollIntoViewIfNeeded();await page.evaluate(()=>new Promise(resolve=>{let y=scrollY,n=0;const frame=()=>{n=scrollY===y?n+1:0;y=scrollY;if(n>=8)resolve();else requestAnimationFrame(frame);};requestAnimationFrame(frame);}));};
 const clickAndLoad=async locator=>{await ready();await stable(locator);const sent=page.waitForResponse(r=>new URL(r.url()).pathname===api+'/submissions'&&r.status()===200);await locator.click();await sent;await ready();};
 async function verify(sort,n,label,{canonical=true,implicit=false}={}){
  const expected=sorted(sort).slice(n*20,(n+1)*20).map(r=>r.id);
  await page.waitForFunction(ids=>JSON.stringify([...document.querySelectorAll('#submission-rows [data-field="select"]')].map(n=>n.value))===JSON.stringify(ids),expected);
  const request=queryOf(latest(api+'/submissions'));
  check(JSON.stringify(implicit?readSort(request):request.getAll('sort'))===JSON.stringify(sort),label+': '+(implicit?'implicit default server order':'ordered repeated API sort parameters'));
  check(request.get('page')===String(n),label+': correct server page');
  check(JSON.stringify(await rowIds())===JSON.stringify(expected),label+': renders complete-range server ordering');
  const uri=new URL(page.url()),exp=new URL(await page.locator('#export-link').getAttribute('href'),base);
  check(JSON.stringify(canonical?uri.searchParams.getAll('sort'):readSort(uri.searchParams))===JSON.stringify(sort),label+': URL preserves sort order');
  check(JSON.stringify(implicit?readSort(exp.searchParams):exp.searchParams.getAll('sort'))===JSON.stringify(sort),label+': Excel receives identical order');
  check(!exp.searchParams.has('page')&&!exp.searchParams.has('size')&&!request.has('sorts'),label+': export is unpaged and no internal object array leaks');
  const chipFields=await page.locator('#sort-controls [data-remove-sort]').evaluateAll(nodes=>nodes.map(n=>n.dataset.removeSort));
  check(JSON.stringify(chipFields)===JSON.stringify(implicit?[]:sort.map(x=>x.split(':')[0])),label+': '+(implicit?'implicit default is not a removable user criterion':'removable chips retain precedence'));
 }
 async function header(field,priority,direction){
  const button=page.locator(`[data-sort-by="${field}"]`);
  const text=await button.innerText(),name=await button.getAttribute('aria-label');
  check(text.includes(String(priority))&&text.includes(direction==='asc'?'↑':'↓'),field+': visible header priority and direction');
  check(Boolean(name&&name.includes(String(priority))),field+': accessible name exposes priority');
 }
 try{
  await page.goto(base+'/sales-checkin/admin/');await ready();
  await verify(['completedAt:desc'],0,'default',{canonical:false,implicit:true});
  const labels=await page.locator('.attendance-table thead th').allTextContents();
  const storeColumn=labels.findIndex(s=>s.includes('门店数')),audioColumn=labels.findIndex(s=>s.includes('录音数量'));
  check(audioColumn===storeColumn+1&&audioColumn>=0,'daily audio count immediately follows store count');
  check(await page.locator(`#attendance-rows tr:first-child td:nth-child(${audioColumn+1})`).innerText()==='5','eight visits with five recorded visits display server count five rather than segment or current-page counts');
  check(await page.locator(`#attendance-rows tr:nth-child(2) td:nth-child(${audioColumn+1})`).innerText()==='0','known zero audio count is displayed as zero');
  await clickAndLoad(page.locator('[data-sort-by="salespersonName"]'));
  await verify(['salespersonName:asc'],0,'first salesperson click replaces default');await header('salespersonName',1,'asc');
  await clickAndLoad(page.locator('[data-sort-by="completedAt"]'));
  await verify(['salespersonName:asc','completedAt:asc'],0,'time appends without Shift');await header('salespersonName',1,'asc');await header('completedAt',2,'asc');
  await clickAndLoad(page.locator('[data-sort-by="salespersonName"]'));
  await verify(['salespersonName:desc','completedAt:asc'],0,'primary toggles in place');
  await clickAndLoad(page.locator('[data-sort-by="completedAt"]'));
  const active=['salespersonName:desc','completedAt:desc'];
  await verify(active,0,'secondary toggles in place');await header('salespersonName',1,'desc');await header('completedAt',2,'desc');
  await page.locator('#sort-controls').evaluate(e=>e.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'01-multisort-desktop.png')});
  await clickAndLoad(page.locator('#next-page'));await verify(active,1,'second page');
  const sameNameRows=(await rowIds()).map(id=>rows.find(row=>row.id===id)).filter(row=>row.salespersonName==='销售甲');
  const identityRuns=sameNameRows.filter((row,i)=>i===0||row.salespersonId!==sameNameRows[i-1].salespersonId).map(row=>row.salespersonId);
  check(new Set(sameNameRows.map(row=>row.salespersonId)).size===2&&new Set(identityRuns).size===identityRuns.length,'same-name accounts remain distinct contiguous server groups before explicit time ordering');
  await page.reload();await ready();await verify(active,1,'refresh on second page');
  await page.goto(base+'/local-return-target');await page.goBack();await ready();await verify(active,1,'browser return restores page and sort');
  const beforeSummary=requests.filter(r=>r.path===api+'/submissions').length;
  const summaryResponse=page.waitForResponse(r=>new URL(r.url()).pathname===api+'/submissions/attendance-summary');
  await page.locator('[data-summary-sort-by="city"]').click();await summaryResponse;await ready();
  check(requests.filter(r=>r.path===api+'/submissions').length===beforeSummary,'daily summary sorting never replaces detail sort');
  let exp=new URL(await page.locator('#export-link').getAttribute('href'),base);
  check(exp.searchParams.get('summarySortBy')==='city'&&exp.searchParams.get('summarySortDirection')==='asc'&&JSON.stringify(exp.searchParams.getAll('sort'))===JSON.stringify(active),'Excel retains independent summary and detail order');
  await page.locator('#filter-from').fill('2026-09-01');await page.locator('#filter-city').selectOption('杭州');await page.locator('#filter-query').fill('本地');
  await clickAndLoad(page.locator('#search-button'));await verify(active,0,'filter search resets page but preserves sorting');
  const request=queryOf(latest(api+'/submissions'));exp=new URL(await page.locator('#export-link').getAttribute('href'),base);
  check(request.get('from')==='2026-09-01'&&request.get('city')==='杭州'&&request.get('q')==='本地'&&exp.searchParams.get('city')==='杭州','combined business filters survive sorting and export');
  await clickAndLoad(page.locator('#next-page'));
  await clickAndLoad(page.locator('#sort-controls [data-remove-sort="salespersonName"]'));
  await verify(['completedAt:desc'],0,'remove primary promotes secondary');await header('completedAt',1,'desc');
  await clickAndLoad(page.locator('[data-sort-by="cityName"]'));
  await clickAndLoad(page.locator('#reset-sort-button'));
  await verify(['completedAt:desc'],0,'restore time sorting',{canonical:false,implicit:true});
  check(queryOf(latest(api+'/submissions')).get('city')==='杭州','reset sorting preserves applied city filter');
  await clickAndLoad(page.locator('[data-sort-by="salespersonName"]'));
  await verify(['salespersonName:asc'],0,'first click after reset replaces implicit default');
  await clickAndLoad(page.locator('#sort-controls [data-remove-sort="salespersonName"]'));
  await verify(['completedAt:desc'],0,'remove final criterion restores default',{canonical:false,implicit:true});
  const maximum=supported.map((field,i)=>field+':'+(i%2?'asc':'desc'));
  const params=new URLSearchParams();maximum.forEach(x=>params.append('sort',x));
  await page.goto(base+'/sales-checkin/admin/?'+params);await ready();
  const restored=queryOf(latest(api+'/submissions')).getAll('sort');
  check(JSON.stringify(restored)===JSON.stringify(maximum),'URL restoration preserves all eight legal criteria in exact priority order');
  check(restored.every(x=>supported.includes(x.split(':')[0])&&['asc','desc'].includes(x.split(':')[1])),'unsupported or duplicate URL sort entries never reach the API');
  check(await page.locator('#sort-controls [data-remove-sort]').count()===8,'all eight restored criteria can be removed from the sort bar');
  await page.setViewportSize({width:390,height:844});await page.locator('#sort-controls').evaluate(e=>e.scrollIntoView({block:'center'}));
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'eight criteria and controls do not cause mobile document overflow');
  await page.screenshot({path:path.join(output,'02-multisort-mobile.png')});
  const invalid=new URLSearchParams(params);invalid.append('sort','unknown:asc');invalid.append('sort','cityName:desc');
  await page.goto(base+'/sales-checkin/admin/?'+invalid);await ready();
  const safe=queryOf(latest(api+'/submissions'));
  check(safe.getAll('sort').length<=8&&new Set(safe.getAll('sort').map(x=>x.split(':')[0])).size===safe.getAll('sort').length&&safe.getAll('sort').every(x=>supported.includes(x.split(':')[0])&&['asc','desc'].includes(x.split(':')[1])),'invalid duplicate and excessive URL groups never send unsupported criteria');
  await page.goto(base+'/sales-checkin/admin/?sortBy=storeName&sortDirection=desc');await ready();
  check(JSON.stringify(queryOf(latest(api+'/submissions')).getAll('sort'))===JSON.stringify(['storeName:desc']),'legacy single-sort bookmark is read into new repeated-sort transport');
  check(errors.length===0,'no browser JavaScript errors');check(blocked.length===0,'no nonlocal calls or business writes');
  await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:true,engine,checks,requests,errors,blocked,scope:'Local production static UI + fictional whole-set sorted responses; SQL and XLSX content verified separately'},null,2));
  console.log(JSON.stringify({passed:true,engine,checks:checks.length,output}));
 }catch(error){await page.screenshot({path:path.join(output,'FAILED.png'),fullPage:true}).catch(()=>{});await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,engine,error:error.stack,checks,requests,errors,blocked},null,2));throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
