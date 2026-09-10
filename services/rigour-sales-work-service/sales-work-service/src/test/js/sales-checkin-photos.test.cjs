const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {webcrypto} = require('node:crypto');
const staticDir = path.resolve(__dirname, '../../main/resources/static/sales-checkin');
const source = fs.readFileSync(path.join(staticDir, 'photos.js'), 'utf8');
const namespace = {};
vm.runInNewContext(source, {window: namespace});
const photos = namespace.SalesCheckinPhotos;
const id = number => `10000000-0000-4000-8000-${String(number).padStart(12, '0')}`;
const file = (name = '门店.jpg', size = 1000) => ({name, size, type: 'image/jpeg'});

test('unique IDs, capture source and nine-photo boundary survive metadata normalization', () => {
    const rows = Array.from({length: 11}, (_, i) => photos.record(file(), id(i), 'CAMERA'));
    const result = photos.normalize([rows[0], rows[0], {photoId: 'bad'}, ...rows.slice(1)]);
    assert.equal(result.length, 9);
    assert.equal(new Set(result.map(row => row.photoId)).size, 9);
    assert.equal(result[0].captureSource, 'CAMERA');
    assert.equal(photos.record(file(), id(55), 'FILE_IMPORT').captureSource, 'FILE_IMPORT');
    assert.equal(photos.record(file(), id(56), 'LEGACY').captureSource, null);
    assert.equal(result[0].mediaId, `photo-${id(0)}`);
});

test('restore distinguishes uncertain upload from missing local file', () => {
    const rows = photos.normalize([{...photos.record(file(), id(1), null), uploadState: 'UPLOADING'}, photos.record(file(), id(2), null)], true);
    assert.equal(rows[0].uploadState, 'UNKNOWN');
    assert.equal(rows[0].mayExistRemotely, true);
    assert.equal(rows[1].uploadState, 'NEEDS_FILE');
});

test('receipts merge by photo ID, preserve pending local photos, and confirm absence only for a full photoIds list', () => {
    const rows = [photos.record(file(), id(1), 'CAMERA'), photos.record(file(), id(2), 'FILE_IMPORT')];
    const merged = photos.merge(rows, {photos: [{photoId:id(1), thumbnailUrl:'/sales-checkin/api/v1/submissions/x/media/photo'}]});
    assert.equal(merged.length, 2);
    assert.equal(merged[0].uploadState, 'UPLOADED');
    assert.equal(merged[1].uploadState, 'LOCAL');
    assert.equal(photos.merge(merged, {photos:[{photoId:id(2)}]})[0].uploadState, 'UPLOADED');
    const absent = photos.merge(merged, {photoIds:[], photos:[]});
    assert.equal(absent[0].uploadState, 'NEEDS_FILE');
    assert.equal(absent[0].mayExistRemotely, false);
    assert.equal(absent[1].uploadState, 'LOCAL');
});

function previewEnv(overrides = {}) {
    const trace = {active:0, maximum:0, closed:0, created:[], revoked:[], canvases:[], calls:[]};
    let sequence = 0;
    const env = {
        readPrefix: async file => file,
        dimensions: () => ({width:4000,height:3000}),
        createImageBitmap: async (file, options) => {
            trace.calls.push(options); trace.active++; trace.maximum = Math.max(trace.maximum, trace.active);
            await new Promise(resolve => setTimeout(resolve, 2));
            return {width:384,height:288,close(){trace.active--;trace.closed++;}};
        },
        URL: {createObjectURL(blob){const value=`blob:photo-${++sequence}`;trace.created.push(value);return value;},revokeObjectURL(value){trace.revoked.push(value);}},
        document:{createElement(){const canvas={width:0,height:0,getContext(){return {fillRect(){},drawImage(){}};},toBlob(fn){fn(new Blob(['thumbnail'],{type:'image/jpeg'}));}};trace.canvases.push(canvas);return canvas;}},
        setTimeout, clearTimeout,
        ...overrides
    };
    return {env,trace};
}

