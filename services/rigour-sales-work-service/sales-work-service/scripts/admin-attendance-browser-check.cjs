#!/usr/bin/env node
'use strict';
// Real admin UI with fictional local API responses; no production credentials or business writes.
const {chromium}=require(process.env.PW_MODULE_PATH||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const base=process.env.CHECKIN_PREVIEW_URL||'http://127.0.0.1:8774';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw Error('Local fictional fixture required');
const output=path.resolve(process.env.QA_DIR||'docs/qa-20260908/admin-attendance');
const api='/sales-checkin/admin/api/v1';
const id=n=>`40000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const checks=[],requests=[],errors=[];
const check=(ok,name)=>{checks.push({name,passed:!!ok});assert.ok(ok,name);};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const filters=url=>Object.fromEntries([...new URL(url,base).searchParams].filter(([key])=>!['page','size','summaryPage','summarySize'].includes(key)));
(async()=>{
 await fs.mkdir(output,{recursive:true});const browser=await chromium.launch({headless:true,channel:'chrome'});let page;
 async function setup(cityScoped=false,width=1440){
  const context=await browser.newContext({viewport:{width,height:width<700?844:1100},locale:'zh-CN',timezoneId:'America/Los_Angeles'});
  const state={fail:false};const identity={username:'本地统计验收',allCities:!cityScoped,city:cityScoped?'杭州':'',canDeleteSubmissions:false,canManageSalespersons:false,canManageCities:false,csrfToken:'fictional-test-token'};
  await context.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());if(url.origin!==new URL(base).origin)return route.abort();
   if(!url.pathname.startsWith(api))return route.continue();
   requests.push({scope:cityScoped?'city':'all',method:req.method(),path:url.pathname,query:Object.fromEntries(url.searchParams)});
   if(req.method()!=='GET')return route.fulfill({status:405,json:{message:'Mutations forbidden'}});
   if(url.pathname===api+'/auth/me')return route.fulfill({json:identity});
   if(url.pathname===api+'/options')return route.fulfill({json:{scope:identity,cities:cityScoped?['杭州']:['杭州','苏州'],salespersons:[{id:id(501),name:'示例销售甲',city:'杭州'},{id:id(502),name:'示例销售乙',city:'苏州'}]}});
   const filtered=!!(url.searchParams.get('from')||url.searchParams.get('q')||url.searchParams.get('salespersonId'));
   const draft=url.searchParams.get('status')==='DRAFT';const total=filtered?7:127;
   const group=(index)=>({date:`2026-09-${String(8-Math.floor(index/12)).padStart(2,'0')}`,city:cityScoped?'杭州':index%2?'苏州':'杭州',salespersonId:index===0?id(590):id(501+index%11),salespersonName:index===0?'历史销售示例':`示例销售${index}`,visitCount:2,storeCount:1,firstCheckinAt:'2026-09-08T01:02:03Z',lastCheckinAt:'2026-09-08T09:10:11Z',pendingReviewCount:index<7?1:0});
   if(url.pathname===api+'/submissions/attendance-summary'){
    if(state.fail)return route.fulfill({status:503,json:{message:'统计服务暂不可用'}});
    const n=Number(url.searchParams.get('summaryPage')||0);
    if(url.searchParams.get('q')==='slow')await sleep(650);
    return route.fulfill({json:{totalVisits:url.searchParams.get('q')==='slow'?999:total,checkedInSalespeople:draft?0:filtered?1:12,pendingReviewTotal:filtered?3:9,
     items:draft?[]:filtered?[{...group(0),date:url.searchParams.get('from')||'2026-09-08',city:url.searchParams.get('city')||'杭州',salespersonId:url.searchParams.get('salespersonId')||id(501),visitCount:7,storeCount:4}]:Array.from({length:n===0?50:3},(_,i)=>group(n*50+i)),
     page:n,size:50,totalElements:draft?0:filtered?1:53,totalPages:draft?0:filtered?1:2}}).catch(()=>{});
   }
   if(url.pathname===api+'/submissions')return route.fulfill({json:{scope:identity,items:[{id:id(1),status:draft?'DRAFT':'SUBMITTED',city:'杭州',salespersonId:id(501),salespersonName:'示例销售甲',storeName:'示例门店',customerName:'示例客户',visitResult:'本地统计验收',completedAt:'2026-09-08T01:02:03Z',createdAt:'2026-09-08T00:00:00Z',photos:[],audioSegments:[],reviewStatus:'PENDING',riskFlags:[]}],page:Number(url.searchParams.get('page')||0),size:20,totalElements:total,totalPages:Math.ceil(total/20),firstVisitTotal:total,revisitTotal:0,locationAttentionTotal:0,reviewPendingTotal:filtered?3:9,missingAudioTotal:total}});
   return route.fulfill({status:404,json:{message:'Unknown fictional API'}});
  });
  const p=await context.newPage();p.setDefaultTimeout(10000);p.on('pageerror',e=>errors.push(e.message));
  await p.goto(base+'/sales-checkin/admin/'+(cityScoped?'?city='+encodeURIComponent('苏州'):''));
  await p.locator('#attendance-total').getByText('127',{exact:true}).waitFor();
  return{page:p,context,state};
 }
 try{
  const t=await setup();page=t.page;
  check(await page.locator('#submission-rows tr').count()===1&&await page.locator('#attendance-total').innerText()==='127','aggregate total comes from all 127 matching records, not one visible detail row');
  check(await page.locator('#attendance-salespeople').innerText()==='12'&&await page.locator('#attendance-pending-review').innerText()==='9','unique submitted salespeople and pending reviews use server totals');
  check((await page.locator('#attendance-card').innerText()).includes('没有记录不等于旷工'),'overview states visit records are not absence determinations');
  check(await page.locator('#attendance-rows tr').count()===50,'daily groups use their own server page');
  check((await page.locator('#attendance-rows tr').first().innerText()).includes('09:02:03'),'daily times are Shanghai clock with seconds and no ISO T');
  const listBefore=requests.filter(r=>r.path===api+'/submissions').length;
  await page.locator('#attendance-next').click();await page.waitForFunction(()=>document.querySelector('#attendance-page-indicator').textContent.includes('第 2'));
  check(await page.locator('#attendance-rows tr').count()===3&&requests.filter(r=>r.path===api+'/submissions').length===listBefore,'summary pagination requests remaining groups without paginating detail rows');
  check(await page.locator('#attendance-total').innerText()==='127','daily group pagination does not reduce full-range totals');
  await page.locator('#attendance-previous').click();await page.waitForFunction(()=>document.querySelector('#attendance-rows').children.length===50);
  await page.locator('#attendance-card').evaluate(element=>element.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'01-all-range-statistics.png')});
  await page.locator('#filter-from').fill('2026-09-01');await page.locator('#filter-to').fill('2026-09-08');await page.locator('#filter-city').selectOption('杭州');await page.locator('#filter-salesperson').selectOption(id(501));
  await page.locator('#filter-query').fill('便利');await page.locator('#filter-status').selectOption('SUBMITTED');await page.locator('#filter-review-status').selectOption('PENDING');await page.locator('#filter-media-status').selectOption('HAS_AUDIO');
  await page.locator('#search-button').click();await page.locator('#attendance-total').getByText('7',{exact:true}).waitFor();
  const latest=kind=>requests.filter(r=>r.scope==='all'&&r.path===api+'/submissions'+kind).at(-1).query;
  const strip=obj=>Object.fromEntries(Object.entries(obj).filter(([key])=>!['page','size','summaryPage','summarySize'].includes(key)));
  const expected=strip(latest(''));
  check(JSON.stringify(strip(latest('/attendance-summary')))===JSON.stringify(expected),'date, city, salesperson, keyword, status, review and media filters exactly match detail and summary requests');
  const link=await page.locator('#export-link').getAttribute('href');check(new URL(link,base).pathname==='/sales-checkin/admin/export.xlsx','normal UI exports actual XLSX endpoint');
  check(JSON.stringify(filters(link))===JSON.stringify(expected),'Excel receives the exact applied filters without page limits');
  check(await page.locator('#export-link').innerText()==='导出 Excel'&&(await page.locator('#export-link').getAttribute('download')).endsWith('.xlsx'),'Excel label and suggested Chinese filename are explicit');
  await page.locator('#filter-city').selectOption('苏州');
  check(JSON.stringify(filters(await page.locator('#export-link').getAttribute('href')))===JSON.stringify(expected),'unsubmitted form changes do not silently alter current export scope');
  await page.locator('#filter-city').selectOption('杭州');await page.locator('#filter-salesperson').selectOption(id(501));
  t.state.fail=true;await page.locator('#search-button').click();await page.locator('#attendance-retry').waitFor({state:'visible'});
  check(await page.locator('#attendance-total').innerText()==='--'&&await page.locator('#attendance-rows tr').count()===0,'summary failure clears old totals rather than showing false zero or stale numbers');
  check(await page.locator('#submission-rows tr').count()===1&&await page.locator('#table-wrap').isVisible(),'summary failure keeps detail records independently available');
  t.state.fail=false;await page.locator('#attendance-retry').click();await page.locator('#attendance-total').getByText('7',{exact:true}).waitFor();
  check(await page.locator('#attendance-retry').isHidden(),'summary retry recovers real values');
  await page.locator('#filter-query').fill('slow');await page.locator('#search-button').click();await page.locator('#search-button').waitFor({state:'visible'});
  await page.waitForFunction(()=>!document.querySelector('#search-button').disabled);await page.locator('#filter-query').fill('fast');await page.locator('#search-button').click();
  await page.locator('#attendance-total').getByText('7',{exact:true}).waitFor();await sleep(750);
  check(await page.locator('#attendance-total').innerText()==='7','late previous-filter summary cannot replace the latest selection');
  await page.locator('[data-attendance-range="today"]').click();await page.locator('#attendance-total').getByText('7',{exact:true}).waitFor();
  const today=await page.evaluate(()=>new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date()));
  check(await page.locator('#filter-from').inputValue()===today&&await page.locator('#filter-to').inputValue()===today,'today shortcut uses Shanghai business date even in an American browser timezone');
  await page.locator('#filter-status').selectOption('DRAFT');await page.locator('#search-button').click();await page.waitForFunction(()=>document.querySelector('#attendance-salespeople').textContent==='0');
  check(await page.locator('#attendance-total').innerText()==='7'&&await page.locator('#attendance-rows tr').count()===0,'drafts remain matched records but never become checked-in people or daily rows');
  check((await page.locator('#attendance-state').innerText()).includes('草稿'),'draft-only scope is explained');
  await page.locator('#reset-button').click();await page.locator('#attendance-total').getByText('127',{exact:true}).waitFor();
  await page.locator('#attendance-rows tr').first().getByRole('button',{name:/查看明细/}).click();await page.locator('#attendance-total').getByText('7',{exact:true}).waitFor();
  check(await page.locator('#filter-salesperson').inputValue()===id(590),'daily drilldown preserves a historical salesperson absent from current directory options');
  check(await page.locator('#filter-from').inputValue()==='2026-09-08'&&await page.locator('#filter-to').inputValue()==='2026-09-08'&&await page.locator('#filter-status').inputValue()==='SUBMITTED','daily row narrows detail to the exact date and submitted status');
  check(new URL(await page.locator('#export-link').getAttribute('href'),base).searchParams.get('salespersonId')===id(590),'drilled-down Excel scope retains the exact historical salesperson');
  await page.reload();await page.locator('#attendance-total').getByText('7',{exact:true}).waitFor();
  check(await page.locator('#filter-salesperson').inputValue()===id(590),'reloading a historical-salesperson drilldown keeps its exact filter instead of broadening to all people');
  await page.setViewportSize({width:390,height:844});await page.locator('#attendance-card').evaluate(element=>element.scrollIntoView({block:'start'}));
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'390px overview stays within viewport; daily table scrolls within its own region');
  await page.screenshot({path:path.join(output,'02-mobile-statistics.png')});await t.context.close();
  const c=await setup(true,390);page=c.page;
  check(await page.locator('#filter-city').isDisabled()&&await page.locator('#filter-city').inputValue()==='杭州','city-scoped UI cannot adopt a foreign city from URL');
  check(requests.filter(r=>r.scope==='city'&&r.path.includes('/submissions')).every(r=>r.query.city==='杭州'),'detail and summary both retain the verified city scope');
  check(new URL(await page.locator('#export-link').getAttribute('href'),base).searchParams.get('city')==='杭州','city-scoped Excel URL retains verified city');
  await page.locator('#attendance-card').evaluate(element=>element.scrollIntoView({block:'start'}));await page.screenshot({path:path.join(output,'03-mobile-fresh-statistics.png')});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'fresh mobile page keeps table within its own scroll region');
  await c.context.close();check(requests.every(r=>r.method==='GET'),'statistics and drilldown issue no business mutation');check(errors.length===0,'all exercised admin views have no JavaScript exception');
  await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:true,checks,requests,errors,scope:'Real admin frontend, fictional local read-only aggregates; backend SQL and XLSX bytes verified separately'},null,2));
  console.log(JSON.stringify({passed:true,checks:checks.length,output}));
 }catch(error){if(page&&!page.isClosed())await page.screenshot({path:path.join(output,'FAILED.png')}).catch(()=>{});await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,checks,requests,errors,error:String(error)},null,2));throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1});
