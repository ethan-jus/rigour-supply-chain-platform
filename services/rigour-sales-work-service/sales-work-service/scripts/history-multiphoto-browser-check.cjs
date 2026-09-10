#!/usr/bin/env node
const {chromium}=require(process.env.PW_MODULE_PATH||'playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const origin=process.env.CHECKIN_PREVIEW_URL||'http://127.0.0.1:8774';
if(!['localhost','127.0.0.1'].includes(new URL(origin).hostname))throw Error('Local fictional fixture required');
const output=path.resolve(process.env.QA_DIR||'docs/qa-20260907/history-multiphoto');
const id=n=>`30000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const api='/sales-checkin/api/v1';
const checks=[],requests=[],errors=[];let firstThumbnailFailed=false,lastThumbnailUnavailable=true;
const check=(value,name)=>{checks.push({name,passed:!!value});assert.ok(value,name);};
(async()=>{
 await fs.mkdir(output,{recursive:true});const jpeg=await fs.readFile(path.join(__dirname,'fixtures/demo-storefront.jpg'));
 const browser=await chromium.launch({headless:true,channel:'chrome'});let page;
 try{
  const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,locale:'zh-CN',timezoneId:'Asia/Shanghai'});
  const identity=await(await context.request.get(origin+api+'/identity/me')).json();
  const photos=Array.from({length:9},(_,i)=>({photoId:id(i+20),mediaId:'photo-'+id(i+20),contentType:'image/jpeg',originalFilename:`虚构照片-${i+1}.jpg`,sizeBytes:jpeg.length,captureSource:'CAMERA',uploadedAt:new Date().toISOString(),thumbnailUrl:`${api}/submissions/${id(1)}/mine/media/photo-${id(i+20)}?variant=thumbnail`,originalUrl:`${api}/submissions/${id(1)}/mine/media/photo-${id(i+20)}?variant=original`}));
  const detail={id:id(1),clientSubmissionId:id(2),status:'SUBMITTED',city:'杭州',storeName:'个人记录九照片示例',salespersonId:identity.salespersonId,salespersonName:'示例销售',customerName:'示例客户',visitResult:'本地虚构九张照片，只读查看验证。',submittedAt:new Date().toISOString(),createdAt:new Date().toISOString(),uploadedMedia:[],audioSegmentIds:['audio-server','audio-client'],audioDisplayDurationMs:8000,audioDurationSource:'MIXED',photos,photoIds:photos.map(p=>p.photoId),media:[
   {mediaId:'audio-server',kind:'audio',originalFilename:'已解析.wav',parsedDurationMs:4000,clientDurationMs:5000,durationMs:4000,durationSource:'SERVER_PARSED',playbackStatus:'PENDING',originalUrl:`${api}/submissions/${id(1)}/mine/media/audio-server?variant=original`},
   {mediaId:'audio-client',kind:'audio',originalFilename:'本机记录.wav',parsedDurationMs:null,clientDurationMs:4000,durationMs:4000,durationSource:'CLIENT_ESTIMATE',playbackStatus:'PENDING',originalUrl:`${api}/submissions/${id(1)}/mine/media/audio-client?variant=original`}
  ],canSupplement:true,locationQuality:'MISSING',supplementUntil:new Date(Date.now()+3600000).toISOString()};
  await context.route('**/*',async route=>{
   const req=route.request(),url=new URL(req.url());if(url.origin!==new URL(origin).origin){errors.push('External media request '+url.origin);return route.abort();}
   if(!url.pathname.startsWith(api+'/submissions'))return route.continue();
   requests.push({path:url.pathname,method:req.method(),query:url.search});
   const json=data=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
   if(req.method()!=='GET')return route.fulfill({status:405,body:'read only'});
   if(url.pathname===api+'/submissions/mine')return json({items:[detail],page:0,size:20,totalElements:1,totalPages:1});
   if(url.pathname===`${api}/submissions/${id(1)}/mine`)return json(detail);
   if(photos.some(photo=>url.pathname===`${api}/submissions/${id(1)}/mine/media/${photo.mediaId}`)&&['thumbnail','original'].includes(url.searchParams.get('variant'))){
    if(url.searchParams.get('variant')==='thumbnail'){
     if(url.pathname.endsWith('/'+photos[0].mediaId)&&!firstThumbnailFailed){firstThumbnailFailed=true;return route.fulfill({status:503,headers:{'Cache-Control':'no-store'},body:'Temporary thumbnail generation unavailable'});}
     if(url.pathname.endsWith('/'+photos[8].mediaId)&&lastThumbnailUnavailable)return route.fulfill({status:503,headers:{'Cache-Control':'no-store'},body:'Thumbnail generation unavailable'});
    }
    return route.fulfill({contentType:'image/jpeg',headers:{'Cache-Control':'no-store'},body:jpeg});
   }
   return route.fulfill({status:404,contentType:'application/json',body:'{"message":"Unknown fictional media"}'});
  });
  page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.setDefaultTimeout(12000);
  await page.goto(origin+'/sales-checkin/');await page.locator('#nav-records-button').tap();
  await page.locator('.history-record-card').waitFor();
  const first=await page.locator('.history-record-card img').getAttribute('src');
  check(new URL(first,origin).pathname===`${api}/submissions/${id(1)}/mine/media/${photos[0].mediaId}`,'list thumbnail uses first photo UUID owner-media endpoint');
  await page.waitForFunction(()=>{const img=document.querySelector('.history-record-card img');return img&&!img.hidden&&img.complete&&img.naturalWidth>0;});
  check(firstThumbnailFailed,'fixture exercised a temporary thumbnail failure');
  check((await page.locator('.history-record-card').innerText()).includes('0:08 · 含本机计时'),'list immediately shows mixed known duration without preloading audio');
  await page.screenshot({path:path.join(output,'00-history-real-list-photo.png')});
  await page.locator('.history-record-card').tap();await page.locator('.history-detail-photo').first().waitFor();
  check(await page.locator('.history-detail-supplement').count()===0,'entire supplement section remains hidden despite server permission');
  check((await page.locator('.history-detail-audio-header').nth(0).innerText()).includes('0:04'),'server duration takes priority over differing client duration');
  check((await page.locator('.history-detail-audio-header').nth(1).innerText()).includes('0:04 · 本机记录'),'client duration displays immediately with source label');
  check(!requests.some(request=>request.path.includes('/media/audio-')),'known duration does not trigger audio preload');
  check(await page.locator('.history-detail-photo').count()===9,'own detail renders nine photos');
  await page.locator('.history-detail-photo').first().scrollIntoViewIfNeeded();await page.screenshot({path:path.join(output,'01-history-nine-detail.png')});
  for(let i=0;i<9;i++){
   const button=page.locator('.history-detail-photo').nth(i),image=button.locator('img');
   check(new URL(await image.getAttribute('src'),origin).pathname===`${api}/submissions/${id(1)}/mine/media/${photos[i].mediaId}`,`photo ${i+1} thumbnail uses own UUID`);
   await button.tap();await page.locator('#history-photo-dialog').waitFor({state:'visible'});
   const original=page.locator('#history-photo-content img');await original.waitFor();
   check(new URL(await original.getAttribute('src'),origin).pathname===`${api}/submissions/${id(1)}/mine/media/${photos[i].mediaId}`,`photo ${i+1} original uses same owned UUID`);
   check(new URL(await original.getAttribute('src'),origin).searchParams.get('variant')==='original',`photo ${i+1} opens original variant`);
   await page.waitForFunction(()=>{const img=document.querySelector('#history-photo-content img');return img?.complete&&img.naturalWidth>0;});
   if(i===8)await page.screenshot({path:path.join(output,'02-history-ninth-original.png')});
   await page.locator('[data-history-action="close-photo"]').tap();
  }
  await page.locator('.history-detail-photo').nth(8).scrollIntoViewIfNeeded();
  await page.locator('.history-detail-photo').nth(8).getByText('预览暂不可用，点开原图').waitFor();
  check(await page.locator('.history-media-refresh').getByText('刷新图片',{exact:true}).isVisible(),'failed thumbnail has a visible real refresh action');
  const ninthThumbnails=()=>requests.filter(request=>request.path.endsWith('/'+photos[8].mediaId)&&request.query==='?variant=thumbnail').length;
  await page.waitForTimeout(5000);const exhaustedCount=ninthThumbnails();await page.waitForTimeout(1800);
  check(exhaustedCount===ninthThumbnails()&&exhaustedCount<=3,'persistent thumbnail failure stops after at most two retries');
  lastThumbnailUnavailable=false;
  await page.getByRole('button',{name:'刷新图片',exact:true}).tap();
  await page.locator('.history-detail-photo').nth(8).scrollIntoViewIfNeeded();
  await page.waitForFunction(()=>{const imgs=[...document.querySelectorAll('.history-detail-photo img')];return imgs.length===9&&imgs.every(img=>img.complete&&img.naturalWidth>0&&!img.hidden);});
  check(await page.locator('.history-detail-photo img').count()===9,'refresh replaces placeholders with all nine actual decoded thumbnails');
  await page.screenshot({path:path.join(output,'03-history-all-nine-loaded.png')});
  for(const width of [390,320]){
   await page.setViewportSize({width,height:844});await page.locator('.history-detail-audio').first().scrollIntoViewIfNeeded();
   check(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),`known duration details do not overflow at ${width}px`);
   await page.screenshot({path:path.join(output,`04-history-duration-${width}.png`)});
  }
  detail.media.push({mediaId:'audio-interrupted',kind:'audio',originalFilename:'中断恢复.wav',durationMs:null,durationSource:'UNKNOWN',clientDurationMs:null,elapsedMs:120000,playbackStatus:'PENDING',originalUrl:`${api}/submissions/${id(1)}/mine/media/audio-interrupted?variant=original`});
  await page.locator('#app-back-button').tap();await page.locator('.history-record-card').tap();await page.locator('.history-detail-audio').nth(2).waitFor();
  check((await page.locator('.history-detail-audio-header').nth(2).innerText()).includes('时长待确认'),'interrupted elapsed time is never presented as encoded audio duration');
  photos[0].originalUrl=`${api}/submissions/${id(999)}/mine/media/${photos[0].mediaId}?variant=original`;
  await page.locator('#app-back-button').tap();await page.locator('.history-record-card').tap();await page.locator('.history-detail-photo').first().waitFor();
  await page.locator('.history-detail-photo').first().tap();
  await page.locator('#history-photo-content').getByText('照片地址暂不可用，请刷新明细后重试。').waitFor();
  check(!requests.some(request=>request.path.includes(id(999))),'mismatched record URL is rejected before requesting another record');
  check(await page.locator('#history-photo-content img').count()===0,'invalid original URL is not rendered as an image');
  check(requests.every(request=>request.method==='GET'),'personal history photo viewing is read only');check(errors.length===0,'no errors or unexpected external media request');
  await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:true,checks,requests,errors,scope:'Actual production HTML/JS, fictional same-origin own-record responses; production permission enforcement not exercised'},null,2));
  console.log(JSON.stringify({passed:true,checks:checks.length,output}));
 }catch(error){if(page)await page.screenshot({path:path.join(output,'FAILED.png')}).catch(()=>{});await fs.writeFile(path.join(output,'results.json'),JSON.stringify({passed:false,checks,requests,errors,error:String(error)},null,2));throw error;}finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
