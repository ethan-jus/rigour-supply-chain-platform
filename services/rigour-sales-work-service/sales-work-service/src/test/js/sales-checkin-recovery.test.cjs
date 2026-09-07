const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const {webcrypto} = require("node:crypto");
const sourcePath = path.resolve(__dirname, "../../main/resources/static/sales-checkin/app.js");
const original = fs.readFileSync(sourcePath, "utf8");
function harness() {
    const elements = new Map();
    let activeElement;
    const makeElement = (key, tagName = "div") => {
        const attributes = new Map();
        const listeners = new Map();
        return {tagName: tagName.toUpperCase(), value: "", hidden: false, disabled: false, children: [],
            dataset: {}, classList: {add() {}, remove() {}, toggle() {}, contains() {return false;}},
            setAttribute(name, value) {attributes.set(name, value);}, getAttribute(name) {return attributes.get(name) ?? null;},
            removeAttribute(name) {attributes.delete(name);}, scrollIntoView() {}, focus() {activeElement = this;},
            querySelector(selector) {return this.querySelectorAll(selector)[0] || element(key + selector);},
            closest: () => element(key + "parent"),
            querySelectorAll(selector) {return selector === "button.visit-store-result"
                ? this.children.filter(child => child.tagName === "BUTTON" && child.className.includes("visit-store-result")) : [];},
            appendChild(child) {this.children.push(child);}, append(...children) {this.children.push(...children);},
            replaceChildren(...children) {this.children = children;},
            addEventListener(type, listener) {listeners.set(type, listener);},
            dispatch(type, event = {}) {listeners.get(type)?.({currentTarget: this, ...event});}
        };
    };
    const element = (key) => {
        if (!elements.has(key)) elements.set(key, makeElement(key));
        return elements.get(key);
    };
    const saved = []; const uploaded = []; const requests = []; const timers = new Map(); const timerDelays = new Map();
    let timerId = 0;
    const window = {setTimeout(fn, delay) {timers.set(++timerId, fn); timerDelays.set(timerId, delay); return timerId;},
        clearTimeout(id) {timers.delete(id); timerDelays.delete(id);}, requestAnimationFrame(fn) {fn();},
        SalesCheckinDraftStore: {async save(owner, snapshot) { saved.push({owner,snapshot}); },
            async removeMedia() {}, async mediaFor() {return [];}, async prune() {}, async list() {return [];}}
    };
    const document = {addEventListener() {}, querySelector: element, querySelectorAll: () => [], visibilityState: "visible",
        createElement: (tag) => makeElement("created:" + tag, tag), get activeElement() {return activeElement;}, body:makeElement("body")};
    let rawDraft;
    const context = {window, document, navigator: {}, crypto: webcrypto, Blob, Headers, URL,
        HTMLTextAreaElement:class {},HTMLSelectElement:class {},HTMLInputElement:class {},
        sessionStorage: {setItem(key, value) {rawDraft = value;}, getItem() {return rawDraft;}, removeItem() {rawDraft = null;}},
        localStorage: {removeItem() {}},
        btoa: (value) => Buffer.from(value, "binary").toString("base64")};
    const injected = original.replace(/\n\}\)\(\);\s*$/, `
        // Tests replace transport and visual rendering boundaries; state/validation/recovery remain production code.
        syncStateFromForm = () => {};
        renderPhotos = renderRestoredValues = renderSelectedStore = renderLocation = renderAudioSegments = renderUploadedBadges
            = renderBusinessLock = renderTab = renderFlowActions = setFormsDisabled = prepareProgress
            = setProgressStep = scrollToFirstError = () => {};
        const actualUploadMedia = uploadMedia;
        window.__test = { state, submitVisit, persistDraft, saveLocalMedia, currentStorageOwner,
            preparePhotoPicker, restoreDraft, openSavedDraft, captureLocation, resolveLocationContext, renderNearbyStores,
            supplementCurrentEvidence, snapshotDraft, hasPendingEvidence, hasRestoredDraft,
            parseResponsePayload, extractApiMessage, friendlyHttpError, validMediaReceipt, selectStore,
            renderStoreResults, handleStoreSearchKeydown, handleStoreResultKeydown, handleCityChange,
            renderRecordingDisclosure, recoverInterruptedSubmission, restoreOwnedDraft, imageHeaderDimensions, readAudioDurationMs, readPcmWaveDurationMs, formatDateTime,
            handleAudioFileSelection, audioFileSizeAllowed, retryAudioSegment, optionalUploadOutcome,
            optionalUploadFailureMessage, uploadWithXHR: actualUploadMedia,
            setRequest(fn) { requestJson = fn; }, setUpload(fn) {uploadMedia = fn;} };
    })();`);
    vm.runInNewContext(fs.readFileSync(path.join(path.dirname(sourcePath), "photos.js"), "utf8"), context);
    vm.runInNewContext(injected, context, {filename:sourcePath});
    const api = window.__test;
    api.state.identity = {authenticated:true, tenantId:"t1", salespersonId:"sales1", city:"杭州"};
    Object.assign(api.state.visit, {city:"杭州", salespersonId:"sales1",selectedStore:{id:"store1",name:"门店"},
        customerName:"客户",visitResult:"拜访完成",privacyAccepted:true});
    const photoId=webcrypto.randomUUID();
    const photo=Object.assign(new Blob(["test-photo"], {type:"image/jpeg"}), {name:"photo.jpg"});
    api.state.files.photos=[{photoId,file:photo}];
    api.state.submission.photos=[window.SalesCheckinPhotos.record(photo,photoId,"CAMERA")];
    api.setRequest(async (url, options) => { requests.push({url,options});
        if (url === "/submissions") return {id:"server1",status:"DRAFT",uploadedMedia:[],audioSegmentIds:[]};
        if (url.endsWith("/complete")) return {id:"server1",status:"SUBMITTED",submittedAt:new Date().toISOString()};
        if (url.startsWith("/submissions/by-client/")) throw Object.assign(new Error("absent"), {status:404});
        return {};
    });
    api.setUpload(async (...args) => {uploaded.push(args); return {id:api.state.submission.serverId,
        kind:args[0].startsWith("photos/")?"storefront-photo":args[0].startsWith("audio/")?"audio":args[0],
        photoId:args[0].startsWith("photos/")?args[0].slice(7):undefined,
        segmentId:args[0].startsWith("audio/")?args[0].slice(6):undefined};});
    return {api, window, elements, element, saved, uploaded, requests, timers, timerDelays, context, photoId};
}
function xhrFixture(h) {
    let instance;
    h.context.FormData = FormData;
    h.context.XMLHttpRequest = class {
        constructor() {instance=this; this.events={}; this.upload={addEventListener:(name,fn)=>{this.upload[name]=fn;}};}
        open() {} setRequestHeader() {} addEventListener(name, fn) {this.events[name]=fn;}
        send() {} abort() {this.aborted=true; this.events.abort?.();}
    };
    return () => instance;
}
let checks = 0;
let completed = false;
process.on("beforeExit",()=>{if(!completed){console.error("Recovery checks ended with an unresolved asynchronous operation");process.exitCode=1;}});
async function check(name, run) {await run(); checks++; console.log(`ok - ${name}`);}
(async () => {
    await check("190 MB audio is allowed and oversized files are rejected before metadata or storage", async () => {
        const h=harness();
        assert.equal(h.api.audioFileSizeAllowed({size:190*1024*1024}),true);
        assert.equal(h.api.audioFileSizeAllowed({size:256*1024*1024}),true);
        const oversized={size:256*1024*1024+1,name:"large.wav",type:"audio/wav",slice(){throw new Error("must not read oversized audio");}};
        await h.api.handleAudioFileSelection({target:{files:[oversized],value:"selected"}});
        assert.equal(h.api.state.submission.audioSegments.length,0);assert.equal(h.api.state.files.audio.length,0);
        assert.match(h.element("#audio-selection-note").textContent,/256.*未添加.*不影响/);
        h.api.state.options.maxAudioBytes=100*1024*1024;
        assert.equal(h.api.audioFileSizeAllowed({size:190*1024*1024}),false);
        h.api.state.options.maxAudioBytes=0;
        assert.equal(h.api.audioFileSizeAllowed({size:1}),false);
    });
    await check("large audio upload keeps progressing beyond short budgets with a fixed 20-minute cap", async () => {
        const h=harness();const current=xhrFixture(h);const max=20*60*1000;
        const operation=h.api.uploadWithXHR("audio/segment1",new Blob(["audio"]),"录音",{},
            {submissionId:"server1",optionalDeadlineMs:Date.now()+max,background:true});
        const xhr=current();assert.ok(xhr.timeout>max-1000 && xhr.timeout<=max);
        const totalTimer=[...h.timerDelays].find(([,delay])=>delay>max-1000)[0];
        const oldIdle=[...h.timerDelays].find(([,delay])=>delay===90000)[0];
        xhr.upload.progress({loaded:10,total:100,lengthComputable:true});
        assert.equal(h.timers.has(oldIdle),false);assert.equal(h.timers.has(totalTimer),true);
        assert.equal([...h.timerDelays.values()].filter(delay=>delay===90000).length,1);
        xhr.status=200;xhr.responseText=JSON.stringify({id:"server1",kind:"audio",segmentId:"segment1"});xhr.events.load();
        assert.equal((await operation).segmentId,"segment1");assert.equal(h.timers.size,0);
    });
    await check("optional upload stops on idle or total deadline without inventing success", async () => {
        for(const total of [false,true]) {
            const h=harness();const current=xhrFixture(h);
            const operation=h.api.uploadWithXHR("audio/segment1",new Blob(["audio"]),"录音",{},
                {submissionId:"server1",optionalDeadlineMs:Date.now()+20*60*1000});
            const selected=[...h.timerDelays].find(([,delay])=>total ? delay>90000 : delay===90000)[0];
            h.timers.get(selected)();
            await assert.rejects(operation,error=>error.uploadOutcome==="UNKNOWN"
                && error.code===(total?"OPTIONAL_MEDIA_UPLOAD_TIMEOUT":"OPTIONAL_MEDIA_IDLE_TIMEOUT"));
            assert.equal(current().aborted,true);assert.equal(h.timers.size,0);
        }
    });
    await check("HTML 413 is a definite oversized rejection and closes all upload timers", async () => {
        const h=harness();const current=xhrFixture(h);
        const operation=h.api.uploadWithXHR("audio/segment1",new Blob(["audio"]),"录音",{},
            {submissionId:"server1",optionalDeadlineMs:Date.now()+20*60*1000});
        const xhr=current();xhr.status=413;xhr.responseText="<html><h1>413 Request Entity Too Large</h1></html>";xhr.events.load();
        await assert.rejects(operation,error=>error.status===413 && error.uploadOutcome==="REJECTED"
            && /重试同一文件无效/.test(error.message) && !error.message.includes("<html>"));
        assert.equal(h.timers.size,0);
        assert.doesNotMatch(h.api.optionalUploadFailureMessage({status:413},"录音",true),/结果未确认/);
    });
    await check("oversized optional audio stays actionable after restore and is never automatically re-uploaded",async()=>{
        const h=harness();const segmentId=webcrypto.randomUUID();
        h.api.state.completed=true;h.api.state.submission.serverId="server1";h.api.state.submission.status="SUBMITTED";
        h.api.state.submission.audioSegments=[{segmentId,uploadState:"LOCAL"}];
        h.api.state.files.audio=[{segmentId,file:new Blob(["audio"])}];
        h.api.setRequest(async()=>({id:"server1",status:"SUBMITTED",uploadedMedia:["storefront-photo"],photoIds:[h.photoId],audioSegmentIds:[]}));
        let uploads=0;h.api.setUpload(async()=>{uploads++;throw Object.assign(new Error("large"),{status:413});});
        await h.api.supplementCurrentEvidence();
        assert.equal(h.api.state.completed,true);assert.equal(h.api.state.submission.audioSegments[0].uploadState,"TOO_LARGE");
        assert.match(h.element("#success-media-note").textContent,/文件过大/);
        h.api.restoreDraft(h.api.snapshotDraft());
        assert.equal(h.api.state.submission.audioSegments[0].uploadState,"TOO_LARGE");
        await h.api.supplementCurrentEvidence();assert.equal(uploads,1);
        let picker=false;h.element("#audio-file").click=()=>{picker=true;};
        await h.api.retryAudioSegment(segmentId);assert.equal(picker,true);assert.equal(uploads,1);
    });
    await check("audio storage quota failure remains visible after metadata save and does not block the main visit",async()=>{
        const h=harness();h.window.SalesCheckinDraftStore.saveMedia=async()=>{throw new Error("quota");};
        assert.equal(await h.api.saveLocalMedia("audio:local",new Blob(["audio"])),false);
        await h.api.persistDraft();
        assert.match(h.element("#draft-save-status").textContent,/附件未在本机保存/);
        assert.match(h.element("#audio-selection-note").textContent,/保留手机原文件/);
        await h.api.submitVisit({preventDefault(){}});assert.equal(h.api.state.completed,true);
    });
    await check("browser recording remains visible before starting and while restoring audio", async () => {
        const h=harness(); h.api.state.ui.visitStep=2;
        const workspace=h.element("#visit-recording-workspace");
        h.api.renderRecordingDisclosure(); assert.equal(workspace.open,true);
        workspace.open=true; h.api.renderRecordingDisclosure(); assert.equal(workspace.open,true);
        workspace.open=false; h.api.state.recorder.starting=true;
        h.api.renderRecordingDisclosure(); assert.equal(workspace.open,true);
        assert.equal(h.element("#recording-stage-badge").textContent,"等待权限");
        assert.equal(h.element("#recording-workspace-summary").getAttribute("aria-disabled"),null,
            "a running recorder must not inherit disabled status from its containing header");
        h.api.state.recorder.starting=false; workspace.open=false;
        h.api.state.submission.audioSegments=[{segmentId:"restored",uploadState:"NEEDS_FILE"}];
        h.api.renderRecordingDisclosure(); assert.equal(workspace.open,true);
        assert.equal(h.element("#recording-stage-badge").hidden,false);
        h.api.state.submission={...h.api.state.submission,clientSubmissionId:"next-visit",audioSegments:[]};
        h.api.renderRecordingDisclosure(); assert.equal(workspace.open,true);
    });
    await check("store search uses native buttons with keyboard navigation and activation", async () => {
        const h = harness(); h.api.state.visit.selectedStore = null;
        const html = fs.readFileSync(path.join(path.dirname(sourcePath), "index.html"), "utf8");
        const searchInput = html.match(/<input id="store-search"[\s\S]*?>/)[0];
        const resultsTag = html.match(/<div id="store-search-results"[\s\S]*?>/)[0];
        assert.doesNotMatch(searchInput, /combobox|aria-autocomplete|aria-haspopup|aria-expanded/);
        assert.doesNotMatch(resultsTag, /listbox/);
        h.api.state.visit.directoryStores = [{storeId:"first",name:"杭州门店一",source:"REGISTERED",checkinEligible:true,nextAction:"CHECK_IN"},{storeId:"second",name:"杭州门店二",source:"REGISTERED",checkinEligible:true,nextAction:"CHECK_IN"}];
        h.api.renderStoreResults(h.api.state.visit.directoryStores);
        const root = h.element("#store-search-results");
        const [first,second] = root.children;
        assert.equal(first.tagName, "BUTTON"); assert.equal(first.type, "button");
        assert.equal(first.getAttribute("role"), null);
        const key = (name, target) => {
            const event = {key:name,currentTarget:target,prevented:false,preventDefault(){this.prevented=true;}};
            return event;
        };
        h.api.handleStoreSearchKeydown(key("ArrowDown",h.element("#store-search")));
        assert.equal(h.context.document.activeElement,first);
        h.api.handleStoreResultKeydown(key("ArrowDown",first));
        assert.equal(h.context.document.activeElement,second);
        h.api.handleStoreResultKeydown(key("Escape",second));
        assert.equal(root.hidden,true); assert.equal(h.context.document.activeElement,h.element("#store-search"));
        for (const name of ["Enter"," "]) {
            const event=key(name,first); h.api.handleStoreResultKeydown(event);
            assert.equal(event.prevented,false,"native button activation must remain enabled");
        }
        first.dispatch("click");
        assert.equal(h.api.state.visit.selectedStore.id,"first"); assert.equal(root.hidden,false);
        assert.equal(root.children[0].getAttribute("aria-pressed"),"true");
        assert.equal(h.element("#store-search").value,"");
    });
    await check("submission without any GPS completes with required photo and server receipt", async () => {
        const h = harness(); await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.api.state.completed,true);
        assert.equal(h.uploaded[0][0], `photos/${h.photoId}`);
        assert.equal(h.requests[0].url,"/submissions");
        assert.equal(h.requests[0].options.body.location ?? null,null);
        assert.equal(h.api.state.submission.status,"SUBMITTED");
    });
    await check("missing required photo never creates a submission or reports success", async () => {
        const h=harness();h.api.state.files.photos=[];h.api.state.submission.photos=[];
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.requests.length,0);assert.equal(h.api.state.completed,false);
    });
    await check("lost create response retries by receipt and reuses one stable payload", async () => {
        const h=harness(); let postCount=0; let seenPayload;
        h.api.setRequest(async(url,options)=>{
            h.requests.push({url,options});
            if(url==="/submissions") { postCount++; seenPayload=options.body;throw new Error("connection lost"); }
            if(url.startsWith("/submissions/by-client/")) return {id:"server1",status:"DRAFT",uploadedMedia:[],audioSegmentIds:[]};
            return {id:"server1",status:"SUBMITTED",submittedAt:new Date().toISOString()};
        });
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.api.state.completed,false);assert.equal(h.api.state.submission.syncState,"UNKNOWN");
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(postCount,1);assert.equal(h.api.state.completed,true);
        assert.equal(h.api.state.submission.attemptedPayload.clientSubmissionId,seenPayload.clientSubmissionId);
        assert.match(h.requests[1].url,/by-client/);
    });
    await check("receipt confirms already uploaded photo after local file loss", async () => {
        const h=harness();h.api.state.submission.attemptedPayload={clientSubmissionId:h.api.state.submission.clientSubmissionId};
        h.api.state.files.photos=[];
        h.api.setRequest(async(url)=>url.includes("by-client")
            ? {id:"server1",status:"DRAFT",uploadedMedia:["storefront-photo"],photoIds:[h.photoId],audioSegmentIds:[]}
            : {id:"server1",status:"SUBMITTED",submittedAt:new Date().toISOString()});
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.uploaded.length,0);assert.equal(h.api.state.completed,true);
    });
    await check("explicit create validation error unlocks payload for correction", async () => {
        const h=harness();h.api.setRequest(async()=>{throw Object.assign(new Error("invalid"),{status:400});});
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.api.state.submission.attemptedPayload,null);
        assert.equal(h.api.state.submission.businessLocked,false);
        assert.equal(h.api.state.submission.syncRequested,false);
    });
    await check("empty completion response cannot masquerade as successful check-in", async () => {
        const h=harness();h.api.setRequest(async(url)=>url==="/submissions"?{id:"server1",status:"DRAFT"}:{});
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.api.state.completed,false);assert.equal(h.api.state.submission.syncState,"UNKNOWN");
    });
    await check("camera picker waits for durable save before opening", async () => {
        const h=harness();let release;let clicked=false;h.context.navigator.userActivation={isActive:true};
        h.window.SalesCheckinDraftStore.save=()=>new Promise(resolve=>{release=resolve;});
        const operation=h.api.preparePhotoPicker({preventDefault(){},target:{click(){clicked=true;}}});
        await Promise.resolve();await Promise.resolve();assert.equal(clicked,false);
        release();await operation;assert.equal(clicked,true);
    });
    await check("lost gesture requires another native tap instead of a blocked synthetic picker", async () => {
        const h=harness();let clicked=false;h.context.navigator.userActivation={isActive:false};
        await h.api.preparePhotoPicker({preventDefault(){},target:{click(){clicked=true;}}});
        assert.equal(clicked,false);assert.match(h.element("#draft-save-status").textContent,/再点一次/);
        let prevented=false;
        await h.api.preparePhotoPicker({preventDefault(){prevented=true;},target:{click(){clicked=true;}}});
        assert.equal(prevented,false);assert.equal(clicked,false);
    });
    await check("gateway HTML and plain text never leak into the user-facing error",async()=>{
        const h=harness();
        for(const body of ["<!DOCTYPE HTML><html><body>Bad Gateway</body></html>", "upstream connection refused"]){
            assert.equal(h.api.parseResponsePayload(body),null);
        }
        assert.equal(h.api.extractApiMessage({message:"<html>502 backend details</html>"}),"");
        assert.equal(h.api.extractApiMessage({message:"请填写客户姓名"}),"请填写客户姓名");
        assert.match(h.api.friendlyHttpError(502),/服务暂时不可用.*已保留/);
    });
    await check("media success must identify the original record and segment",async()=>{
        const h=harness();
        assert.equal(h.api.validMediaReceipt(null,"storefront-photo","s1"),false);
        assert.equal(h.api.validMediaReceipt({id:"s2",kind:"storefront-photo"},"storefront-photo","s1"),false);
        assert.equal(h.api.validMediaReceipt({id:"s1",kind:"audio",segmentId:"a1"},"audio/a1","s1"),true);
        assert.equal(h.api.validMediaReceipt({id:"s1",kind:"audio",segmentId:"a2"},"audio/a1","s1"),false);
    });
    await check("quota failure is visible and never claims locally saved", async () => {
        const h=harness();h.window.SalesCheckinDraftStore.save=async()=>{throw new Error("QuotaExceededError");};
        assert.equal(await h.api.persistDraft(),false);
        assert.match(h.element("#draft-save-status").textContent,/本机未保存/);
    });
    await check("account mismatch cannot hydrate another salesperson's media", async () => {
        const h=harness();let read=false;h.window.SalesCheckinDraftStore.mediaFor=async()=>{read=true;return[];};
        await h.api.openSavedDraft({owner:"t1:other-sales",snapshot:{}});
        assert.equal(read,false);assert.equal(h.api.currentStorageOwner(),"t1:sales1");
    });
    await check("saved photo and audio Blob recover after page memory was cleared", async () => {
        const h=harness();const segmentId=webcrypto.randomUUID();
        h.api.state.submission.audioSegments=[{segmentId,originalFilename:"voice.webm",uploadState:"LOCAL"}];
        const snapshot=h.api.snapshotDraft();const photo=h.api.state.files.photos[0].file;
        const audio=Object.assign(new Blob(["audio"]),{name:"voice.webm"});
        h.window.SalesCheckinDraftStore.mediaFor=async()=>[
            {mediaId:`photo:${h.photoId}`,file:photo,filename:"photo.jpg"},
            {mediaId:`audio:${segmentId}`,file:audio,filename:"voice.webm"}];
        h.api.state.files.photos=[];
        await h.api.openSavedDraft({owner:"t1:sales1",snapshot},false);
        assert.equal(h.api.state.files.photos[0].file.size,photo.size);
        assert.equal(h.api.state.files.photos[0].photoId,h.photoId);
        assert.equal(h.api.state.files.audio[0].segmentId,segmentId);
        assert.equal(h.api.state.submission.audioSegments[0].uploadState,"LOCAL");
        assert.equal(h.api.state.visit.selectedStore.id,"store1");
    });
    await check("identity and GPS-only cache does not show an unfinished visit notice",async()=>{
        const h=harness();Object.assign(h.api.state.visit,{selectedStore:null,customerName:"",visitResult:""});
        h.api.state.files.photos=[];h.api.state.submission.photos=[];h.api.state.restoredAt=new Date().toISOString();
        assert.equal(h.api.hasRestoredDraft(),false);
        h.api.state.visit.selectedStore={id:"store1"};assert.equal(h.api.hasRestoredDraft(),true);
    });
    await check("GPS capture retains selected store and improves beyond first callback", async () => {
        const h=harness();let callback;let cleared=0;h.window.isSecureContext=true;
        h.context.navigator.geolocation={watchPosition(fn){callback=fn;return 7;},clearWatch(){cleared++;}};
        const capture=h.api.captureLocation("visit");
        callback({timestamp:Date.now(),coords:{longitude:120,latitude:30,accuracy:200}});
        assert.equal(cleared,0,"a coarse first sample should leave the bounded watch running");
        callback({timestamp:Date.now()+1,coords:{longitude:120.1,latitude:30.1,accuracy:20}});
        await capture;
        assert.equal(h.api.state.visit.location.accuracyMeters,20);
        assert.equal(h.api.state.visit.selectedStore.id,"store1");assert.equal(cleared,1);
        assert.equal(h.requests.filter(item=>item.url==="/locations/resolve").length,1);
    });
    await check("GPS with no callbacks settles at its deadline without clearing the selected store",async()=>{
        const h=harness();let cleared=0;h.window.isSecureContext=true;
        h.context.navigator.geolocation={watchPosition(){return 9;},clearWatch(){cleared++;}};
        const capture=h.api.captureLocation("visit",{maxWaitMs:1800});
        const deadline=[...h.timerDelays].find(([,delay])=>delay===1800)[0];
        h.timers.get(deadline)();await capture;
        assert.equal(cleared,1);assert.equal(h.api.state.visit.selectedStore.id,"store1");
        assert.equal(h.api.state.visit.locationContext.locationFailureReason,"TIMEOUT");
        assert.equal(h.requests.length,0);
    });
    await check("submission GPS wait blocks double-click and city changes, then creates only the original visit",async()=>{
        const h=harness();h.window.isSecureContext=true;
        h.context.navigator.geolocation={watchPosition(){return 10;},clearWatch(){}};
        const first=h.api.submitVisit({preventDefault(){}});
        assert.equal(h.api.state.submitting,true);
        await h.api.submitVisit({preventDefault(){}});
        h.element("#visit-city").value="苏州";await h.api.handleCityChange("visit");
        assert.equal(h.api.state.visit.city,"杭州");assert.equal(h.requests.length,0);
        const deadline=[...h.timerDelays].find(([,delay])=>delay===5000)[0];h.timers.get(deadline)();
        await first;assert.equal(h.api.state.completed,true);
        const created=h.requests.filter(item=>item.url==="/submissions");assert.equal(created.length,1);
        assert.equal(created[0].options.body.city,"杭州");assert.equal(created[0].options.body.storeId,"store1");
    });
    await check("a changed owner, draft or store cancels the pending GPS submission instead of submitting new content",async()=>{
        for(const change of ["owner","draft","store"]) {
            const h=harness();h.window.isSecureContext=true;let locationCallback;
            h.context.navigator.geolocation={watchPosition(callback){locationCallback=callback;return 10;},clearWatch(){}};
            const submitting=h.api.submitVisit({preventDefault(){}});
            if(change==="owner") h.api.state.identity={authenticated:true,tenantId:"t1",salespersonId:"other"};
            if(change==="draft") h.api.state.submission={...h.api.state.submission,clientSubmissionId:"next-draft"};
            if(change==="store") h.api.state.visit.selectedStore={id:"next-store",name:"新店"};
            const deadline=[...h.timerDelays].find(([,delay])=>delay===5000)[0];h.timers.get(deadline)();await submitting;
            assert.equal(h.requests.length,0);assert.equal(h.api.state.completed,false);assert.equal(h.api.state.submitting,false);
            if(change!=="store") {
                locationCallback({timestamp:Date.now(),coords:{longitude:121,latitude:31,accuracy:10}});
                assert.equal(h.api.state.visit.location,null,"a stale capture must not write coordinates into another identity/draft");
            }
            if(change==="draft") assert.equal(h.api.state.submission.clientSubmissionId,"next-draft");
            if(change==="store") assert.equal(h.api.state.visit.selectedStore.id,"next-store");
        }
    });
    await check("a late location resolve success or failure cannot overwrite a newly opened draft",async()=>{
        for(const failed of [false,true]) {
            const h=harness();const capturedAt=new Date().toISOString();
            h.api.state.visit.location={longitude:120,latitude:30,accuracyMeters:20,capturedAt};
            const next=h.api.snapshotDraft();next.submission.clientSubmissionId=webcrypto.randomUUID();
            next.visit.selectedStore={id:"next-store",name:"新草稿门店"};
            next.visit.location={longitude:121,latitude:31,accuracyMeters:10,capturedAt};
            next.visit.locationContext={geocodeStatus:"RESOLVED",address:"新草稿地址",accuracyAccepted:true,freshnessAccepted:true};
            let resolveOld,rejectOld;
            h.api.setRequest(()=>new Promise((resolve,reject)=>{resolveOld=resolve;rejectOld=reject;}));
            const oldRequest=h.api.resolveLocationContext("visit");
            assert.equal(h.api.state.visit.locationContext.geocodeStatus,"RESOLVING");
            assert.equal(await h.api.openSavedDraft({owner:"t1:sales1",snapshot:next},false),true);
            const currentContext=h.api.state.visit.locationContext;
            const writesBefore=h.saved.length;
            if(failed) rejectOld(new Error("old network error"));
            else resolveOld({geocodeStatus:"RESOLVED",address:"旧地址不得串单",accuracyAccepted:true,freshnessAccepted:true,
                nearbyStores:[{source:"REGISTERED",storeId:"old-store",name:"旧附近店"}]});
            await oldRequest;
            assert.equal(h.api.state.submission.clientSubmissionId,next.submission.clientSubmissionId);
            assert.equal(h.api.state.visit.selectedStore.id,"next-store");
            assert.equal(h.api.state.visit.location.longitude,121);
            assert.equal(h.api.state.visit.locationContext,currentContext);
            assert.equal(h.api.state.visit.locationContext.address,"新草稿地址");
            assert.equal(h.api.state.visit.nearbyStores.some(store=>store.storeId==="old-store"),false);
            assert.equal(h.saved.length,writesBefore,"the stale finally handler must not persist over the new draft");
        }
    });
    await check("photo taken before store selection survives choosing the first store",async()=>{
        const h=harness();const photo=h.api.state.files.photos[0].file;h.api.state.visit.selectedStore=null;
        h.api.selectStore({id:"first-store",name:"首家门店",city:"杭州"});
        assert.equal(h.api.state.files.photos[0].file,photo);assert.equal(h.api.state.visit.selectedStore.id,"first-store");
    });
    await check("initial directory remains enabled while GPS is capturing or denied",async()=>{
        const h=harness();for(const context of [null,{geocodeStatus:"CAPTURING"},{locationFailureReason:"PERMISSION_DENIED"}]){
            h.api.state.visit.locationContext=context;h.api.renderNearbyStores();
            assert.equal(h.element("#store-search").disabled,false);
        }
    });
    await check("supplement uses original visit after operator starts next visit",async()=>{
        const h=harness();const originalId=h.api.state.submission.clientSubmissionId;
        h.api.state.completed=true;h.api.state.submission.serverId="server-original";
        h.api.state.submission.status="SUBMITTED";h.api.state.submission.pendingWechat=true;
        h.api.state.files.wechat=Object.assign(new Blob(["shot"]),{name:"shot.jpg"});
        let release;h.api.setRequest(()=>new Promise(resolve=>{release=resolve;}));
        const op=h.api.supplementCurrentEvidence();await Promise.resolve();await Promise.resolve();
        h.api.state.submission={...h.api.state.submission,clientSubmissionId:"next-visit",serverId:"server-next"};
        release({id:"server-original",status:"SUBMITTED",uploadedMedia:[],audioSegmentIds:[]});await op;
        assert.equal(h.uploaded[0][4].submissionId,"server-original");
        assert.equal(h.api.state.submission.clientSubmissionId,"next-visit");
        assert.ok(h.saved.some(item=>item.snapshot.submission.clientSubmissionId===originalId));
    });

    await check("page re-entry confirms the original submitted receipt without a second POST", async () => {
        const h=harness(); h.api.state.submission.serverId="server1";
        h.api.state.submission.attemptedPayload={clientSubmissionId:h.api.state.submission.clientSubmissionId};
        h.api.state.submission.syncRequested=true; h.api.state.files.photos=[];
        let reads=0;h.api.setRequest(async url=>{assert.match(url,/by-client/);reads++;
            return {id:"server1",status:"SUBMITTED",submittedAt:"2026-09-07T07:15:32Z",uploadedMedia:["storefront-photo"],photoIds:[h.photoId]};});
        await h.api.recoverInterruptedSubmission();
        assert.equal(reads,1);assert.equal(h.api.state.completed,true);assert.equal(h.uploaded.length,0);
        assert.equal(h.element("#success-submitted-at").textContent,"2026-09-07 15:15:32");
    });
    await check("unknown network result never auto-creates or falsely completes a restored visit",async()=>{
        const h=harness();h.api.state.submission.serverId="server1";h.api.state.submission.syncRequested=true;
        let requests=0;h.api.setRequest(async url=>{assert.match(url,/by-client/);requests++;throw new Error("offline");});
        await h.api.recoverInterruptedSubmission();
        assert.equal(requests,1);assert.equal(h.api.state.completed,false);assert.equal(h.uploaded.length,0);
        assert.equal(h.api.state.submission.serverId,"server1");
    });
    await check("receipt for a different visit cannot complete the restored form",async()=>{
        const h=harness();h.api.state.submission.serverId="server1";
        h.api.setRequest(async()=>({id:"server1",clientSubmissionId:"different",status:"SUBMITTED"}));
        await h.api.recoverInterruptedSubmission();assert.equal(h.api.state.completed,false);
    });
    await check("lost completion response is immediately resolved from the server receipt",async()=>{
        const h=harness();let writes=0;
        h.api.setRequest(async url=>{
            if(url==="/submissions"){writes++;return{id:"server1",status:"DRAFT",uploadedMedia:[]};}
            if(url.endsWith("/complete")){writes++;throw Object.assign(new Error("connection lost"),{uploadOutcome:"UNKNOWN"});}
            return{id:"server1",status:"SUBMITTED",uploadedMedia:["storefront-photo"],photoIds:[h.photoId],submittedAt:new Date().toISOString()};
        });
        await h.api.submitVisit({preventDefault(){}});
        assert.equal(h.api.state.completed,true);assert.equal(writes,2);assert.equal(h.uploaded.length,1);
    });
    await check("unknown audio upload is confirmed received without uploading it a second time",async()=>{
        const h=harness();const segmentId=webcrypto.randomUUID();
        Object.assign(h.api.state.submission,{serverId:"server1",status:"SUBMITTED",audioSegments:[{segmentId,uploadState:"LOCAL"}]});
        h.api.state.completed=true;h.api.state.files.audio=[{segmentId,file:new Blob(["audio"])}];
        let reads=0,writes=0;h.api.setRequest(async()=>({id:"server1",status:"SUBMITTED",uploadedMedia:["storefront-photo"],photoIds:[h.photoId],audioSegmentIds:++reads>1?[segmentId]:[]}));
        h.api.setUpload(async()=>{writes++;throw Object.assign(new Error("lost upload reply"),{uploadOutcome:"UNKNOWN"});});
        await h.api.supplementCurrentEvidence();assert.equal(writes,1);assert.equal(reads,2);
        assert.equal(h.api.state.submission.audioSegments[0].uploadState,"UPLOADED");assert.equal(h.api.hasPendingEvidence(),false);
        await h.api.supplementCurrentEvidence();assert.equal(writes,1);
    });
    await check("expired supplement still reconciles previously received audio with no write",async()=>{
        const h=harness();const segmentId=webcrypto.randomUUID();
        Object.assign(h.api.state.submission,{serverId:"server1",status:"SUBMITTED",audioSegments:[{segmentId,uploadState:"UNKNOWN"}]});
        h.api.state.completed=true;
        h.api.setRequest(async()=>({id:"server1",status:"SUBMITTED",supplementUntil:"2000-01-01T00:00:00Z",audioSegmentIds:[segmentId],uploadedMedia:["storefront-photo"],photoIds:[h.photoId]}));
        await h.api.supplementCurrentEvidence();assert.equal(h.api.state.submission.audioSegments[0].uploadState,"UPLOADED");assert.equal(h.uploaded.length,0);
    });
    await check("restoring local drafts keeps the current session visit instead of an older pending one",async()=>{
        const h=harness();const id=h.api.state.submission.clientSubmissionId;
        h.api.state.restoredAt=new Date().toISOString();let mediaReads=0;
        h.window.SalesCheckinDraftStore.mediaFor=async()=>{mediaReads++;return[];};
        const old=h.api.snapshotDraft();old.submission.clientSubmissionId="older-draft";
        h.window.SalesCheckinDraftStore.list=async()=>[{owner:"t1:sales1",snapshot:old}];
        await h.api.restoreOwnedDraft();assert.equal(h.api.state.submission.clientSubmissionId,id);assert.equal(mediaReads,0);
    });
    await check("large non-WAV audio never starts metadata decoding",async()=>{
        const h=harness();let decoded=false;h.context.Audio=function(){decoded=true;throw new Error("must not decode");};
        assert.equal(await h.api.readAudioDurationMs({size:190*1024*1024}),null);assert.equal(decoded,false);
    });
    await check("large PCM WAV duration uses only a bounded header read",async()=>{
        const h=harness();const header=Buffer.alloc(44);header.write("RIFF",0);header.write("WAVEfmt ",8);
        header.writeUInt32LE(16,16);header.writeUInt16LE(1,20);header.writeUInt16LE(2,22);
        header.writeUInt32LE(48000,24);header.writeUInt32LE(192000,28);header.write("data",36);
        const bytes=192000*691;header.writeUInt32LE(bytes,40);let length;
        class Reader{constructor(){this.listeners={};}addEventListener(name,fn){this.listeners[name]=fn;}readAsArrayBuffer(){this.result=header.buffer.slice(header.byteOffset,header.byteOffset+header.byteLength);this.listeners.load();}abort(){}}
        h.window.FileReader=h.context.FileReader=Reader;
        const file={size:bytes+44,slice(start,end){assert.equal(start,0);length=end;return new Blob([header]);}};
        assert.equal(await h.api.readAudioDurationMs(file),691000);assert.ok(length<=65536);
    });
    await check("photo header dimensions reject unknown and truncated formats without decoding",async()=>{
        const h=harness();const jpeg=Buffer.alloc(40);jpeg[0]=255;jpeg[1]=216;jpeg[2]=255;jpeg[3]=192;
        jpeg.writeUInt16BE(17,4);jpeg[6]=8;jpeg.writeUInt16BE(3000,7);jpeg.writeUInt16BE(4000,9);
        const result=h.api.imageHeaderDimensions(jpeg);assert.equal(result.width,4000);assert.equal(result.height,3000);
        assert.equal(h.api.imageHeaderDimensions(Buffer.alloc(30)),null);
        assert.equal(h.api.imageHeaderDimensions(jpeg.subarray(0,10)),null);
        assert.equal(h.api.formatDateTime("2026-09-06T16:00:00Z"),"2026-09-07 00:00:00");
    });

    await check("a delayed draft read cannot overwrite another visit that started submitting",async()=>{
        const h=harness();const currentId=h.api.state.submission.clientSubmissionId;
        const old=h.api.snapshotDraft();old.submission.clientSubmissionId="older";
        let release;h.window.SalesCheckinDraftStore.mediaFor=()=>new Promise(resolve=>{release=resolve;});
        const restore=h.api.openSavedDraft({owner:"t1:sales1",snapshot:old},false);
        await Promise.resolve();h.api.state.submitting=true;release([]);
        assert.equal(await restore,false);assert.equal(h.api.state.submission.clientSubmissionId,currentId);
    });
    await check("edits made while a draft is loading are not overwritten by its late result",async()=>{
        const h=harness();const old=h.api.snapshotDraft();old.submission.clientSubmissionId="older";
        let release;h.window.SalesCheckinDraftStore.mediaFor=()=>new Promise(resolve=>{release=resolve;});
        const restore=h.api.openSavedDraft({owner:"t1:sales1",snapshot:old},false);
        await Promise.resolve();h.api.state.visit.customerName="刚输入的新客户";release([]);
        assert.equal(await restore,false);assert.equal(h.api.state.visit.customerName,"刚输入的新客户");
    });
    console.log(`${checks} recovery behavior checks passed`);
    completed=true;
})().catch(error=>{completed=true;console.error(error);process.exitCode=1;});