test('nine ordinary 12MP photos use one decoder at a time and close every bitmap', async () => {
    const {env,trace}=previewEnv(); const queue=photos.createPreviewQueue(env);
    await Promise.all(Array.from({length:9},(_,i)=>queue.enqueue(id(i),file())));
    assert.equal(trace.maximum,1);assert.equal(trace.closed,9);
    assert.equal(trace.calls[0].resizeWidth,384);
    assert.equal(trace.created.length,9);
    assert.ok(trace.canvases.every(canvas=>canvas.width===1&&canvas.height===1));
    queue.releaseAll();assert.equal(trace.revoked.length,9);
});

test('unknown or over-16MP sources never reach a decoder', async () => {
    for(const dimensions of [null,{width:8000,height:6000}]){
        const {env,trace}=previewEnv({dimensions:()=>dimensions});
        assert.equal(await photos.createPreviewQueue(env).enqueue(id(1),file()),null);
        assert.equal(trace.calls.length,0);assert.equal(trace.created.length,0);
    }
});

test('cancelling a photo while it decodes closes its bitmap without exposing a stale URL', async () => {
    const {env,trace}=previewEnv();const queue=photos.createPreviewQueue(env);
    const pending=queue.enqueue(id(1),file());
    await new Promise(resolve=>setTimeout(resolve,1));queue.release(id(1));await pending;
    assert.equal(queue.get(id(1)),null);assert.equal(trace.closed,1);assert.equal(trace.created.length,0);
});

test('known 12MP fallback decodes once and releases the original object URL and image', async () => {
    let removed=0;
    class Image {constructor(){this.width=4000;this.height=3000;}set src(value){queueMicrotask(()=>this.onload?.());}removeAttribute(){removed++;}}
    const {env,trace}=previewEnv({createImageBitmap:async()=>{throw Error('unsupported');},Image});
    const queue=photos.createPreviewQueue(env);assert.ok(await queue.enqueue(id(1),file()));
    assert.equal(removed,1);assert.equal(trace.created.length,2);
    assert.deepEqual(trace.revoked,[trace.created[0]]);
    queue.releaseAll();assert.equal(trace.revoked.length,2);
});

test('bitmap is released when canvas processing fails', async () => {
    const {env,trace}=previewEnv({document:{createElement(){throw Error('canvas unavailable');}}});
    assert.equal(await photos.createPreviewQueue(env).enqueue(id(1),file()),null);
    assert.equal(trace.closed,1);
});

function appHarness() {
    const events={saved:[],deleted:[],requests:[],notes:[],snapshots:[],started:0};
    const elements=new Map();
    const element=selector=>{if(!elements.has(selector))elements.set(selector,{value:'',textContent:'',hidden:false,disabled:false,classList:{toggle(){}},setAttribute(){},removeAttribute(){},close(){},replaceChildren(){}});return elements.get(selector);};
    const window={SalesCheckinPhotos:photos,location:{href:'https://example.test/sales-checkin/',origin:'https://example.test'},setTimeout,clearTimeout,confirm:()=>true};
    const injection=`
        renderPhotos = () => {}; renderFlowActions = () => {}; resumeActiveVisit = () => {};
        prepareSafePhotoPreview = async () => {}; emitClientDiagnostic = () => {};
        clearFieldError = () => {}; setFieldError = () => {}; renderDraftSaveStatus = () => {};
        photoSelectionNote = (message) => window.__events.notes.push(message);
        saveLocalMedia = async (key, file) => { window.__events.saved.push({key, file, snapshot:snapshotDraft()}); return window.__save ? window.__save(key,file) : true; };
        deleteLocalMedia = key => window.__events.deleted.push(key);
        persistDraft = async () => { window.__events.snapshots.push(snapshotDraft()); return true; };
        requestJson = async (url, options) => { window.__events.requests.push({url,options}); return window.__request ? window.__request(url,options) : {}; };
        setFormsDisabled = () => {}; recordingBusy = () => false; renderUploadedBadges = () => {};
        lookupSubmissionReceipt = async () => window.__receipt;
        showSuccess = () => window.__events.notes.push("submitted kept");
        showError = message => window.__events.notes.push(message);
        startNewSubmission = () => window.__events.started++;
        window.SalesCheckinDraftStore = {remove: async (owner, draftId) => window.__events.deleted.push({owner,draftId})};
        window.__test = {state,handlePhotoSelection,removePhoto,restorePhotoMetadata,restorePhotoMedia,
            snapshotDraft,findPhoto,photoFile,mergePhotoReceipt,photoAppendAllowed,discardDraft};
    `;
    const app=fs.readFileSync(path.join(staticDir,'app.js'),'utf8').replace(/\n\}\)\(\);\s*$/,`${injection}\n})();`);
    window.__events=events;
    vm.runInNewContext(app,{window,document:{addEventListener(){},querySelector:element},navigator:{},crypto:webcrypto,Blob,Headers,URL,
        btoa:value=>Buffer.from(value,'binary').toString('base64')});
    const api=window.__test;
    Object.assign(api.state,{identity:{authenticated:true,tenantId:id(99),salespersonId:id(100)}});
    return {api,events,window};
}

