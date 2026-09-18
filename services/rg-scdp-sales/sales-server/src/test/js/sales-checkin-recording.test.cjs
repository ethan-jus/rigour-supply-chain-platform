const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');
const staticDir = path.resolve(__dirname, '../../main/resources/static/sales-checkin');
const appSource = fs.readFileSync(path.join(staticDir, 'app.js'), 'utf8');
const journalSource = fs.readFileSync(path.join(staticDir, 'recording-journal.js'), 'utf8');
const tick = async () => { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); };
class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(type, fn, options) { const list = this.listeners.get(type) || []; list.push({fn, once:options?.once}); this.listeners.set(type,list); }
    removeEventListener(type, fn) { this.listeners.set(type,(this.listeners.get(type)||[]).filter(item=>item.fn!==fn)); }
    emit(type, event={}) { for(const item of [...(this.listeners.get(type)||[])]) { if(item.once) this.removeEventListener(type,item.fn); item.fn({target:this,...event}); } }
}
function harness() {
    let now = 100000; let nextTimer=0; let nextUrl=0; let savedFailure=false;
    const timers = new Map(); const elements = new Map(); const media = new Map(); const writes=[]; const recorders=[];
    function element(id) {
        if(!elements.has(id)) {
            const item=Object.assign(new Events(), {value:'',hidden:false,disabled:false,children:[],dataset:{},tagName:'DIV',
                classList:{toggle(){},add(){},remove(){},contains(){return false;}},setAttribute(){},removeAttribute(){},
                scrollIntoView(){},pause(){this.paused=true;},load(){},append(...children){this.children.push(...children);},
                appendChild(child){this.children.push(child);},replaceChildren(...children){this.children=children;},
                closest(){return element(id+':parent');},querySelector(selector){return element(id+selector);},querySelectorAll(){return [];} });
            elements.set(id,item);
        }
        return elements.get(id);
    }
    const window=Object.assign(new Events(), {isSecureContext:true,File,
        setTimeout(fn,delay){timers.set(++nextTimer,{fn,delay});return nextTimer;},clearTimeout(id){timers.delete(id);},
        setInterval(fn,delay){timers.set(++nextTimer,{fn,delay,interval:true});return nextTimer;},clearInterval(id){timers.delete(id);},
        requestAnimationFrame(fn){fn();},SalesCheckinDraftStore:{
            async save(){}, async saveMedia(owner,draftId,mediaId,file) {
                if(savedFailure) throw new Error('quota');
                writes.push({owner,draftId,mediaId,file}); media.set(`${owner}|${draftId}|${mediaId}`,{owner,draftId,mediaId,file});
            },async mediaFor(owner,draftId){return [...media.values()].filter(item=>item.owner===owner&&item.draftId===draftId);},
            async removeMedia(owner,draftId,id){media.delete(`${owner}|${draftId}|${id}`);}
        }});
    const document=Object.assign(new Events(), {visibilityState:'visible',querySelector:element,querySelectorAll(){return [];},
        createElement(tag){return element(`new:${tag}:${Math.random()}`);},body:element('body')});
    const track=Object.assign(new Events(), {readyState:'live',muted:false,enabled:true,stops:0,stop(){this.stops++;this.readyState='ended';}});
    const stream={getTracks:()=>[track],getAudioTracks:()=>[track]};
    class Recorder extends Events {
        static isTypeSupported(){return true;}
        constructor(){super();this.state='inactive';this.mimeType='audio/webm';this.stops=0;this.requests=0;recorders.push(this);}
        start(){this.state='recording';}
        pause(){this.state='paused';this.emit('pause');}
        resume(){this.state='recording';this.emit('resume');}
        requestData(){this.requests++;}
        stop(){this.stops++;this.state='inactive';}
        chunk(text){this.emit('dataavailable',{data:new Blob([text],{type:this.mimeType})});}
        finish(){this.state='inactive';this.emit('stop');}
    }
    window.MediaRecorder=Recorder;
    const audioSession=Object.assign(new Events(),{type:'auto',state:'active'});
    const locks=[];
    const navigator={mediaDevices:{async getUserMedia(){return stream;}},audioSession,
        wakeLock:{async request(){const lock=Object.assign(new Events(),{releases:0,async release(){this.releases++;this.emit('release');}});locks.push(lock);return lock;}}};
    const BrowserDate=class extends Date { constructor(...args){super(...(args.length?args:[now]));} static now(){return now;} };
    const context={window,document,navigator,Blob,File,MediaRecorder:Recorder,Date:BrowserDate,crypto:webcrypto,
        URL:{createObjectURL(){return `blob:test-${++nextUrl}`;},revokeObjectURL(){}},
        sessionStorage:{setItem(){},getItem(){return null;}},localStorage:{removeItem(){}},
        clearInterval:window.clearInterval,btoa:text=>Buffer.from(text,'binary').toString('base64')};
    vm.runInNewContext(journalSource,context);
    const source=appSource.replace(/\n\}\)\(\);\s*$/, `
        renderFlowSteps=renderAudioSegments=renderUploadedBadges=syncStateFromForm=()=>{};
        beginAudioFileMetadataRead=()=>{};
        window.__recordingTest={state,bindEvents,toggleRecording,stopRecording,finishRecording,updateRecordingClock,
            resumeRecordingLifecycle,recoverRecordingJournals,renderRecordingRecoveries,recordingActiveDuration,isRecording,recordingBusy,
            setInitialized(){initialized=true;}};
    })();`);
    vm.runInNewContext(source,context);
    const api=window.__recordingTest;
    api.state.identity={authenticated:true,tenantId:'tenant',salespersonId:'sales'};
    api.state.visit.city='杭州';api.state.visit.salespersonId='sales';api.state.visit.selectedStore={id:'store',name:'示例店'};
    api.state.ui.visitStep=2;
    return {api,window,document,track,recorders,media,writes,elements,element,audioSession,locks,timers,
        advance(ms){now+=ms;},failStorage(){savedFailure=true;},setStorageHealthy(){savedFailure=false;},context};
}

