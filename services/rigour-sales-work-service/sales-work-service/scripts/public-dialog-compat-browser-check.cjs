#!/usr/bin/env node
'use strict';
// Simulates missing dialog capability in modern engines; this is not an iOS 14.6 device run.
// The low-specificity author rule removes modern UA display:none behavior without overriding a real app closed-dialog rule.
const {chromium,webkit}=require(process.env.PW_MODULE_PATH||'playwright');
const assert=require('node:assert/strict'),fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const base='http://127.0.0.1:8774',api='/sales-checkin/api/v1';
const engine=process.env.BROWSER_ENGINE||'webkit',legacy=process.env.DIALOG_MODE!=='native',baseline=process.env.EXPECT_BASELINE_FAILURE==='1';
const staticRoot=path.resolve(process.env.CHECKIN_STATIC_ROOT||path.join(__dirname,'../src/main/resources/static'));
const output=path.resolve(process.env.QA_DIR||`/tmp/checkin-dialog-compat-20260909/${engine}-${legacy?'legacy':'native'}`);
const id=n=>`70000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const checks=[],requests=[],errors=[],blocked=[];let snapshot;
const blockPageScripts=process.env.BLOCK_PAGE_SCRIPTS==='1',anonymous=process.env.ANONYMOUS==='1';
const cssSimulation={hasRules:0,aspectDeclarations:0,viewportDeclarations:0,supportsFallbacks:0};
function legacyCss(css){
 css=css.replace(/[^{}]*:has\([^{}]*\)[^{}]*\{[^{}]*\}/g,()=>{cssSimulation.hasRules++;return '';});
 css=css.replace(/@supports\s+not\s*\((?:aspect-ratio\s*:[^)]*|height\s*:\s*1dvh)\)/g,()=>{cssSimulation.supportsFallbacks++;return '@supports (display:block)';});
 css=css.replace(/([;{])\s*aspect-ratio\s*:[^;}]+/g,(_,lead)=>{cssSimulation.aspectDeclarations++;return lead;});
 css=css.replace(/([;{])\s*[a-z-]+\s*:[^;{}]*(?:dvh|svh)[^;{}]*/g,(_,lead)=>{cssSimulation.viewportDeclarations++;return lead;});
 return css;
}
const check=(ok,name)=>{checks.push({name,passed:!!ok});assert.ok(ok,name);};
(async()=>{
 await fs.mkdir(output,{recursive:true});
 const jpeg=await fs.readFile(path.join(__dirname,'fixtures/demo-storefront.jpg'));
 const profile=await fs.mkdtemp(path.join(os.tmpdir(),'checkin-dialog-profile-'));
 const context=await(engine==='webkit'?webkit:chromium).launchPersistentContext(profile,{headless:true,...(engine==='webkit'?{}:{channel:'chrome'}),viewport:{width:390,height:844},isMobile:true,hasTouch:true,locale:'zh-CN',timezoneId:'Asia/Shanghai',serviceWorkers:'block'});
 const identity={authenticated:true,tenantId:id(99),salespersonId:id(501),salespersonName:'本地兼容验收',city:'杭州',enforcementEnabled:true,expiresAt:new Date(Date.now()+86400000).toISOString()};
 const store={id:id(3),storeId:id(3),name:'本地兼容示例门店',city:'杭州',address:'本地示例路1号',locationSummary:'本地示例路1号',attribute:'便利店',source:'REGISTERED',checkinEligible:true,nextAction:'CHECK_IN',locationVerificationStatus:'VERIFIED'};
 const options={cities:['杭州'],salespersons:[{id:id(501),name:identity.salespersonName,city:'杭州'}],maxAudioBytes:268435456,storeAttributes:['便利店'],operatingStatuses:['营业中'],areaRanges:['50㎡以下'],businessTypes:['食品零售'],intendedBusinesses:['休闲零食'],cooperationIntents:['持续跟进'],storeGrades:['A'],storeTags:['社区店']};
 const photo={photoId:id(20),mediaId:'photo-'+id(20),contentType:'image/jpeg',originalFilename:'本地示例照片.jpg',sizeBytes:jpeg.length,captureSource:'CAMERA',uploadedAt:new Date().toISOString(),thumbnailUrl:`${api}/submissions/${id(1)}/mine/media/photo-${id(20)}?variant=thumbnail`,originalUrl:`${api}/submissions/${id(1)}/mine/media/photo-${id(20)}?variant=original`};
 const detail={id:id(1),clientSubmissionId:id(2),status:'SUBMITTED',city:'杭州',storeName:'历史照片兼容示例',salespersonId:id(501),salespersonName:identity.salespersonName,customerName:'本地客户',visitResult:'仅本地弹窗兼容验收',createdAt:new Date().toISOString(),submittedAt:new Date().toISOString(),uploadedMedia:['storefront-photo'],photos:[photo],photoIds:[photo.photoId],media:[],audioSegmentIds:[],locationQuality:'MISSING',canSupplement:false};
 await context.addInitScript(({legacy})=>{
  // Deny fixture geolocation immediately; no real geolocation or map provider request is needed.
  const denied=(_ok,fail)=>setTimeout(()=>fail?.({code:1,message:'Local compatibility fixture'}),0);
  Object.defineProperty(navigator,'geolocation',{configurable:true,value:{getCurrentPosition:denied,watchPosition:(_ok,fail)=>{denied(_ok,fail);return 1;},clearWatch(){}}});
  window.__dialogSimulation={mode:legacy?'missing-native-and-UA-hidden-rule':'native',nativeConstructor:typeof window.HTMLDialogElement};
  if(legacy){
   const proto=window.HTMLDialogElement?.prototype;
   if(proto)for(const name of ['show','showModal','close','open','returnValue']){try{delete proto[name];}catch{}if(name in proto)Object.defineProperty(proto,name,{configurable:true,value:undefined});}
   Object.defineProperty(window,'HTMLDialogElement',{configurable:true,value:undefined});
  }
 },{legacy});
 await context.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url());
  // WebKit routes local object URLs too. Let its real Blob store resolve them; never substitute bytes.
  if(url.protocol==='blob:'&&url.origin===base)return route.continue();
  if(url.origin!==base){blocked.push({method:req.method(),path:'external'});return route.abort();}
  if(url.pathname.startsWith(api)){
   requests.push({method:req.method(),path:url.pathname,query:url.search,...(url.pathname.endsWith('/diagnostics/events')?{diagnostic:req.postDataJSON()}: {})});
   const send=json=>route.fulfill({json});
   if(req.method()==='POST'&&[api+'/client-events',api+'/diagnostics/events'].includes(url.pathname))return send({accepted:true});
   if(req.method()!=='GET'){blocked.push({method:req.method(),path:url.pathname});return route.fulfill({status:405,json:{message:'No business writes in dialog fixture'}});}
   if(url.pathname===api+'/identity/me')return send(anonymous?{authenticated:false,enforcementEnabled:true}:identity);
   if(url.pathname===api+'/options')return send(options);
   if(url.pathname===api+'/stores')return send([store]);
   if(url.pathname===api+'/submissions/mine')return send({items:[detail],page:0,size:20,totalElements:1,totalPages:1});
   if(url.pathname===`${api}/submissions/${id(1)}/mine`)return send(detail);
   if(url.pathname===`${api}/submissions/${id(1)}/mine/media/${photo.mediaId}`)return route.fulfill({contentType:'image/jpeg',body:jpeg});
   if(url.pathname.startsWith(api+'/submissions/by-client/'))return route.fulfill({status:404,json:{message:'No server visit created'}});
   return route.fulfill({status:404,json:{message:'Unknown local fixture API'}});
  }
  if(req.method()!=='GET'){blocked.push({method:req.method(),path:url.pathname});return route.abort();}
  if(url.pathname==='/favicon.ico')return route.fulfill({status:204,body:''});
  const relative=url.pathname.endsWith('/')?url.pathname+'index.html':url.pathname;
  const filename=path.resolve(staticRoot,'.'+relative);
  if(!filename.startsWith(staticRoot+path.sep)||!relative.startsWith('/sales-checkin/'))return route.abort();
  try{
   if(blockPageScripts&&relative.endsWith('.js'))return route.fulfill({status:503,contentType:'text/javascript',body:''});
   if(legacy&&relative==='/sales-checkin/styles.css')return route.fulfill({contentType:'text/css',body:legacyCss(await fs.readFile(filename,'utf8'))});
   if(relative==='/sales-checkin/index.html'&&legacy){
    const html=await fs.readFile(filename,'utf8');
    // This substitutes only the missing UA behavior. Product selectors such as dialog:not([open])
    // and [hidden] remain more specific and therefore determine the actual closed state.
    const fixtureStyle='<style data-dialog-ua-simulation>:where(dialog){display:block}</style>';
    return route.fulfill({contentType:'text/html',body:html.replace('</head>',fixtureStyle+'</head>')});
   }
   return await route.fulfill({path:filename});
  }catch{return route.fulfill({status:404,body:'Unknown local static fixture'});}
 });
 const page=await context.newPage();page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message));
 async function capture(name){await page.evaluate(async()=>{await Promise.all(Array.from(document.querySelectorAll('dialog[open] .dialog-close-icon')).map(img=>typeof img.decode==='function'?img.decode().catch(()=>{}):Promise.resolve()));await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));});await page.screenshot({path:path.join(output,name)});}
 const visibleClosedDialogs=()=>page.evaluate(()=>[...document.querySelectorAll('dialog')].filter(d=>!d.hasAttribute('open')&&getComputedStyle(d).display!=='none'&&d.getBoundingClientRect().width>0&&d.getBoundingClientRect().height>0).map(d=>({id:d.id,display:getComputedStyle(d).display,background:getComputedStyle(d).backgroundColor,alt:d.querySelector('img[alt]')?.getAttribute('alt')})));
 const opened=selector=>page.locator(selector).waitFor({state:'visible'});
 const closed=selector=>page.locator(selector).waitFor({state:'hidden'});
 async function inspectOpen(selector,label){await opened(selector);check(await page.locator(selector).evaluate(d=>d.hasAttribute('open')),label+' has explicit open state');check(await page.locator(selector).evaluate(d=>d.contains(document.activeElement)),label+' receives focus');await page.keyboard.press('Shift+Tab');check(await page.locator(selector).evaluate(d=>d.contains(document.activeElement)),label+' keeps reverse keyboard focus inside');await page.keyboard.press('Tab');check(await page.locator(selector).evaluate(d=>d.contains(document.activeElement)),label+' keeps forward keyboard focus inside');check(await page.evaluate(()=>[...document.querySelectorAll('dialog')].filter(d=>getComputedStyle(d).display!=='none'&&d.getBoundingClientRect().height>0).length===1),label+' is the only visible dialog');if(legacy)check(await page.locator('.dialog-fallback-backdrop').count()===1,label+' creates one fallback backdrop');}
 async function initial(){
  await page.goto(base+'/sales-checkin/');if(!blockPageScripts)await page.locator(anonymous?'#identity-form':'#checkin-workspace').waitFor({state:'visible'});
  snapshot={dialogs:await visibleClosedDialogs(),capabilities:await page.evaluate(()=>({constructor:typeof window.HTMLDialogElement,showModal:typeof document.querySelector('#local-photo-dialog').showModal,openProperty:'open' in document.querySelector('#local-photo-dialog')})),topAtCenter:await page.evaluate(()=>{const e=document.elementFromPoint(innerWidth/2,innerHeight/2);return {tag:e?.tagName,id:e?.id,closestDialog:e?.closest('dialog')?.id};})};
  await capture('01-initial.png');
 }
 try{
  await initial();
  if(blockPageScripts){
   check(await page.locator('dialog[hidden]').count()===3,'all three dialogs have explicit hidden attributes without JavaScript');
   check(snapshot.dialogs.length===0,'HTML/CSS alone hide all unopened dialogs when product scripts fail to load');
   check(await page.evaluate(()=>typeof window.SalesCheckinHistory==='undefined'),'product history and dialog JavaScript never initialized');
   check(requests.length===0,'script failure test starts no business API requests');
  }else if(anonymous){
   check(snapshot.dialogs.length===0,'anonymous initial load shows no black photo overlay');
   check(await page.locator('#identity-form').isVisible(),'anonymous identity form remains visible and usable');
   check(await page.locator('dialog[hidden]').count()===3,'anonymous entry keeps all three dialogs explicitly hidden');
   check(errors.length===0&&blocked.length===0,'anonymous fixture has no page errors, writes or external calls');
  }else if(baseline){
   check(legacy,'baseline reproduction uses compatibility simulation');
   check(snapshot.dialogs.some(d=>d.id==='local-photo-dialog'),'baseline reproduces unopened visible local photo dialog');
   check(snapshot.topAtCenter.closestDialog==='local-photo-dialog','baseline black photo dialog covers the initial form');
   check(await page.locator('#local-photo-full').getAttribute('alt')==='本次拜访现场照片','baseline overlay carries the reported photo alt text');
  }else{
   check(snapshot.dialogs.length===0,'initial load shows no unopened dialogs or black photo overlay');
   check(legacy?snapshot.capabilities.constructor==='undefined'&&snapshot.capabilities.showModal==='undefined':snapshot.capabilities.showModal==='function','requested native capability path is actually exercised');
   await page.locator('#store-search').fill('本地');await page.locator('#store-search-results button').first().waitFor();await page.locator('#store-search-results button').first().tap();
   await page.locator('#visit-step-1-next').tap();await page.locator('#customer-name').fill('本地客户');await page.locator('#visit-result').fill('旧浏览器弹窗兼容验证');await page.locator('#visit-step-2-next').tap();
   await page.locator('#storefront-photo').setInputFiles(path.join(__dirname,'fixtures/demo-storefront.jpg'));
   await page.locator('[data-photo-thumbnail]').first().waitFor({state:'visible'});
   const photoRect=await page.locator('[data-photo-open]').first().evaluate(button=>{const r=button.getBoundingClientRect();const img=button.querySelector('img').getBoundingClientRect();return {width:r.width,height:r.height,imageWidth:img.width,imageHeight:img.height};});
   check(photoRect.width>=80&&photoRect.height>=80&&Math.abs(photoRect.height-photoRect.width)<4,'photo tile keeps square visible bounds without relying on aspect-ratio support');
   await capture('02-photo-grid.png');
   if(legacy)check(cssSimulation.aspectDeclarations>0&&cssSimulation.supportsFallbacks>0,'legacy run removed aspect-ratio and enabled the actual product CSS fallback');
   await page.locator('[data-photo-open]').first().focus();await page.locator('[data-photo-open]').first().press('Enter');
   await inspectOpen('#local-photo-dialog','local preview');await page.waitForFunction(()=>document.querySelector('#local-photo-full').naturalWidth>0);
   check(await page.locator('#local-photo-full').evaluate(img=>img.currentSrc.startsWith('blob:')),'local preview uses bounded local photo URL');
   await capture('02-local-photo.png');
   await page.locator('#local-photo-close').tap();await closed('#local-photo-dialog');
   await page.waitForFunction(()=>!document.querySelector('#local-photo-full').getAttribute('src'));
   check(!await page.locator('#local-photo-full').getAttribute('src'),'closing local preview releases image source after the native close event');
   check(await page.locator('[data-photo-open]').first().evaluate(button=>button===document.activeElement),'closing local preview restores opener focus');
   await page.locator('[data-photo-open]').first().tap();await opened('#local-photo-dialog');await page.keyboard.press('Escape');await closed('#local-photo-dialog');
   check(true,'local photo Escape closes native or fallback preview');
   await page.locator('#my-records-button').tap();await page.locator('.history-record-card').waitFor();
   await page.locator('.history-date-trigger').focus();await page.locator('.history-date-trigger').press('Enter');await inspectOpen('#history-calendar-dialog','history calendar');
   await capture('03-calendar.png');
   await page.locator('[data-history-action="close-calendar"]').tap();await closed('#history-calendar-dialog');
   check(await page.locator('.history-date-trigger').evaluate(button=>button===document.activeElement),'calendar close restores date trigger focus');
   await page.locator('.history-date-trigger').tap();await opened('#history-calendar-dialog');await page.keyboard.press('Escape');await closed('#history-calendar-dialog');
   check(true,'calendar Escape closes native or fallback dialog');
   await page.locator('.history-record-card').tap();await page.locator('.history-detail-photo').waitFor();await page.locator('.history-detail-photo').focus();await page.locator('.history-detail-photo').press('Enter');
   await inspectOpen('#history-photo-dialog','history photo');await page.waitForFunction(()=>document.querySelector('#history-photo-content img')?.naturalWidth>0);
   check((await page.locator('#history-photo-content img').getAttribute('src')).includes('variant=original'),'history preview requests the explicitly selected fictional original');
   await capture('04-history-photo.png');
   await page.locator('[data-history-action="close-photo"]').tap();await closed('#history-photo-dialog');
   check(await page.locator('.history-detail-photo').evaluate(button=>button===document.activeElement),'history photo close restores detail trigger focus');
   await page.locator('.history-detail-photo').tap();await opened('#history-photo-dialog');await page.keyboard.press('Escape');await closed('#history-photo-dialog');
   check(true,'history photo Escape closes native or fallback dialog');
   check((await visibleClosedDialogs()).length===0,'all three closed dialogs stay hidden after interactions');
   check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'mobile page keeps viewport bounds');
   await page.reload();await page.waitForFunction(()=>!document.querySelector('#checkin-workspace').hidden);
   check((await visibleClosedDialogs()).length===0,'reload with restored photo draft has no black overlay');
   check(errors.length===0,'no page JavaScript errors on supported compatibility path');
   check(blocked.length===0,'all traffic remains local with no business writes or map calls');
  }
  await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:true,engine,legacy,baseline,blockPageScripts,anonymous,cssSimulation,checks,snapshot,requests,errors,blocked,scope:'Modern browser with explicit missing-dialog API and missing-UA-hiding simulation; not a physical iOS14.6 run'},null,2));
  console.log(JSON.stringify({passed:true,engine,legacy,baseline,checks:checks.length,output}));
 }catch(error){await page.screenshot({path:path.join(output,'FAILED.png')}).catch(()=>{});await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,engine,legacy,baseline,blockPageScripts,anonymous,cssSimulation,error:error.stack,checks,snapshot,requests,errors,blocked},null,2));throw error;}finally{await context.close();await fs.rm(profile,{recursive:true,force:true});}
})().catch(error=>{console.error(error);process.exitCode=1;});