test('album appends all selected photos under stable individual draft media keys; a camera photo appends next', async () => {
    const {api,events}=appHarness();
    await api.handlePhotoSelection({target:{id:'photo-album-input',files:[file('a.jpg'),file('b.jpg')],value:'chosen'}});
    await api.handlePhotoSelection({target:{id:'storefront-photo',files:[file('c.jpg')],value:'chosen'}});
    assert.equal(api.state.files.photos.length,3);assert.equal(events.saved.length,3);
    const ids=api.state.submission.photos.map(photo=>photo.photoId);
    assert.equal(new Set(ids).size,3);
    assert.deepEqual(Array.from(api.snapshotDraft().localMediaIds),Array.from(ids,photoId=>`photo:${photoId}`));
    assert.equal(api.state.submission.photos[0].captureSource,'FILE_IMPORT');
    assert.equal(api.state.submission.photos[2].captureSource,'CAMERA');
    assert.equal(events.saved[0].snapshot.submission.photos[0].photoId,ids[0]);
    assert.equal(api.state.files.photo,api.state.files.photos[0].file);
});

test('tenth photo and invalid/oversized images are rejected without losing the nine accepted files', async () => {
    const {api,events}=appHarness();
    await api.handlePhotoSelection({target:{id:'photo-album-input',files:[{name:'bad.txt',size:10,type:'text/plain'},file('too-large.jpg',11*1024*1024),...Array.from({length:10},(_,i)=>file(`${i}.jpg`))],value:'chosen'}});
    assert.equal(api.state.submission.photos.length,9);assert.equal(events.saved.length,9);
    assert.ok(events.notes.some(note=>note?.includes('最多 9 张')));
    assert.ok(events.notes.some(note=>note?.includes('2 张未添加')));
});

test('switching identity during Blob persistence prevents remaining files from entering a different draft', async () => {
    const {api,events,window}=appHarness();
    window.__save=async()=>{api.state.identity={authenticated:true,tenantId:id(99),salespersonId:id(101)};return true;};
    await api.handlePhotoSelection({target:{id:'photo-album-input',files:[file('a.jpg'),file('b.jpg')],value:'chosen'}});
    assert.equal(events.saved.length,1);assert.equal(api.state.files.photos.length,1);
});

test('remote draft photo deletion uses exact UUID endpoint and retains local data after an uncertain failure', async () => {
    const {api,events,window}=appHarness();
    api.state.submission.serverId=id(9);const record=photos.record(file(),id(1),'CAMERA');record.mayExistRemotely=true;record.uploadState='UNKNOWN';
    api.state.submission.photos=[record];api.state.files.photos=[{photoId:id(1),file:file()}];
    window.__request=async()=>{throw Error('network lost');};assert.equal(await api.removePhoto(id(1)),false);
    assert.equal(api.state.submission.photos.length,1);assert.equal(events.deleted.length,0);
    window.__request=async()=>({});assert.equal(await api.removePhoto(id(1)),true);
    assert.equal(api.state.submission.photos.length,0);assert.equal(events.requests[1].url,`/submissions/${id(9)}/media/photos/${id(1)}`);
    assert.equal(events.requests[1].options.method,'DELETE');assert.deepEqual(events.deleted,[`photo:${id(1)}`]);
});