test('hidden checkpoints an active microphone without stopping; background chunks remain in the same session',async()=>{
    const h=harness();h.api.bindEvents();await h.api.toggleRecording();await tick();const recorder=h.recorders[0];
    recorder.chunk('first');h.document.visibilityState='hidden';h.document.emit('visibilitychange');recorder.chunk('second');await tick();
    assert.equal(recorder.stops,0);assert.equal(h.track.stops,0);assert.ok(recorder.requests>0);
    assert.equal(h.api.state.recorder.activeSession.chunks.length,2);
    assert.equal(h.writes.filter(item=>item.mediaId.includes(':chunk:')).length,2);
    h.api.stopRecording();recorder.chunk('tail');recorder.finish();await tick();
    const audio=[...h.media.values()].find(item=>item.mediaId.startsWith('audio:'));
    assert.equal(await audio.file.text(),'firstsecondtail');assert.equal(h.api.state.submission.audioSegments.length,1);
    assert.equal(h.api.state.submission.audioSegments[0].clientDurationMs,null,'wall time is not an encoded-duration claim');
    assert.match(audio.file.name,/后台录音待回放/);assert.equal(h.audioSession.type,'auto');assert.equal(h.locks[0].releases,1);
});

test('mute and recorder pause exclude known unavailable intervals; unmute resumes the same recording',async()=>{
    const h=harness();await h.api.toggleRecording();const recorder=h.recorders[0];h.advance(2000);
    h.track.muted=true;h.track.emit('mute');assert.equal(recorder.state,'paused');assert.equal(h.api.isRecording(),false);
    h.advance(30000);h.api.updateRecordingClock();assert.equal(h.api.state.recorder.elapsedMs,2000);assert.equal(h.api.recordingBusy(),true);
    assert.equal(h.element('#record-button-label').textContent,'结束并保存');
    h.track.muted=false;h.track.emit('unmute');h.advance(1000);h.api.updateRecordingClock();
    assert.equal(recorder.state,'recording');assert.equal(h.api.state.recorder.elapsedMs,3000);assert.equal(recorder.stops,0);
});

test('AudioSession interruption pauses until available, and an ended track finalizes instead of reopening the microphone',async()=>{
    const h=harness();await h.api.toggleRecording();const recorder=h.recorders[0];recorder.chunk('kept');
    h.audioSession.state='interrupted';h.audioSession.emit('statechange');assert.equal(recorder.state,'paused');
    h.audioSession.state='active';h.audioSession.emit('statechange');assert.equal(recorder.state,'recording');
    h.track.readyState='ended';h.track.emit('ended');assert.equal(recorder.stops,1);recorder.finish();await tick();
    assert.equal(h.recorders.length,1);assert.equal(h.api.state.submission.audioSegments.length,1);
    assert.match(h.api.state.submission.audioSegments[0].originalFilename,/中断后保留/);
});

test('stop timeout exposes an unfinished combined recovery file and never appends it as a normal recording',async()=>{
    const h=harness();await h.api.toggleRecording();const recorder=h.recorders[0];recorder.chunk('partial');h.api.stopRecording();
    const fallback=[...h.timers.values()].find(item=>item.delay===5000);fallback.fn();await tick();
    assert.equal(h.api.state.submission.audioSegments.length,0);assert.equal(h.api.state.recorder.recoveries.length,1);
    const entries=await h.window.SalesCheckinRecordingJournal.list('tenant:sales',h.api.state.submission.clientSubmissionId);
    assert.equal(entries[0].complete,false);assert.equal(await entries[0].blob.text(),'partial');
    recorder.chunk('late');recorder.finish();await tick();assert.equal(h.api.state.submission.audioSegments.length,0);
});

