#!/usr/bin/env node
// Real browser verification of production static files against local example APIs only.
const {chromium} = require(process.env.PW_MODULE_PATH || 'playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const base = process.env.CHECKIN_PREVIEW_URL || 'http://127.0.0.1:8774';
if(!['127.0.0.1','localhost','::1'].includes(new URL(base).hostname)) throw new Error('Local example service required');
const output = path.resolve(process.env.QA_DIR || 'docs/qa-20260907');
const photo = path.join(__dirname,'fixtures/demo-storefront.jpg');
const results=[];const errors=[];let checks=0;
function check(value,label){assert.ok(value,label);checks++;results.push(label);}
(async()=>{
 await fs.mkdir(output,{recursive:true});
 const browser = await chromium.launch({...(process.env.CHROME_BIN ? {executablePath:process.env.CHROME_BIN}:{channel:'chrome'}),headless:true,
   args:['--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});
 async function context(width=390){const c=await browser.newContext({viewport:{width,height:844},deviceScaleFactor:1,isMobile:true,hasTouch:true,locale:'zh-CN',timezoneId:'Asia/Shanghai',geolocation:{latitude:30.2858,longitude:120.1383,accuracy:35},permissions:['geolocation']});
   await c.route('**/*',route=> new URL(route.request().url()).origin===new URL(base).origin ? route.continue():route.abort());return c;}
 async function page(c){const p=await c.newPage();p.setDefaultTimeout(10000);p.on('pageerror',e=>errors.push(e.message));await p.goto(base+'/sales-checkin/');await p.locator('#store-search-results button').first().waitFor();return p;}
 async function capture(p,name){await p.locator('#hero-title').tap();await p.waitForFunction(()=>!document.body.classList.contains('has-mobile-input-focus'));await p.evaluate(()=>scrollTo(0,0));await p.screenshot({path:path.join(output,name),animations:'disabled'});const geo=await p.evaluate(()=>({w:innerWidth,scroll:document.documentElement.scrollWidth}));check(geo.scroll===geo.w,name+' no horizontal overflow');}
 async function form(p,label){await p.locator('#store-search-results button').nth(1).tap();check(await p.locator('#store-search-results button[aria-pressed=true]').count()===1,'selected store is visible');await p.locator('#visit-step-1-next').tap();await p.waitForFunction(()=>document.body.dataset.screen==='visit-form');await p.locator('#customer-name').fill(label);await p.locator('#visit-result').fill('已了解补货需求，约定下周跟进。');}
 async function addPhoto(p){await p.locator('#visit-step-2-next').tap();await p.locator('#storefront-photo').setInputFiles(photo);await p.locator('[data-photo-thumbnail]').first().waitFor({state:'visible'});await p.waitForFunction(()=>!document.querySelector('#photo-camera-button').disabled);}
 try{
  const c=await context();const p=await page(c);
  await p.locator('#store-search-results button').nth(1).tap();await capture(p,'pw-01-home.png');
  await p.locator('#visit-step-1-next').tap();await p.locator('#customer-name').fill('张店长 · 本地浏览器测试');await p.locator('#visit-result').fill('已沟通本周销售情况，重点了解休闲零食补货需求，约定周五再次拜访。');
  await capture(p,'pw-02-form.png');
  // Fake browser microphone captures a synthetic test stream, never the computer's real microphone.
  check(await p.locator('#recording-consent, #privacy-accepted').count()===0,'obsolete consent checkboxes are absent');
  await p.locator('#record-audio-button').tap();
  await p.waitForFunction(()=>document.querySelector('#record-audio-button').classList.contains('is-recording'));
  await p.waitForFunction(()=>document.querySelector('#recording-clock').textContent!=='00:00');
  check(await p.locator('#visit-step-2-next').isDisabled(),'next step is guarded while microphone records');
  await p.locator('#record-audio-button').tap();await p.locator('#audio-preview-list audio').waitFor();
  check(await p.locator('#audio-preview-list audio').count()===1,'browser recording creates one playable segment');
  await addPhoto(p);await capture(p,'pw-03-photo.png');
  const chooserReady=p.waitForEvent('filechooser');await p.locator('#photo-camera-button').tap();const chooser=await chooserReady;await chooser.setFiles(photo);
  await p.waitForFunction(()=>document.querySelectorAll('[data-photo-item]').length===2&&!document.querySelector('#photo-camera-button').disabled);
  await p.locator('[data-photo-thumbnail]').nth(1).waitFor({state:'visible'});
  check(await p.locator('[data-photo-thumbnail]:visible').count()===2,'continue shooting invokes a native file picker and appends a second photo');
  await p.locator('[data-photo-open]').first().tap();check(await p.locator('#local-photo-dialog').isVisible(),'bounded local photo opens');await p.locator('#local-photo-close').tap();
  await p.locator('[data-photo-remove]').first().tap();await p.waitForFunction(()=>document.querySelectorAll('[data-photo-item]').length===1);
  check(await p.locator('#photo-count').innerText()==='1 / 9 张','deleting one photo preserves the other');
  const fixturePhoto=await fs.readFile(photo);
  // Synthetic 4000x3000 JPEG checks the real 12MP browser decoder path without a private customer photo.
  const twelveMp=Buffer.from(await p.evaluate(async()=>{const canvas=document.createElement('canvas');canvas.width=4000;canvas.height=3000;const context=canvas.getContext('2d');context.fillStyle='#dae8f8';context.fillRect(0,0,4000,3000);context.fillStyle='#1558f5';context.fillRect(0,1700,4000,1300);context.fillStyle='#ffffff';context.font='bold 420px sans-serif';context.fillText('12MP TEST',460,2400);const blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg',0.8));canvas.width=1;canvas.height=1;return [...new Uint8Array(await blob.arrayBuffer())];}));
  await p.locator('#photo-album-input').setInputFiles(Array.from({length:8},(_,i)=>({name:i===0?'synthetic-12mp.jpg':`album-${i+1}.jpg`,mimeType:'image/jpeg',buffer:i===0?twelveMp:fixturePhoto})));
  await p.waitForFunction(()=>document.querySelectorAll('[data-photo-item]').length===9&&document.querySelector('#draft-save-status').textContent==='本机已保存');
  check(await p.locator('#photo-camera-button').isDisabled(),'nine-photo limit disables further camera selection');
  check(await p.locator('#photo-album-button').isDisabled(),'nine-photo limit disables further album selection');
  await p.locator('[data-photo-thumbnail]').nth(1).waitFor({state:'visible'});
  check(await p.locator('[data-photo-thumbnail]').nth(1).evaluate(image=>image.naturalWidth<=384&&image.naturalHeight<=384),'actual 12MP JPEG becomes a bounded 384px thumbnail');
  await p.waitForFunction(()=>[...document.querySelectorAll('[data-photo-thumbnail]')].filter(image=>!image.hidden&&image.complete&&image.naturalWidth>0).length===9);
  await capture(p,'pw-03-nine-photos.png');
  await p.reload();await p.locator('[data-photo-thumbnail]').first().waitFor({state:'visible'});
  check(await p.locator('[data-photo-item]').count()===9,'all nine photo Blobs survive real page reload with stable metadata');
  check((await p.locator('[data-photo-name]').first().textContent()).includes('demo-storefront'),'original photo filename survives reload');
  check(await p.locator('#audio-preview-list audio').count()===1,'recorded audio survives page reload');
  let completeWrites=0,audioWrites=0,created;
  p.on('response',async r=>{if(r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/submissions'))created=await r.json();});
  await p.route('**/complete',async route=>{completeWrites++;await route.fetch();await route.abort('failed');});
  await p.route('**/media/audio/*',async route=>{audioWrites++;await route.fetch();await route.abort('failed');});
  await p.locator('#submit-visit-button').tap();await p.waitForFunction(()=>document.body.dataset.screen==='result');
  await p.waitForFunction(()=>[...document.querySelectorAll('#success-audio-list strong')].some(e=>e.textContent==='已收到'));
  check(completeWrites===1,'lost completion response rechecks receipt instead of duplicating completion');
  check(audioWrites===1,'lost optional audio response rechecks receipt instead of duplicating upload');
  check(!await p.locator('#success-media-note').isVisible(),'confirmed audio clears the earlier uncertainty note');
  const receiptId=(await p.locator('#success-submission-id').textContent()).trim();check(/^[0-9a-f-]{36}$/.test(receiptId),'successful UI exposes actual receipt ID');
  check(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(await p.locator('#success-submitted-at').innerText()),'receipt time uses second precision and requested format');
  await p.waitForFunction(async id=>{const response=await fetch('/sales-checkin/api/v1/submissions/'+id+'/mine');const record=await response.json();return record.photos?.length===9;},receiptId);
  check(true,'all nine photos are confirmed on the same completed server record');
  await capture(p,'pw-04-result.png');
  await p.locator('#success-view-record-button').tap();await p.waitForFunction(()=>document.body.dataset.screen==='history-detail');
  await p.locator('#history-detail-content img').first().waitFor();await capture(p,'pw-07-detail.png');
  check((await p.locator('#history-detail-content').innerText()).includes('张店长'),'own detail contains submitted visit');
  await p.locator('#app-back-button').tap();await p.waitForFunction(()=>document.body.dataset.screen==='history');await p.locator('#history-content .history-record-card').first().waitFor();check(/^共 \d+ 次拜访$/.test(await p.locator('#history-content .history-count').innerText()),'return from direct receipt detail loads own history');await capture(p,'pw-05-history.png');
  await c.close();
  // A pending upload is persisted before page loss. Re-entry must resume the same client ID.
  const c2=await context(320);const p2=await page(c2);await form(p2,'断网恢复 · 本地测试');await addPhoto(p2);
  await p2.locator('#photo-album-input').setInputFiles(Array.from({length:8},(_,i)=>({name:`narrow-${i+1}.jpg`,mimeType:'image/jpeg',buffer:fixturePhoto})));
  await p2.waitForFunction(()=>document.querySelectorAll('[data-photo-item]').length===9&&document.querySelector('#draft-save-status').textContent==='本机已保存');
  await p2.waitForFunction(()=>[...document.querySelectorAll('[data-photo-thumbnail]')].filter(image=>!image.hidden&&image.complete&&image.naturalWidth>0).length===9);
  await capture(p2,'pw-320-nine-photos.png');
  let held,releaseHeld;let photoSeen;const intercepted=new Promise(resolve=>photoSeen=resolve);
  const holding=new Promise(resolve=>releaseHeld=resolve);
  await p2.route('**/media/photos/*',async route=>{held=route;photoSeen();await holding;});
  await p2.locator('#submit-visit-button').tap();await intercepted;
  const draftKey=await p2.evaluate(()=>Object.keys(sessionStorage).find(k=>k.includes('draft')));
  const snapshot=await p2.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),draftKey);
  check(Boolean(snapshot.submission.serverId),'server draft receipt persisted before required photo upload');
  const originalId=snapshot.submission.clientSubmissionId;
  await held.abort('failed');releaseHeld();await p2.unroute('**/media/photos/*');
  p2.on('dialog',dialog=>dialog.accept());
  await p2.reload();await p2.waitForFunction(()=>document.body.dataset.screen==='result');
  const afterId=(await p2.locator('#success-submission-id').textContent()).trim();check(afterId===snapshot.submission.serverId,'re-entry resumes original server record after required upload interruption');
  const stored=await p2.evaluate(key=>JSON.parse(sessionStorage.getItem(key)),draftKey);check(stored.submission.clientSubmissionId===originalId,'re-entry retains original idempotency identity');
  await p2.waitForFunction(async id=>{const response=await fetch('/sales-checkin/api/v1/submissions/'+id+'/mine');const record=await response.json();return record.photos?.length===9;},afterId);
  check(true,'all nine interrupted photos eventually attach to the original record');
  await capture(p2,'pw-320-result.png');await c2.close();
  // A definite proxy rejection of optional audio must leave the main visit saved.
  const c3=await context();const p3=await page(c3);await form(p3,'附件过大 · 本地测试');
  const wav=Buffer.alloc(44+16000);wav.write('RIFF',0);wav.writeUInt32LE(wav.length-8,4);wav.write('WAVEfmt ',8);wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);wav.writeUInt32LE(8000,24);wav.writeUInt32LE(16000,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);wav.write('data',36);wav.writeUInt32LE(16000,40);
  await p3.locator('#audio-file').setInputFiles({name:'synthetic-silence.wav',mimeType:'audio/wav',buffer:wav});await p3.locator('#audio-preview-list audio').waitFor();await addPhoto(p3);
  let rejected=0;await p3.route('**/media/audio/*',route=>{rejected++;return route.fulfill({status:413,contentType:'text/html',body:'<html><h1>413 Request Entity Too Large</h1><p>nginx</p></html>'});});
  await p3.locator('#submit-visit-button').tap();await p3.waitForFunction(()=>document.body.dataset.screen==='result');await p3.waitForFunction(()=>document.querySelector('#success-audio-list').textContent.includes('文件过大，请更换'));await p3.locator('#success-retry-button').waitFor();
  check(rejected===1,'definite audio 413 is attempted once');check(!((await p3.locator('body').innerText()).includes('<html>')),'raw proxy HTML is not exposed as user guidance');
  const confirmedId=(await p3.locator('#success-submission-id').textContent()).trim();const confirmed=await(await p3.request.get(base+'/sales-checkin/api/v1/submissions/'+confirmedId+'/mine')).json();check(confirmed.status==='SUBMITTED','optional 413 leaves main server visit submitted');
  await capture(p3,'pw-04-result-pending.png');
  check(await p3.locator('#new-submission-button').isEnabled(),'next visit remains available with a rejected optional recording');await p3.locator('#new-submission-button').tap();await p3.waitForFunction(()=>document.body.dataset.screen==='visit-home');check(rejected===1,'starting next visit does not blindly retry rejected optional audio');await c3.close();
  check(errors.length===0,'no uncaught page errors during full flows');
  await fs.writeFile(path.join(output,'public-browser-results.json'),JSON.stringify({passed:true,checks,results,errors,engine:'local Chrome via Playwright',media:'synthetic microphone stream and fixture photo'},null,2));
  console.log(JSON.stringify({passed:true,checks,output}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