test('submitted received photos cannot be deleted, while additions stop at the supplement deadline', async () => {
    const {api,events}=appHarness();api.state.completed=true;api.state.submission.photos=[{...photos.record(file(),id(1),null),uploadState:'UPLOADED'}];
    assert.equal(await api.removePhoto(id(1)),false);assert.equal(events.requests.length,0);assert.equal(events.deleted.length,0);
    api.state.submission.supplementUntil=new Date(Date.now()+3600000).toISOString();assert.equal(api.photoAppendAllowed(),true);
    api.state.submission.supplementUntil=new Date(Date.now()-1000).toISOString();assert.equal(api.photoAppendAllowed(),false);
});

test('legacy remote and local single-photo records migrate to server UUID without re-upload or early deletion', async () => {
    const {api,events}=appHarness();api.state.submission.serverId=id(9);api.state.submission.uploadedMedia=['storefront-photo'];api.restorePhotoMetadata();
    api.restorePhotoMedia({mediaId:'photo'},file());await Promise.resolve();
    assert.equal(api.state.submission.photos.length,1);assert.equal(api.state.submission.photos[0].photoId,id(9));
    assert.equal(api.state.submission.photos[0].uploadState,'UPLOADED');assert.equal(events.saved[0].key,`photo:${id(9)}`);
    assert.deepEqual(events.deleted,['photo']);
});

test('deleted Blob leftovers are not resurrected during restore', () => {
    const {api}=appHarness();api.state.restoredLocalMediaIds=[];
    api.restorePhotoMedia({mediaId:`photo:${id(1)}`},file());assert.equal(api.state.submission.photos.length,0);
});


test('discard verifies the complete receipt and deletes every remote photo UUID before local draft removal', async () => {
    const {api,events,window}=appHarness();api.state.submission.serverId=id(9);
    window.__receipt={id:id(9),status:'DRAFT',photoIds:[id(1),id(2),id(3)],audioSegmentIds:[id(10)],uploadedMedia:['storefront-photo','audio']};
    await api.discardDraft();
    assert.equal(events.requests.filter(entry=>entry.url.includes('/media/photos/')).length,3);
    assert.ok([id(1),id(2),id(3)].every(photoId=>events.requests.some(entry=>entry.url.endsWith(`/photos/${photoId}`))));
    assert.equal(events.requests.filter(entry=>entry.url.endsWith(`/audio/${id(10)}`)).length,1);
    assert.equal(events.started,1);assert.ok(events.deleted.some(value=>value?.draftId===api.state.submission.clientSubmissionId));
});

test('discard retains local files and never DELETEs when the server draft cannot be verified', async () => {
    for(const receipt of [null,{id:id(9),status:'DRAFT',uploadedMedia:['storefront-photo']}]){
        const {api,events,window}=appHarness();api.state.submission.serverId=id(9);window.__receipt=receipt;
        await api.discardDraft();assert.equal(events.requests.length,0);assert.equal(events.deleted.length,0);assert.equal(events.started,0);
    }
});

test('discard cannot delete a record that became submitted while the browser was away', async () => {
    const {api,events,window}=appHarness();api.state.submission.serverId=id(9);window.__receipt={id:id(9),status:'SUBMITTED',photoIds:[id(1)]};
    await api.discardDraft();assert.equal(events.requests.length,0);assert.equal(events.deleted.length,0);assert.equal(events.started,0);
    assert.ok(events.notes.includes('submitted kept'));
});

test('partial server cleanup failure keeps local draft available for a receipt-first retry', async () => {
    const {api,events,window}=appHarness();api.state.submission.serverId=id(9);window.__receipt={id:id(9),status:'DRAFT',photoIds:[id(1),id(2)],audioSegmentIds:[]};
    window.__request=async url=>{if(url.endsWith(id(2)))throw Error('connection lost');return {};};
    await api.discardDraft();assert.equal(events.requests.length,2);assert.equal(events.deleted.length,0);assert.equal(events.started,0);
});


test('permanent image rejection status and unknown size survive saved metadata restoration', () => {
    for(const status of [413,415]){
        const result=photos.normalize([{...photos.record(file(),id(1),'CAMERA'),sizeBytes:null,uploadState:'ERROR',uploadErrorStatus:status}],true);
        assert.equal(result[0].uploadErrorStatus,status);assert.equal(result[0].sizeBytes,null);
    }
});
