#!/usr/bin/env node
'use strict';
// Real admin frontend, fictional localhost APIs and deliberate read-only resource failures.
const {chromium,webkit}=require(process.env.PW_MODULE_PATH||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const base=process.env.CHECKIN_PREVIEW_URL||'http://127.0.0.1:8774';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw Error('Local fixture required');
const engine=process.env.BROWSER||'chromium';
const output=path.resolve(process.env.QA_DIR||'/tmp/checkin-admin-recovery-sort-'+engine);
const api='/sales-checkin/admin/api/v1';
const checks=[],requests=[],errors=[];
const check=(value,name)=>{checks.push({name,passed:!!value});assert.ok(value,name);};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const id=n=>'50000000-0000-4000-8000-'+String(n).padStart(12,'0');
const groups=Array.from({length:53},(_,i)=>({date:'2026-09-'+String(8-i%5).padStart(2,'0'),city:i%2?'苏州':'杭州',salespersonId:id(i+1),salespersonName:'示例销售'+String(53-i).padStart(2,'0'),visitCount:2,storeCount:1,pendingReviewCount:0,firstCheckinAt:'2026-09-08T01:02:03Z',lastCheckinAt:'2026-09-08T02:03:04Z'}));
function sorted(by='date',direction='desc'){const key={date:'date',city:'city',salesperson:'salespersonName'}[by];return [...groups].sort((a,b)=>(a[key].localeCompare(b[key])*(direction==='asc'?1:-1))||a.salespersonId.localeCompare(b.salespersonId));}
(async()=>{
 await fs.mkdir(output,{recursive:true});const browser=await (engine==='webkit'?webkit:chromium).launch({headless:true,...(engine==='webkit'?{}:{channel:'chrome'})});let page;
 async function setup(config={}){
  const context=await browser.newContext({viewport:{width:1440,height:1100},locale:'zh-CN'});
  const fixture={failResource:'',status:0,remaining:0,auth401:false,bootstrapPath:'',bootstrapRemaining:0,stylesheetDelay:0,...config};
  await context.addInitScript(()=>{window.adminInteractionEvents=[];for(const type of ['click','submit','invalid'])document.addEventListener(type,event=>{window.adminInteractionEvents.push({type,id:event.target.id,invalid:event.target.validity?.valid,scrollY});},true);});
  const identity={username:'本地排序验收',allCities:true,city:'',canDeleteSubmissions:false,canManageSalespersons:false,canManageCities:false,csrfToken:'fictional'};
  await context.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());if(url.origin!==new URL(base).origin)return route.abort();
   if(url.pathname==='/sales-checkin/admin/'){
    const response=await route.fetch();return route.fulfill({response,headers:{...response.headers(),'content-security-policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'"}});
   }
   if(fixture.failResource&&url.pathname.endsWith('/admin.'+fixture.failResource))return route.fulfill({status:503,contentType:'text/html',body:'<h1>503 Service Unavailable</h1>'});
   if(url.pathname.endsWith('/admin.css')&&fixture.stylesheetDelay)await sleep(fixture.stylesheetDelay);
   if(!url.pathname.startsWith(api))return route.continue();
   requests.push({method:req.method(),path:url.pathname,query:Object.fromEntries(url.searchParams)});
   if(req.method()!=='GET')return route.fulfill({status:503,json:{message:'模拟写操作失败'}});
   if(fixture.bootstrapPath&&url.pathname===api+fixture.bootstrapPath&&fixture.bootstrapRemaining>0){fixture.bootstrapRemaining--;return route.fulfill({status:503,contentType:'text/html',body:'<h1>503</h1>'});}
   if(url.pathname===api+'/auth/me')return route.fulfill({status:fixture.auth401?401:200,json:fixture.auth401?{message:'登录测试'}:identity});
   if(url.pathname===api+'/options')return route.fulfill({json:{scope:identity,cities:['杭州','苏州'],salespersons:[{id:id(1),name:'示例销售53',city:'杭州'}]}});
   if(url.pathname===api+'/submissions')return route.fulfill({json:{scope:identity,items:[],page:Number(url.searchParams.get('page')||0),size:20,totalElements:0,totalPages:0,firstVisitTotal:0,revisitTotal:0}});
   if(url.pathname===api+'/submissions/attendance-summary'){
    if(url.searchParams.get('q')==='obsolete-retry'||fixture.remaining>0){fixture.remaining=Math.max(0,fixture.remaining-1);return route.fulfill({status:fixture.status||503,headers:{'Retry-After':'0'},contentType:'text/html',body:'<h1>Temporary upstream failure</h1>'});}
    const by=url.searchParams.get('summarySortBy')||'date',direction=url.searchParams.get('summarySortDirection')||'desc',n=Number(url.searchParams.get('summaryPage')||0);
    return route.fulfill({json:{totalVisits:106,checkedInSalespeople:53,pendingReviewTotal:0,items:sorted(by,direction).slice(n*50,n*50+50),page:n,size:50,totalElements:53,totalPages:2}});
   }
   return route.fulfill({status:404,json:{message:'Unknown fictional API'}});
  });
  const p=await context.newPage();p.setDefaultTimeout(12000);p.on('pageerror',error=>errors.push(error.message));
  return{page:p,context,fixture};
 }
 const summaryRequests=()=>requests.filter(r=>r.path.endsWith('/attendance-summary'));
 const waitReady=p=>p.waitForFunction(()=>document.querySelector('#attendance-total').textContent==='106'&&!document.querySelector('#attendance-table-wrap').hidden);
 const clickQuery=async p=>{
  await p.waitForFunction(()=>!document.querySelector('#search-button').disabled);
  await p.locator('#search-button').scrollIntoViewIfNeeded();
  await p.evaluate(()=>new Promise(resolve=>{let previous=scrollY,stable=0;const frame=()=>{if(scrollY===previous)stable++;else stable=0;previous=scrollY;if(stable>=8)resolve();else requestAnimationFrame(frame);};requestAnimationFrame(frame);}));
  const sent=p.waitForRequest(req=>new URL(req.url()).pathname===api+'/submissions');
  await p.locator('#search-button').click();await sent;
 };
 const rowNames=p=>p.locator('#attendance-rows tr td:nth-child(3)').allTextContents();
 try{
  const t=await setup({stylesheetDelay:500});page=t.page;await page.goto(base+'/sales-checkin/admin/');await waitReady(page);
  check(await page.evaluate(()=>document.querySelector('#admin-stylesheet').sheet.cssRules.length>0&&!document.querySelector('#admin-main').hidden),'delayed stylesheet completes before initialization without a false load-failure state');
  check(await page.locator('#admin-resource-status').isHidden(),'healthy CSS and completed application hide the fallback notice under strict self-only CSP');
  check(summaryRequests().at(-1).query.summarySortBy==='date'&&summaryRequests().at(-1).query.summarySortDirection==='desc','default summary requests full-range date descending sort');
  const detailCount=requests.filter(r=>r.path===api+'/submissions').length;
  for(const by of ['date','city','salesperson']){
   for(const direction of ['asc','desc']){
    await page.locator('#attendance-next').click();await page.waitForFunction(()=>document.querySelector('#attendance-page-indicator').textContent.includes('第 2'));
    await page.locator('[data-summary-sort-by="'+by+'"]').click();await waitReady(page);
    const query=summaryRequests().at(-1).query;
    check(query.summarySortBy===by&&query.summarySortDirection===direction&&query.summaryPage==='0',by+' '+direction+' resets summary pagination and requests service-side ordering');
    check(JSON.stringify(await rowNames(page))===JSON.stringify(sorted(by,direction).slice(0,50).map(x=>x.salespersonName)),by+' '+direction+' shows the full-range ordered first page');
    check(await page.locator('[data-summary-sort-by="'+by+'"]').locator('..').getAttribute('aria-sort')===(direction==='asc'?'ascending':'descending'),by+' '+direction+' exposes accessible sort direction');
   }
  }
  check(requests.filter(r=>r.path===api+'/submissions').length===detailCount,'summary sort does not reload or reorder detail records');
  check(requests.filter(r=>r.path===api+'/submissions').every(r=>!Object.hasOwn(r.query,'summarySortBy')),'detail API stays independent from summary sorting parameters');
  let exported=new URL(await page.locator('#export-link').getAttribute('href'),base);
  check(exported.searchParams.get('summarySortBy')==='salesperson'&&exported.searchParams.get('summarySortDirection')==='desc'&&exported.searchParams.get('sortBy')==='completedAt','Excel receives independent daily-summary and detail sort parameters');
  check(new URL(page.url()).searchParams.get('summarySortBy')==='salesperson','summary sort is persisted in the browser URL');
  await page.reload();await waitReady(page);
  check(summaryRequests().at(-1).query.summarySortBy==='salesperson'&&await page.locator('[data-summary-sort-by="salesperson"]').locator('..').getAttribute('aria-sort')==='descending','reload restores sort direction and server query');
  await page.locator('#attendance-next').click();await page.waitForFunction(()=>document.querySelector('#attendance-page-indicator').textContent.includes('第 2'));
  check(JSON.stringify(await rowNames(page))===JSON.stringify(sorted('salesperson','desc').slice(50).map(x=>x.salespersonName)),'next page continues the same global sort rather than sorting visible rows');
  await page.locator('#filter-from').fill('2026-09-01');await page.locator('#filter-city').selectOption('杭州');await page.locator('#filter-salesperson').selectOption(id(1));await clickQuery(page);await waitReady(page);
  await page.locator('[data-summary-sort-by="city"]').click();await waitReady(page);
  const filtered=summaryRequests().at(-1).query;
  check(filtered.from==='2026-09-01'&&filtered.city==='杭州'&&filtered.salespersonId===id(1),'summary header preserves all applied date/city/salesperson filters');
  await page.locator('#attendance-card').evaluate(e=>e.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'01-summary-sort.png')});
  await page.locator('#attendance-rows tr').first().getByRole('button',{name:/查看明细/}).click();await waitReady(page);
  check(summaryRequests().at(-1).query.summarySortBy==='city'&&summaryRequests().at(-1).query.summarySortDirection==='asc','daily drilldown retains the chosen summary ordering');
  await page.locator('#reset-button').click();await waitReady(page);
  check(summaryRequests().at(-1).query.summarySortBy==='date'&&summaryRequests().at(-1).query.summarySortDirection==='desc','reset filters restores default date descending summary order');
  for(const status of [429,502,503]){
   const before=summaryRequests().length;t.fixture.status=status;t.fixture.remaining=2;
   await clickQuery(page);await waitReady(page);
   check(summaryRequests().length-before===3,status+' read response recovers after at most two bounded retries');
  }
  t.fixture.status=503;t.fixture.remaining=10;const beforePersistent=summaryRequests().length;
  await clickQuery(page);await page.locator('#attendance-retry').waitFor({state:'visible'});
  check(summaryRequests().length-beforePersistent===3,'persistent 503 stops after three total attempts');
  check((await page.locator('#attendance-state').innerText()).includes('服务暂时繁忙')&&!(await page.locator('#attendance-state').innerText()).includes('<h1>'),'HTML upstream errors produce a useful message without leaking markup');
  t.fixture.remaining=0;await page.locator('#attendance-retry').click();await waitReady(page);
  await page.locator('#filter-query').fill('obsolete-retry');await clickQuery(page);await page.waitForFunction(()=>!document.querySelector('#search-button').disabled);
  await page.locator('#filter-query').fill('current');await clickQuery(page);await waitReady(page);await sleep(1900);
  check(summaryRequests().filter(r=>r.query.q==='obsolete-retry').length===1,'changing filters aborts a pending retry before any old request is replayed');
  check(await page.locator('#attendance-total').innerText()==='106','aborted retry cannot replace the current result');
  await t.context.close();
  for(const extension of ['css','js']){
   const r=await setup({failResource:extension});page=r.page;const before=requests.length;
   await page.goto(base+'/sales-checkin/admin/?city='+encodeURIComponent('杭州'));
   check(await page.locator('#admin-resource-status').isVisible()&&await page.locator('#admin-resource-status a').isVisible(),extension+' resource 503 leaves a real HTML recovery link available');
   check(requests.length===before,extension+' load failure does not start authentication or business data requests');
   await page.screenshot({path:path.join(output,'02-'+extension+'-failure.png')});r.fixture.failResource='';await page.locator('#admin-resource-status a').click();await waitReady(page);
   check(await page.locator('#admin-resource-status').isHidden()&&new URL(page.url()).searchParams.get('city')==='杭州',extension+' manual reload restores styled application and retains query filters');
   await page.goto(base+'/sales-checkin/');await page.goBack();await waitReady(page);
   check(await page.locator('#admin-resource-status').isHidden()&&await page.locator('#admin-main').isVisible(),extension+' recovered page also returns from browser history without repeated initialization hooks');
   await r.context.close();
  }
  for(const bootstrapPath of ['/auth/me','/options']){
   const failed=await setup({bootstrapPath,bootstrapRemaining:3});page=failed.page;const before=requests.length;
   await page.goto(base+'/sales-checkin/admin/');await page.locator('#page-error').waitFor({state:'visible'});
   check(requests.slice(before).filter(r=>r.path===api+bootstrapPath).length===3,bootstrapPath+' bootstrap stops after bounded GET attempts');
   check(await page.locator('#login-dialog').isHidden()&&!requests.slice(before).some(r=>r.path===api+'/submissions'),bootstrapPath+' temporary failure does not log out or load uninitialized business data');
   await page.locator('#retry-button').click();await waitReady(page);
   check((await page.locator('#filter-city option').allTextContents()).includes('苏州')&&(await page.locator('#scope-username').innerText())==='本地排序验收',bootstrapPath+' manual retry reloads identity and options before showing data');
   const details=requests.filter(r=>r.path===api+'/submissions').length;await clickQuery(page);await waitReady(page);
   check(requests.filter(r=>r.path===api+'/submissions').length===details+1,bootstrapPath+' recovery binds no duplicate event handlers');
   await failed.context.close();
  }
  const login=await setup({auth401:true});page=login.page;await page.goto(base+'/sales-checkin/admin/');await page.locator('#login-dialog').waitFor({state:'visible'});
  await page.locator('#login-username').fill('local-fictional');await page.locator('#login-password').fill('local-only-test');await page.locator('#login-form button[type="submit"]').click();await sleep(2200);
  check(requests.filter(r=>r.method==='POST'&&r.path.endsWith('/auth/login')).length===1,'failed login POST is never automatically retried');
  await login.context.close();check(errors.length===0,'all exercised flows have no JavaScript exception');
  await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:true,engine,checks,requests,errors,scope:'Local actual frontend; fictional sorted API validates frontend transport/rendering, not SQL sort implementation'},null,2));console.log(JSON.stringify({passed:true,engine,checks:checks.length,output}));
 }catch(error){if(page&&!page.isClosed())await fs.writeFile(path.join(output,'diagnostic.json'),JSON.stringify(await page.evaluate(()=>({ready:document.readyState,events:window.adminInteractionEvents,form:[...document.querySelectorAll('#filter-form input')].map(e=>({id:e.id,value:e.value,valid:e.validity.valid,badInput:e.validity.badInput,message:e.validationMessage})),button:document.querySelector('#search-button')?.outerHTML,sheets:[...document.styleSheets].map(s=>({href:s.href,rules:(()=>{try{return s.cssRules.length}catch(e){return String(e)}})()})),scripts:[...document.scripts].map(s=>s.src),resources:performance.getEntriesByType('resource').map(e=>({name:e.name,transferSize:e.transferSize,responseStatus:e.responseStatus}))})),null,2)).catch(()=>{});if(page&&!page.isClosed())await page.screenshot({path:path.join(output,'FAILED.png')}).catch(()=>{});await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,checks,requests,errors,error:String(error)},null,2));throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
