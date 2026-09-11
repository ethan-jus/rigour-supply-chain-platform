#!/usr/bin/env node
// Verify real admin/history page media controls against strict local, fictional API routes.
const {chromium}=require(process.env.PW_MODULE_PATH||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const base=process.env.CHECKIN_PREVIEW_URL||'http://127.0.0.1:8774';
if(!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw Error('Local fixture required');
const api='/sales-checkin/admin/api/v1';
const output=path.resolve(process.env.QA_DIR||'docs/qa-20260907/admin-multiphoto');
const id=n=>`20000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const checks=[],requests=[],pageErrors=[];
const check=(value,name,details)=>{checks.push({name,passed:!!value,details});assert.ok(value,name+' '+JSON.stringify(details||''));};
(async()=>{
 await fs.mkdir(output,{recursive:true});
 const jpeg=await fs.readFile(path.join(__dirname,'fixtures/demo-storefront.jpg'));
 const photos=Array.from({length:9},(_,i)=>({photoId:id(i+20),mediaId:'photo-'+id(i+20),contentType:'image/jpeg',originalFilename:`虚构测试照片-${i+1}.jpg`,sizeBytes:jpeg.length,uploadedAt:'2026-09-07T10:00:00Z',captureSource:'CAMERA',thumbnailUrl:`${api}/submissions/${id(1)}/media/photos/${id(i+20)}?thumbnail=true`,originalUrl:`${api}/submissions/${id(1)}/media/photos/${id(i+20)}`}));
 const item={id:id(1),status:'SUBMITTED',city:'杭州',cityName:'杭州',salespersonId:id(70),salespersonName:'虚构销售',storeName:'九张照片示例便利店',customerName:'虚构客户',customerPhone:'',visitResult:'仅本地多照片界面验证，不是真实业务记录。',visitOrdinal:1,visitType:'FIRST_VISIT',createdAt:'2026-09-07T09:00:00Z',submittedAt:'2026-09-07T10:00:00Z',completedAt:'2026-09-07T10:00:00Z',storefrontPhotoAvailable:true,wechatScreenshotAvailable:false,audioAvailable:false,audioSegments:[],photos,reviewStatus:'PENDING',riskFlags:[],locationQuality:'MISSING',locationVerificationStatus:'UNVERIFIED',longitude:null,latitude:null};
 const browser=await chromium.launch({channel:'chrome',headless:true});let page;
 try{
  const context=await browser.newContext({viewport:{width:1440,height:1040},locale:'zh-CN',acceptDownloads:true});
  page=await context.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>pageErrors.push(error.message));
  await context.route('**/*',async route=>{
   const request=route.request(),url=new URL(request.url());
   if(url.origin!==new URL(base).origin)return route.abort();
   if(!url.pathname.startsWith('/sales-checkin/admin/api/')&&!url.pathname.includes('/media/'))return route.continue();
   requests.push({method:request.method(),path:url.pathname,query:url.search});
   const json=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
   if(request.method()!=='GET')return json({message:'Mutation forbidden in this read-only fixture'},405);
   const identity={username:'虚构管理员',allCities:true,csrfToken:'local-fixture-token',canDeleteSubmissions:true,canManageSalespersons:true,canManageCities:true};
   if(url.pathname===api+'/auth/me')return json(identity);
   if(url.pathname===api+'/options')return json({scope:identity,cities:['杭州'],salespersons:[{id:id(70),name:'虚构销售',city:'杭州'}],audioIntelligenceEnabled:false});
   if(url.pathname===api+'/submissions/attendance-summary')return json({totalVisits:1,checkedInSalespeople:1,pendingReviewTotal:1,items:[{date:'2026-09-07',city:'杭州',salespersonId:id(70),salespersonName:'虚构销售',visitCount:1,storeCount:1,firstCheckinAt:item.completedAt,lastCheckinAt:item.completedAt,pendingReviewCount:1}],page:0,size:50,totalElements:1,totalPages:1});
   if(url.pathname===api+'/submissions')return json({scope:identity,items:[item],page:0,size:20,total:1,totalElements:1,totalPages:1,firstVisitTotal:1,revisitTotal:0,locationAttentionTotal:1,reviewPendingTotal:1,missingAudioTotal:1});
   if(url.pathname===`${api}/submissions/${id(1)}/reviews`)return json([]);
   const match=url.pathname.match(/^\/sales-checkin\/admin\/api\/v1\/submissions\/([^/]+)\/media\/photos\/([^/]+)$/);
   if(match&&match[1]===id(1)&&photos.some(photo=>photo.photoId===match[2]))return route.fulfill({status:200,contentType:'image/jpeg',headers:{'Cache-Control':'private, no-store','Content-Disposition':`${url.searchParams.get('download')==='true'?'attachment':'inline'}; filename="photo-${match[2]}.jpg"`},body:jpeg});
   return json({message:'No matching controller GET mapping'},404);
  });
  await page.goto(base+'/sales-checkin/admin/',{waitUntil:'networkidle'});
  await page.locator('#submission-rows tr').first().waitFor();
  const gallery=page.locator('.row-photo-gallery');check(await gallery.locator('button').count()===9,'list exposes all nine photo buttons');
  await page.screenshot({path:path.join(output,'01-admin-list.png')});
  for(let i=0;i<9;i++){
   const button=gallery.locator('button').nth(i),image=button.locator('img');
   const expected=`${api}/submissions/${id(1)}/media/photos/${photos[i].photoId}`;
   check(new URL(await image.getAttribute('src'),base).pathname===expected,`list thumbnail ${i+1} uses controller UUID GET route`,await image.getAttribute('src'));
   await button.scrollIntoViewIfNeeded();await image.waitFor({state:'visible'});
   await page.waitForFunction(src=>[...document.images].some(image=>image.src===src&&image.complete&&image.naturalWidth>0),new URL(await image.getAttribute('src'),base).href);
   await button.click();await page.locator('#image-preview-dialog').waitFor({state:'visible'});
   check(new URL(await page.locator('#image-preview-content').getAttribute('src'),base).pathname===expected,`list photo ${i+1} opens its own original`);
   check(new URL(await page.locator('#image-preview-download').getAttribute('href'),base).pathname===expected,`list photo ${i+1} download keeps same UUID`);
   check(new URL(await page.locator('#image-preview-download').getAttribute('href'),base).searchParams.get('download')==='true',`list photo ${i+1} uses download=true`);
   if(i===8)await page.screenshot({path:path.join(output,'02-admin-original-nine.png')});
   await page.locator('#image-preview-close').click();
  }
  await page.locator('[data-field="detail"]').first().click();await page.locator('#submission-detail-dialog').waitFor({state:'visible'});
  const cards=page.locator('#detail-media .media-card--image');
  check(await cards.count()===9,'detail renders all nine image cards');
  for(let i=0;i<9;i++){
   const card=cards.nth(i),expected=`${api}/submissions/${id(1)}/media/photos/${photos[i].photoId}`;
   const link=card.locator('.media-download');
   check(new URL(await link.getAttribute('href'),base).pathname===expected,`detail download ${i+1} keeps exact UUID`);
   check(await card.locator('.media-delete').count()===0,`detail photo ${i+1} never links legacy delete action`);
   await card.locator('.media-thumbnail-button').click();
   check(new URL(await page.locator('#image-preview-content').getAttribute('src'),base).pathname===expected,`detail photo ${i+1} opens its original`);
   await page.locator('#image-preview-close').click();
  }
  await cards.first().scrollIntoViewIfNeeded();await page.screenshot({path:path.join(output,'03-admin-detail.png')});
  const downloadEvent=page.waitForEvent('download');await cards.nth(8).locator('.media-download').click();const download=await downloadEvent;
  const downloaded=await download.path();
  const expectedDownload=`${api}/submissions/${id(1)}/media/photos/${photos[8].photoId}`;
  check(new URL(download.url()).pathname===expectedDownload&&new URL(download.url()).searchParams.get('download')==='true','real browser download requests the chosen ninth photo UUID');
  const originalResponse=await page.request.get(base+expectedDownload+'?download=true');
  check(originalResponse.headers()['content-disposition'].includes(download.suggestedFilename())&&await download.failure()===null,'real browser honors server-provided download filename');
  check((await fs.readFile(downloaded)).equals(jpeg),'real browser download contains expected original bytes');
  check(requests.every(request=>request.method==='GET'),'viewing and downloading never issue a mutation');
  check(pageErrors.length===0,'admin has no uncaught page error',pageErrors);
  const css=await page.locator('link[rel="stylesheet"]').getAttribute('href');check(css.includes('multi-photo'),'admin CSS cache key changed for new gallery');
  await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:true,checks,requests,pageErrors,scope:'Local fictional fixture; controller route contracts mirrored, production auth not exercised'},null,2));
  console.log(JSON.stringify({passed:true,checks:checks.length,output}));
 }catch(error){if(page)await page.screenshot({path:path.join(output,'FAILED.png')}).catch(()=>{});await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,checks,requests,pageErrors,error:String(error)},null,2));throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