test('storage failure retains in-page data and reports uncertainty instead of pretending chunks were saved',async()=>{
    const h=harness();h.failStorage();await h.api.toggleRecording();h.recorders[0].chunk('in-memory');await tick();
    assert.equal(h.api.state.recorder.activeSession.chunks.length,1);assert.equal(h.api.state.recorder.activeSession.journalFailed,true);
    assert.match(h.element('#audio-selection-note').textContent,/未能.*保存/);assert.equal(h.media.size,0);
});

test('completed journal recovery is idempotent; an unfinished or other-owner journal is never auto-added',async()=>{
    const h=harness();const store=h.window.SalesCheckinRecordingJournal;const draft=h.api.state.submission.clientSubmissionId;
    const finished=store.create('tenant:sales',draft,'complete-session',{mimeType:'audio/webm'});
    await finished.append(new Blob(['all']));await finished.finish({finalized:true});
    const open=store.create('tenant:sales',draft,'unfinished-session',{mimeType:'audio/webm'});await open.append(new Blob(['part']));
    const other=store.create('tenant:other',draft,'other-session',{mimeType:'audio/webm'});await other.append(new Blob(['private']));await other.finish({finalized:true});
    await h.api.recoverRecordingJournals();await h.api.recoverRecordingJournals();
    assert.equal(h.api.state.submission.audioSegments.length,1);assert.equal(h.api.state.submission.audioSegments[0].segmentId,'complete-session');
    assert.equal(h.api.state.recorder.recoveries.length,1);assert.equal(h.api.state.recorder.recoveries[0].sessionId,'unfinished-session');
    assert.equal((await store.list('tenant:other',draft)).length,1);
});

test('journal writes chunks once in order and only marks completion after each write succeeds',async()=>{
    const h=harness();const store=h.window.SalesCheckinRecordingJournal;const draft='visit';
    const handle=store.create('tenant:sales',draft,'session',{mimeType:'audio/webm'});
    const writes=[handle.append(new Blob(['a'])),handle.append(new Blob(['b'])),handle.finish({finalized:true})];
    await Promise.all(writes);const entries=await store.list('tenant:sales',draft);
    assert.equal(entries[0].complete,true);assert.equal(await entries[0].blob.text(),'ab');
    const ids=h.writes.filter(item=>item.mediaId.includes(':chunk:')).map(item=>item.mediaId);
    assert.deepEqual(ids,['recording:session:chunk:000000','recording:session:chunk:000001']);
    await assert.rejects(handle.append(new Blob(['late'])));
    h.media.delete('tenant:sales|visit|recording:session:chunk:000000');assert.equal((await store.list('tenant:sales',draft))[0].complete,false);
});

test('an earlier journal write failure cannot be hidden by a successful FINALIZED manifest',async()=>{
    const h=harness();const handle=h.window.SalesCheckinRecordingJournal.create('tenant:sales','visit','session');await handle.ready;
    h.failStorage();await assert.rejects(handle.append(new Blob(['x'])));h.setStorageHealthy();await assert.rejects(handle.finish({finalized:true}));
    const manifest=JSON.parse(await [...h.media.values()][0].file.text());assert.equal(manifest.status,'OPEN');
});

test('a delayed recovery read cannot attach the previous owner audio after identity changes',async()=>{
    const h=harness();const draftId=h.api.state.submission.clientSubmissionId;let resolveRead;
    h.window.SalesCheckinRecordingJournal={list:()=>new Promise(resolve=>{resolveRead=resolve;}),async remove(){throw new Error('must not remove old owner');}};
    const operation=h.api.recoverRecordingJournals();
    h.api.state.identity={authenticated:true,tenantId:'tenant',salespersonId:'other'};
    resolveRead([{complete:true,sessionId:'old-recording',owner:'tenant:sales',draftId,blob:new Blob(['private']),mimeType:'audio/webm'}]);
    await operation;assert.equal(h.api.state.submission.audioSegments.length,0);assert.equal(h.writes.length,0);
    h.api.state.recorder.recoveries=[{owner:'tenant:sales',draftId,objectUrl:'blob:old',sessionId:'old-recording'}];
    h.api.renderRecordingRecoveries();assert.equal(h.api.state.recorder.recoveries.length,0);
    assert.equal(h.element('#recording-recovery-list').children.length,0);
});

test('a late pause or data event from a finalized recorder cannot alter the next recording',async()=>{
    const h=harness();await h.api.toggleRecording();const old=h.recorders[0];old.chunk('one');old.finish();await tick();
    h.track.readyState='live';await h.api.toggleRecording();const next=h.api.state.recorder.activeSession;
    h.advance(1000);old.emit('pause');old.chunk('late');h.api.updateRecordingClock();
    assert.equal(h.api.state.recorder.activeSession,next);assert.equal(next.interrupted,false);
    assert.equal(next.chunks.length,0);assert.equal(h.api.isRecording(),true);
});
