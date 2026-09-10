#!/usr/bin/env node
'use strict';
// Only local fixture traffic. Counts real browser requests; no Amap account or real microphone is used.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const {chromium,webkit} = require(process.env.PW_MODULE_PATH || 'playwright');
const base = 'http://127.0.0.1:8774';
const output = path.resolve('docs/qa-20260907/search-upgrade'+(process.env.PW_BROWSER==='webkit'?'-webkit':''));
const checks = [];
function check(value, name) { assert.ok(value, name); checks.push(name); }
(async () => {
 await fs.mkdir(output, {recursive:true});
 const browser = process.env.PW_BROWSER==='webkit' ? await webkit.launch({headless:true}) : await chromium.launch({channel:'chrome',headless:true});
 const errors = [];
 async function open(width, hq=false, location=true) {
  const context = await browser.newContext({viewport:{width,height:844},isMobile:true,hasTouch:true,locale:'zh-CN',
   permissions:location?['geolocation']:[], ...(location?{geolocation:{latitude:30.2863,longitude:120.1389,accuracy:30}}:{})});
  await context.addInitScript(()=>{
   window.__gpsRequests=0;
   const watch=navigator.geolocation.watchPosition.bind(navigator.geolocation);
   navigator.geolocation.watchPosition=(...args)=>{window.__gpsRequests++;return watch(...args);};
  });
  const page=await context.newPage(); page.setDefaultTimeout(12000);
  page.on('pageerror',error=>errors.push(error.message));
  const calls=[]; let mapMode='ok'; let createPayload;
  await page.route('**/*',async route=>{
   const req=route.request(), url=new URL(req.url());
   if(url.origin!==base) return route.abort();
   if(url.pathname.includes('/api/')) calls.push({path:url.pathname,query:Object.fromEntries(url.searchParams),body:req.headers()['content-type']?.includes('application/json')?req.postDataJSON():null});
   if(hq&&url.pathname.endsWith('/identity/me')) {const r=await route.fetch();const body=await r.json();body.city='总部';return route.fulfill({response:r,json:body});}
   if(url.pathname.endsWith('/submissions')&&req.method()==='POST') createPayload=req.postDataJSON();
   if(url.pathname.endsWith('/locations/search-new-store')) {
    if(mapMode==='failed') return route.fulfill({status:503,json:{message:'fixture map unavailable'}});
    const poi=(id,name,distance)=>({source:'AMAP_POI',poiId:id,name,address:'本地示例地址',distanceMeters:distance,
      longitude:120.14,latitude:30.28,checkinEligible:false,nextAction:'COMPLETE_STORE_PROFILE',selectionToken:'fixture-token'});
    return route.fulfill({json:{poiLookupStatus:'AVAILABLE',nearbyStores:[poi('MAP-1','悦邻地图示例',180),
      poi('MAP-1','悦邻地图示例',180),poi('MAP-2','悦邻地图同名分店',800),poi('MAP-3','悦邻距离未知',null)]}});
   }
   await route.continue();
  });
  // Multipart payloads are not JSON, so network logging only handles text JSON safely.
  await page.goto(base+'/sales-checkin/');
  await page.locator('#identity-summary').waitFor();
  return {page,context,calls,setMapMode:value=>mapMode=value,payload:()=>createPayload};
 }
 try {
  const test=await open(390);const {page,context,calls}=test;
  await page.locator('#store-search-results button').first().waitFor();
  check(await page.locator('#visit-city').inputValue()==='杭州','defaults to salesperson business city');
  check(await page.locator('#visit-city').isEnabled(),'ordinary salesperson can change business city');
  check(await page.locator('#visit-location-address').innerText().then(t=>t.includes('文二路')),'initial geolocation displays resolved address');
  const initialGps=await page.evaluate(()=>window.__gpsRequests), initialResolve=calls.filter(c=>c.path.endsWith('/locations/resolve')).length;
  await page.locator('#visit-city').selectOption('苏州');
  check(await page.evaluate(()=>window.__gpsRequests)===initialGps,'business city change does not recapture physical GPS');
  check(calls.filter(c=>c.path.endsWith('/locations/resolve')).length===initialResolve,'business city change does not spend a geocoding call');
  await page.locator('#store-search').fill('悦');
  await page.locator('#store-search-results button').first().waitFor();
  await page.waitForFunction(()=>!document.querySelector('#store-search-help').textContent.includes('正在'));
  check(calls.some(c=>c.path.endsWith('/stores')&&c.query.q==='悦'),'single-character input queries internal archive');
  check(!calls.some(c=>c.path.endsWith('/locations/search-new-store')),'typing never queries Amap POI');
  check(await page.evaluate(()=>window.__gpsRequests)===initialGps,'typing does not repeatedly capture GPS');
  await page.locator('#store-search-toggle').click();
  await page.waitForFunction(()=>document.querySelector('#store-search-help').textContent.includes('按距离排序')).catch(async error=>{
   console.error('SEARCH_DEBUG', await page.locator('#store-search-help').innerText(), JSON.stringify(calls));
   throw error;
  });
  check(calls.filter(c=>c.path.endsWith('/locations/search-new-store')).length===1,'one explicit click performs exactly one POI request');
  check(await page.evaluate(()=>window.__gpsRequests)===initialGps+1,'explicit search first obtains fresh physical GPS');
  const results=await page.locator('#store-search-results button').allInnerTexts();
  check(results.length===5,'merged list deduplicates the same POI and retains distinct branches');
  check(results[0].includes('西湖店')&&results[1].includes('悦邻地图示例')&&results[2].includes('滨江店')&&results[3].includes('同名分店'),'combined results sort from near to far');
  check(results[4].includes('距离未知'),'unknown distances sort last without becoming zero metres');
  check(results[1].includes('地图门店')&&results[0].includes('已建档'),'registered and map-only stores are clearly identified');
  check(await page.locator('#store-search-results').evaluate(e=>getComputedStyle(e).overflowY==='auto'),'merged selection list scrolls vertically');
  check(await page.locator('#nearby-stores-panel').getAttribute('role')==='dialog','search opens dedicated store picker');
  check(await page.locator('#store-search').boundingBox().then(box=>box.y<90),'query is pinned near the top of the picker');
  check(await page.locator('#store-search-results').boundingBox().then(box=>box.height>600),'results use the available screen height');
  check(await page.locator('#store-search-results .visit-store-result__meta').first().evaluate(e=>e.parentElement===e.closest('button').firstElementChild),'distance and source sit below the address');
  await page.screenshot({path:path.join(output,'390-combined-search.png')});
  await page.setViewportSize({width:390,height:460});
  const inputTop=await page.locator('#store-search').boundingBox().then(box=>box.y);
  await page.locator('#store-search-results').evaluate(e=>{e.scrollTop=e.scrollHeight;});
  check(await page.locator('#store-search-results').evaluate(e=>e.scrollTop>0),'short viewport can scroll to the last result');
  check(await page.locator('#store-search').boundingBox().then(box=>Math.abs(box.y-inputTop)<1),'scrolling results keeps search controls fixed');
  check(await page.locator('#store-picker-close').isVisible(),'cancel remains available in short viewport');
  const beforeCancel=calls.filter(c=>c.path.endsWith('/locations/search-new-store')).length;
  await page.locator('#store-picker-close').click();
  check(!await page.locator('body').evaluate(e=>e.classList.contains('is-store-picker-open')),'cancel closes picker without changing selection');
  await page.locator('#store-search').focus();
  check(calls.filter(c=>c.path.endsWith('/locations/search-new-store')).length===beforeCancel,'reopening picker spends no map request');
  await page.locator('#store-search').press('Escape');
  check(await page.locator('#store-search-toggle').evaluate(e=>document.activeElement===e),'Escape returns focus to search action');
  await page.locator('#store-search').focus();
  await page.setViewportSize({width:390,height:844});

  test.setMapMode('failed');await page.locator('#store-search-toggle').click();
  await page.waitForFunction(()=>document.querySelector('#store-search-help').textContent.includes('地图搜索暂不可用'));
  check(await page.locator('#store-search-results button').count()===2,'map failure keeps selectable internal results');
  await page.locator('#store-search-results button').first().click();
  check(!await page.locator('body').evaluate(e=>e.classList.contains('is-store-picker-open')),'selection closes picker and returns to visit');
  check(await page.locator('#selected-store-card').isVisible(),'selected store card is visible after selection');
  const selectedName=await page.locator('#selected-store-name').innerText();
  await page.locator('#clear-store-button').click();
  await page.locator('#store-picker-close').click();
  check(await page.locator('#selected-store-name').innerText()===selectedName,'cancel reselect preserves the original store');
  await page.locator('#store-search').fill('悦邻');
  await page.locator('#store-picker-close').click();
  await page.waitForTimeout(700);
  check(!await page.locator('body').evaluate(e=>e.classList.contains('is-store-picker-open')),'cancel clears pending debounced search without reopening');
  check(await page.locator('#selected-store-name').innerText()===selectedName,'cancel pending search preserves original store');
  await page.locator('#visit-step-1-next').click();await page.locator('#customer-name').fill('业务城独立GPS · 本地测试');
  await page.locator('#visit-result').fill('本地测试：跨业务城市门店，提交时采样真实当前位置。');
  await page.locator('#visit-step-2-next').click();await page.locator('#storefront-photo').setInputFiles(path.join(__dirname,'fixtures/demo-storefront.jpg'));
  await page.locator('#photo-grid [data-photo-thumbnail]').first().waitFor();
  await context.setGeolocation({latitude:31.2304,longitude:121.4737,accuracy:25});
  const beforeSubmit=await page.evaluate(()=>window.__gpsRequests);
  await page.locator('#submit-visit-button').click();await page.locator('#success-view-record-button').waitFor();
  check(await page.evaluate(()=>window.__gpsRequests)===beforeSubmit+1,'initial submission obtains a new GPS sample');
  const payload=test.payload();
  check(payload.city==='苏州'&&payload.location.latitude===31.2304&&payload.location.longitude===121.4737,'business city is independent from actual submission coordinates');
  check(payload.privacyAccepted===false,'removed checkbox never fabricates privacy consent');
  await context.close();
  const hq=await open(320,true);await hq.page.locator('#visit-city').waitFor();
  check(await hq.page.locator('#visit-city').inputValue()==='总部','headquarters account defaults to headquarters instead of empty city');
  check(!(await hq.page.locator('body').innerText()).includes('city不能为空'),'headquarters entry never exposes an empty-city API error');
  await hq.page.locator('#store-search-results button').first().waitFor();await hq.page.screenshot({path:path.join(output,'320-headquarters.png')});
  check(await hq.page.evaluate(()=>document.documentElement.scrollWidth===innerWidth),'320px homepage has no horizontal overflow');
  await hq.page.locator('#store-search').fill('悦');
  await hq.page.locator('#store-picker-close').waitFor();
  check(await hq.page.evaluate(()=>document.documentElement.scrollWidth===innerWidth),'320px picker has no horizontal overflow');
  await hq.page.screenshot({path:path.join(output,'320-picker.png')});
  await hq.context.close();
  const mapVisit=await open(390);
  await mapVisit.page.locator('#store-search-results button').first().waitFor();
  await mapVisit.page.locator('#visit-city').selectOption('苏州');
  await mapVisit.page.locator('#store-search').fill('悦');
  await mapVisit.page.waitForFunction(()=>document.querySelectorAll('#store-search-results button').length===2);
  await mapVisit.page.locator('#store-search-toggle').click();
  await mapVisit.page.getByRole('button',{name:/悦邻地图示例/}).click();
  await mapVisit.page.locator('#store-name').waitFor();
  check(await mapVisit.page.locator('#store-name').inputValue()==='悦邻地图示例','map-only selection opens prefilled store registration');
  const selectedDraft=await mapVisit.page.evaluate(()=>JSON.parse(sessionStorage.getItem('rigour.sales-checkin.draft.v1')));
  const searchBody=mapVisit.calls.find(c=>c.path.endsWith('/locations/search-new-store')).body;
  check(selectedDraft.store.clientStoreId===searchBody.clientStoreId&&selectedDraft.store.city==='苏州',
    'map selection preserves its signed draft identifier across changed business city');
  await mapVisit.context.close();
  const missing=await open(390,false,false);await missing.page.locator('#store-search').fill('悦');
  await missing.page.waitForFunction(()=>document.querySelectorAll('#store-search-results button').length===2);
  check(await missing.page.locator('#store-search-results button').first().isEnabled(),'denied location never blocks internal store selection');
  await missing.context.close();
  check(errors.length===0,'all exercised pages have no JavaScript exceptions');
  await fs.writeFile(path.join(output,'report.json'),JSON.stringify({passed:true,checks},null,2));
  console.log(JSON.stringify({passed:true,count:checks.length,checks},null,2));
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
